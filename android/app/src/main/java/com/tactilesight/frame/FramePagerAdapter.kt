package com.tactilesight.frame

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tactilesight.databinding.ItemFramePageBinding

/** One panel of the capture carousel. */
data class FramePage(
    val label: String,
    val contentDescription: String,
    val bitmap: Bitmap?,
)

/**
 * The three streams of a capture, swipeable: colour, infrared, depth.
 *
 * Shown side by side they were unreadable at a third of the width each — and
 * the IR panel in particular reads as a black rectangle, because the IR frames
 * genuinely are that dark (mean 3/255). That is left honest rather than
 * contrast-stretched: IR exists for dark environments we have no captures of
 * yet, and brightening it here would flatter data nobody has tested.
 */
class FramePagerAdapter : RecyclerView.Adapter<FramePagerAdapter.PageHolder>() {

    private var pages: List<FramePage> = emptyList()

    fun submit(pages: List<FramePage>) {
        this.pages = pages
        notifyDataSetChanged()
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
        ItemFramePageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(pages[position])

    class PageHolder(
        private val binding: ItemFramePageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(page: FramePage) {
            binding.pageImage.setImageBitmap(page.bitmap)
            binding.pageImage.contentDescription = page.contentDescription
            binding.pageLabel.text = page.label
        }
    }
}
