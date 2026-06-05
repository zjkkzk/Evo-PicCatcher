package com.pic.catcher.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pic.catcher.databinding.ItemStoragePathBinding
import java.io.File

data class StoragePathItem(
    val packageName: String,
    val path: String,
    val sizeStr: String,
    val file: File
)

class StoragePathAdapter(
    private val onOpenClick: (StoragePathItem) -> Unit,
    private val onClearClick: (StoragePathItem) -> Unit
) : ListAdapter<StoragePathItem, StoragePathAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStoragePathBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(private val binding: ItemStoragePathBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StoragePathItem) {
            binding.tvPackageName.text = item.packageName
            binding.tvPath.text = item.path
            binding.tvSize.text = item.sizeStr
            binding.btnOpen.setOnClickListener { onOpenClick(item) }
            binding.btnClear.setOnClickListener { onClearClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<StoragePathItem>() {
        override fun areItemsTheSame(oldItem: StoragePathItem, newItem: StoragePathItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: StoragePathItem, newItem: StoragePathItem): Boolean {
            return oldItem == newItem
        }
    }
}
