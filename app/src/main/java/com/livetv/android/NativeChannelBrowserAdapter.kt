package com.livetv.android

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.livetv.android.databinding.ItemNativeChannelBrowserBinding

class NativeChannelBrowserAdapter(
    private val onChannelSelected: (NativeWatchChannelListItem) -> Unit,
) : RecyclerView.Adapter<NativeChannelBrowserAdapter.NativeChannelBrowserViewHolder>() {
    private val items = mutableListOf<NativeWatchChannelListItem>()

    fun submitList(channels: List<NativeWatchChannelListItem>) {
        items.clear()
        items.addAll(channels)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NativeChannelBrowserViewHolder {
        val binding =
            ItemNativeChannelBrowserBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return NativeChannelBrowserViewHolder(binding, onChannelSelected)
    }

    override fun onBindViewHolder(holder: NativeChannelBrowserViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class NativeChannelBrowserViewHolder(
        private val binding: ItemNativeChannelBrowserBinding,
        private val onChannelSelected: (NativeWatchChannelListItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NativeWatchChannelListItem) {
            binding.nativeChannelItemName.text = item.name.ifBlank { "Live TV" }
            binding.nativeChannelItemMeta.text =
                buildString {
                    append("CH ")
                    append(item.id.ifBlank { "--" })
                    append(" • ")
                    append(item.playbackMode.ifBlank { "Live" }.uppercase())
                    if (item.isDirectStream) {
                        append(" • DIRECT")
                    }
                }
            binding.nativeChannelItemBadge.text = if (item.isSelected) "Now" else "Tune"
            binding.nativeChannelItemBadge.setBackgroundColor(
                Color.parseColor(if (item.isSelected) "#30FFFFFF" else "#16FFFFFF"),
            )
            binding.nativeChannelItemRoot.setBackgroundColor(
                Color.parseColor(if (item.isSelected) "#26FFFFFF" else "#12FFFFFF"),
            )
            binding.nativeChannelItemRoot.alpha = if (item.isSelected) 1f else 0.86f
            binding.nativeChannelItemRoot.isSelected = item.isSelected
            binding.nativeChannelItemRoot.setOnClickListener {
                onChannelSelected(item)
            }

            if (item.logoUrl.isNullOrBlank()) {
                binding.nativeChannelItemLogo.setImageDrawable(null)
                binding.nativeChannelItemLogo.visibility = View.GONE
            } else {
                binding.nativeChannelItemLogo.visibility = View.VISIBLE
                binding.nativeChannelItemLogo.load(item.logoUrl) {
                    crossfade(true)
                    listener(
                        onError = { _, _ ->
                            binding.nativeChannelItemLogo.visibility = View.GONE
                        },
                    )
                }
            }
        }
    }
}
