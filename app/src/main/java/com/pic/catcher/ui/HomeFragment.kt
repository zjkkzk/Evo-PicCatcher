package com.pic.catcher.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.color.MaterialColors
import com.lu.magic.util.thread.AppExecutor
import com.pic.catcher.AppBuildInfo
import com.pic.catcher.BuildConfig
import com.pic.catcher.R
import com.pic.catcher.SelfHook
import com.pic.catcher.base.BaseFragment
import com.pic.catcher.config.ModuleConfig
import com.pic.catcher.databinding.FragmentHomeBinding
import com.pic.catcher.databinding.ItemInfoRowBinding
import com.pic.catcher.util.RootUtil

class HomeFragment : BaseFragment() {
    private lateinit var binding: FragmentHomeBinding
    private val buildInfo = AppBuildInfo.of()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        initInfo()
        initGithub()
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_settings)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                item.isEnabled = false
                requestRoot()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        initStatus()
        initShellStatus()

        // 每次进入页面都进行一次静默异步检测，确保状态与系统同步（特别是处理撤销权限的情况）
        AppExecutor.io().execute {
            val result = RootUtil.checkRootStatus()
            val configInstance = ModuleConfig.getInstance()
            
            // 如果检测到的状态与配置记录不符，则更新
            if (configInstance.rootStatus != result.status.name || configInstance.suManagerName != result.suName) {
                configInstance.rootStatus = result.status.name
                configInstance.suManagerName = result.suName
                configInstance.save()
                activity?.runOnUiThread {
                    initShellStatus()
                }
            }
        }
    }

    private fun initShellStatus() {
        val context = context ?: return
        val configInstance = ModuleConfig.getInstance()
        
        val status = configInstance.rootStatus
        val suName = configInstance.suManagerName.ifEmpty { "Root" }
        
        val colorTertiaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiaryContainer, Color.LTGRAY)
        val onColorTertiaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnTertiaryContainer, Color.BLACK)
        val colorErrorContainer = errorColorContainer(context)
        val onColorErrorContainer = onColorErrorContainer(context)

        // 设置动态标题
        binding.tvShellStatusTitle.text = suName

        when (status) {
            "AUTHORIZED" -> {
                binding.shellStatusCard.setCardBackgroundColor(onColorTertiaryContainer)
                binding.ivShellStatusIcon.setImageResource(R.drawable.ic_icon_check)
                binding.ivShellStatusIcon.imageTintList = ColorStateList.valueOf(colorTertiaryContainer)
                binding.tvShellStatusTitle.setTextColor(colorTertiaryContainer)
                binding.tvShellStatusDesc.text = "已授权"
                binding.tvShellStatusDesc.setTextColor(colorTertiaryContainer)
            }
            else -> {
                binding.shellStatusCard.setCardBackgroundColor(colorErrorContainer)
                binding.ivShellStatusIcon.setImageResource(R.drawable.ic_icon_warning)
                binding.ivShellStatusIcon.imageTintList = ColorStateList.valueOf(onColorErrorContainer)
                binding.tvShellStatusTitle.setTextColor(onColorErrorContainer)
                binding.tvShellStatusDesc.text = when(status) {
                    "DENIED" -> "已拒绝授权"
                    "NOT_FOUND" -> "未找到 SU 环境"
                    else -> "点击请求授权"
                }
                binding.tvShellStatusDesc.setTextColor(onColorErrorContainer)
            }
        }

        binding.shellStatusCard.isClickable = true
        binding.shellStatusCard.setOnClickListener { 
            requestRoot()
        }
        binding.shellStatusCard.setOnLongClickListener {
            requestRoot()
            true
        }
    }

    private fun errorColorContainer(context: android.content.Context) = 
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorErrorContainer, Color.RED)
    
    private fun onColorErrorContainer(context: android.content.Context) = 
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnErrorContainer, Color.WHITE)

    private fun requestRoot() {
        // 1. 立即提供视觉反馈，让用户知道“刷新”已经开始
        startRefreshAnimation()
        binding.tvShellStatusDesc.text = "正在检测..."
        binding.tvShellStatusTitle.text = "请稍候"
        binding.shellStatusCard.alpha = 0.6f // 增加半透明效果表示正在忙碌
        binding.shellStatusCard.isEnabled = false 
        
        AppExecutor.io().execute {
            // 2. 强制关闭旧会话并彻底清除所有状态缓存
            RootUtil.closeShellSession()
            
            // 3. 执行实测（这会真正重新向系统申请权限）
            val result = RootUtil.checkRootStatus()
            
            val configInstance = ModuleConfig.getInstance()
            configInstance.rootStatus = result.status.name
            configInstance.suManagerName = result.suName
            if (result.status == RootUtil.Status.AUTHORIZED) {
                configInstance.shellAuthType = "Root"
            }
            configInstance.save()
            
            // 4. 回到主线程刷新，稍微延时一点点让用户能看清状态切换
            Thread.sleep(400) 
            activity?.runOnUiThread {
                binding.toolbar.menu.findItem(R.id.action_refresh)?.isEnabled = true
                binding.shellStatusCard.alpha = 1.0f
                binding.shellStatusCard.isEnabled = true
                initShellStatus()
            }
        }
    }

    private fun initStatus() {
        val isActive = SelfHook.getInstance().isModuleEnable
        val context = activity ?: return
        val versionName = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            BuildConfig.VERSION_NAME
        }

        if (isActive) {
            val primaryColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY)
            val onPrimaryColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK)
            binding.statusCard.setCardBackgroundColor(onPrimaryColor)
            binding.ivStatusIcon.setImageResource(R.drawable.ic_icon_check)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(primaryColor)
            binding.tvStatusTitle.text = "已激活"
            binding.tvStatusTitle.setTextColor(primaryColor)
            binding.tvVersion.setTextColor(primaryColor)
        } else {
            val errorColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorErrorContainer, Color.RED)
            val onErrorColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnErrorContainer, Color.WHITE)
            binding.statusCard.setCardBackgroundColor(errorColor)
            binding.ivStatusIcon.setImageResource(R.drawable.ic_icon_warning)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(onErrorColor)
            binding.tvStatusTitle.text = "未激活"
            binding.tvStatusTitle.setTextColor(onErrorColor)
            binding.tvVersion.setTextColor(onErrorColor)
        }
        binding.tvVersion.text = "版本: $versionName"
        binding.statusCard.setOnClickListener { } // 纯解压动画
    }

    private fun initInfo() {
        val deviceBinding = ItemInfoRowBinding.bind(binding.itemDeviceModel.root)
        deviceBinding.tvLabel.text = "设备型号"
        deviceBinding.tvValue.text = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})"

        val systemBinding = ItemInfoRowBinding.bind(binding.itemSystemVersion.root)
        systemBinding.tvLabel.text = "系统版本"
        systemBinding.tvValue.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        val branchBinding = ItemInfoRowBinding.bind(binding.itemBranch.root)
        branchBinding.tvLabel.text = "代码分支"
        branchBinding.tvValue.text = buildInfo.branch

        val timeBinding = ItemInfoRowBinding.bind(binding.itemBuildTime.root)
        timeBinding.tvLabel.text = "构建时间"
        timeBinding.tvValue.text = buildInfo.buildTime
    }

    private fun initGithub() {
        binding.githubCard.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Evo-creative/Evo-PicCatcher"))
                startActivity(intent)
            } catch (e: Exception) { }
        }
    }

    private fun startRefreshAnimation() {
        val view = binding.toolbar.findViewById<View>(R.id.action_refresh) ?: return
        view.animate()
            .rotationBy(360f)
            .setDuration(800)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun stopRefreshAnimation() {
        // 动画会自动完成一圈，无需手动停止
    }
}
