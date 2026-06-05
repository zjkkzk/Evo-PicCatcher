package com.pic.catcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lu.magic.util.ToastUtil
import com.lu.magic.util.thread.AppExecutor
import com.pic.catcher.R
import com.pic.catcher.databinding.LayoutStorageBinding
import com.pic.catcher.ui.adapter.StoragePathAdapter
import com.pic.catcher.ui.adapter.StoragePathItem
import com.pic.catcher.util.FileUtils
import java.io.File

class StorageFragment : Fragment() {

    private var _binding: LayoutStorageBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: StoragePathAdapter
    private var allPaths = mutableListOf<StoragePathItem>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabLayout()
        setupRecyclerView()
        loadPaths()
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                filterPaths(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = StoragePathAdapter(
            onOpenClick = { item -> openPath(item) },
            onClearClick = { item -> showClearConfirm(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun loadPaths() {
        AppExecutor.io().execute {
            val paths = mutableListOf<StoragePathItem>()
            val pm = requireContext().packageManager
            
            // 扫描两个可能的根目录
            val internalRoot = requireContext().getExternalFilesDir("Pictures")?.absolutePath
            val externalRoot = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).absolutePath + "/PicCatcher"
            
            val roots = mapOf(
                "Internal" to internalRoot,
                "External" to externalRoot
            )

            roots.forEach { (_, rootPath) ->
                if (rootPath != null) {
                    val rootFile = File(rootPath)
                    if (rootFile.exists() && rootFile.isDirectory) {
                        rootFile.listFiles()?.forEach { file ->
                            if (file.isDirectory && !file.name.startsWith(".")) {
                                val size = FileUtils.getFolderSize(file)
                                if (size >= 0) {
                                    val pkgName = file.name
                                    var appLabel = pkgName
                                    var appIcon: android.graphics.drawable.Drawable? = null
                                    
                                    try {
                                        val appInfo = pm.getApplicationInfo(pkgName, 0)
                                        appLabel = pm.getApplicationLabel(appInfo).toString()
                                        appIcon = pm.getApplicationIcon(appInfo)
                                    } catch (e: Exception) {
                                        // 应用可能已卸载
                                    }

                                    val fileCount = FileUtils.getFileCount(file)

                                    paths.add(
                                        StoragePathItem(
                                            appName = appLabel,
                                            packageName = pkgName,
                                            path = file.absolutePath,
                                            sizeStr = FileUtils.formatFileSize(size),
                                            fileCountStr = "$fileCount files",
                                            file = file,
                                            icon = appIcon
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 按应用名称排序
            paths.sortBy { it.appName }
            allPaths = paths

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                filterPaths(binding.tabLayout.selectedTabPosition)
            }
        }
    }

    private fun filterPaths(position: Int) {
        val internalRoot = requireContext().getExternalFilesDir("Pictures")?.absolutePath ?: ""
        val filtered = if (position == 0) {
            // 内部路径：在 Android/data/com.evo.piccatcher 下的
            allPaths.filter { it.path.contains(internalRoot) }
        } else {
            // 外部路径：在 Pictures/PicCatcher 下的
            val externalRoot = "/Pictures/PicCatcher"
            allPaths.filter { !it.path.contains(internalRoot) && it.path.contains(externalRoot) }
        }
        
        adapter.submitList(filtered)
        if (filtered.isEmpty()) {
            ToastUtil.show(if (position == 0) "内部暂无图片" else "外部暂无图片")
        }
    }

    private fun openPath(item: StoragePathItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    item.file
                )
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                // 尝试直接调用 com.android.documentsui (系统文件应用)
                setPackage("com.android.documentsui")
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                // 如果指定 package 失败，则不限制 package 再次尝试
                intent.setPackage(null)
                intent.setDataAndType(intent.data, "*/*")
                startActivity(Intent.createChooser(intent, "Open Folder"))
            }

        } catch (e: Exception) {
            ToastUtil.show("无法打开目录: ${e.message}")
        }
    }

    private fun showClearConfirm(item: StoragePathItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_delete_sweep)
            .setTitle(R.string.storage_clear_confirm_title)
            .setMessage(getString(R.string.storage_clear_confirm_msg, item.appName))
            .setPositiveButton(R.string.storage_path_cleared) { _, _ ->
                clearPath(item)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearPath(item: StoragePathItem) {
        AppExecutor.io().execute {
            val success = FileUtils.deleteFolder(item.file)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (success) {
                    ToastUtil.show(R.string.storage_path_cleared)
                    loadPaths() // 重新加载
                } else {
                    ToastUtil.show("清空失败")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
