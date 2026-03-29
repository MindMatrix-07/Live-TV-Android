package com.livetv.android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.databinding.ItemNativeWatchProgramBinding

class NativeWatchProgramAdapter : RecyclerView.Adapter<NativeWatchProgramAdapter.NativeWatchProgramViewHolder>() {
    private val items = mutableListOf<NativeWatchProgram>()

    fun submitList(programs: List<NativeWatchProgram>) {
        items.clear()
        items.addAll(programs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NativeWatchProgramViewHolder {
        val binding =
            ItemNativeWatchProgramBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return NativeWatchProgramViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NativeWatchProgramViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class NativeWatchProgramViewHolder(
        private val binding: ItemNativeWatchProgramBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NativeWatchProgram) {
            binding.nativeProgramTitle.text = item.title
            binding.nativeProgramSubtitle.text = item.subtitle
            binding.nativeProgramBadge.visibility =
                if (item.isCurrent && !item.isPlaceholder) View.VISIBLE else View.GONE
            binding.nativeProgramRoot.alpha = if (item.isPlaceholder) 0.75f else 1f
        }
    }
}
