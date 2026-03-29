package com.livetv.android

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.databinding.ItemNativeWatchAudioTrackBinding

class NativeWatchAudioTrackAdapter(
    private val onTrackSelected: (NativeWatchAudioTrack) -> Unit,
) : RecyclerView.Adapter<NativeWatchAudioTrackAdapter.NativeWatchAudioTrackViewHolder>() {
    private val items = mutableListOf<NativeWatchAudioTrack>()

    fun submitList(tracks: List<NativeWatchAudioTrack>) {
        items.clear()
        items.addAll(tracks)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NativeWatchAudioTrackViewHolder {
        val binding =
            ItemNativeWatchAudioTrackBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return NativeWatchAudioTrackViewHolder(binding, onTrackSelected)
    }

    override fun onBindViewHolder(holder: NativeWatchAudioTrackViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class NativeWatchAudioTrackViewHolder(
        private val binding: ItemNativeWatchAudioTrackBinding,
        private val onTrackSelected: (NativeWatchAudioTrack) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NativeWatchAudioTrack) {
            binding.nativeAudioTrackChip.text = item.shortLabel.ifBlank { item.label }
            binding.nativeAudioTrackChip.alpha = if (item.selected) 1f else 0.72f
            binding.nativeAudioTrackChip.isSelected = item.selected
            binding.nativeAudioTrackChip.setBackgroundColor(
                Color.parseColor(if (item.selected) "#2EFFFFFF" else "#14FFFFFF"),
            )
            binding.nativeAudioTrackChip.setOnClickListener {
                onTrackSelected(item)
            }
        }
    }
}
