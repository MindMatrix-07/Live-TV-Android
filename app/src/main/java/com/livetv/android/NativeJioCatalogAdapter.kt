package com.livetv.android

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.livetv.android.databinding.ItemNativeJioCatalogBinding

class NativeJioCatalogAdapter(
    private val onActionClicked: (NativeWatchJioCatalogItem) -> Unit,
) : RecyclerView.Adapter<NativeJioCatalogAdapter.NativeJioCatalogViewHolder>() {
    private val items = mutableListOf<NativeWatchJioCatalogItem>()

    fun submitList(channels: List<NativeWatchJioCatalogItem>) {
        items.clear()
        items.addAll(channels)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NativeJioCatalogViewHolder {
        val binding =
            ItemNativeJioCatalogBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return NativeJioCatalogViewHolder(binding, onActionClicked)
    }

    override fun onBindViewHolder(holder: NativeJioCatalogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class NativeJioCatalogViewHolder(
        private val binding: ItemNativeJioCatalogBinding,
        private val onActionClicked: (NativeWatchJioCatalogItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NativeWatchJioCatalogItem) {
            binding.nativeJioCatalogName.text = item.channelName.ifBlank { "Jio Channel" }
            binding.nativeJioCatalogMeta.text =
                buildString {
                    append("Jio ")
                    append(item.channelId.ifBlank { "--" })
                    if (item.categoryName.isNotBlank()) {
                        append(" • ")
                        append(item.categoryName)
                    }
                }

            binding.nativeJioCatalogAction.text = if (item.imported) "Remove" else "Import"
            binding.nativeJioCatalogAction.setBackgroundColor(
                Color.parseColor(if (item.imported) "#2AFFFFFF" else "#18FFFFFF"),
            )
            binding.nativeJioCatalogAction.setOnClickListener {
                onActionClicked(item)
            }

            if (item.logoUrl.isNullOrBlank()) {
                binding.nativeJioCatalogLogo.setImageDrawable(null)
                binding.nativeJioCatalogLogo.visibility = View.GONE
            } else {
                binding.nativeJioCatalogLogo.visibility = View.VISIBLE
                binding.nativeJioCatalogLogo.load(item.logoUrl) {
                    crossfade(true)
                    listener(
                        onError = { _, _ ->
                            binding.nativeJioCatalogLogo.visibility = View.GONE
                        },
                    )
                }
            }
        }
    }
}
