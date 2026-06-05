package com.pic.catcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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
        setupRecyclerView()
        loadPaths()
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
            
            // 扫描两个可能的根目录
            val internalRoot = requireContext().getExternalFilesDir("Pictures")?.absolutePath
            val externalRoot = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).absolutePath + "/PicCatcher"
            
            val rootPaths = listOf(internalRoot, externalRoot)

            rootPaths.filterNotNull().forEach { rootPath ->
                val rootFile = File(rootPath)
                if (rootFile.exists() && rootFile.isDirectory) {
                    rootFile.listFiles()?.forEach { file ->
                        if (file.isDirectory && !file.name.startsWith(".")) {
                            val size = FileUtils.getFolderSize(file)
                            if (size >= 0) { // 即使是 0 也显示，方便查看 and 管理（例如刚清空的）
                                val nomedia = File(file, ".nomedia")
                                val isHidden = nomedia.exists()
                                paths.add(
                                    StoragePathItem(
                                        packageName = file.name + (if (isHidden) " (.nomedia)" else ""),
                                        path = file.absolutePath,
                                        sizeStr = FileUtils.formatFileSize(size),
                                        file = file
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 按包名排序
            paths.sortBy { it.packageName }

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                adapter.submitList(paths)
                if (paths.isEmpty()) {
                    ToastUtil.show(R.string.storage_empty_hint)
                }
            }
        }
    }

    private fun openPath(item: StoragePathItem) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                item.file
            )

            // 优先尝试使用系统文件管理器打开 (Android 7.0+)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 适配 Android 10+ 的 Scoped Storage 限制，
            // 实际上对于第三方应用，很难直接通过 Intent 让其他应用打开一个具体文件夹并显示内容。
            // 这里的最佳实践是调用系统的文件选择器（ACTION_GET_CONTENT 或 ACTION_OPEN_DOCUMENT）
            // 或者使用 ACTION_VIEW 配合特定的 MIME type
            
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // 如果没有专门处理文件夹的应用，尝试以通用方式打开
                val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Open Folder")
                startActivity(chooser)
            }

        } catch (e: Exception) {
            ToastUtil.show("无法打开目录: ${e.message}")
        }
    }

    private fun showClearConfirm(item: StoragePathItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.storage_clear_confirm_title)
            .setMessage(R.string.storage_clear_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
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
