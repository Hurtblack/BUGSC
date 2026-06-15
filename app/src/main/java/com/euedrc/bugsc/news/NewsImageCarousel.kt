package com.euedrc.bugsc.news

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.euedrc.bugsc.ImageLoader
import com.euedrc.bugsc.R

class NewsImageCarousel(
    private val fragment: Fragment,
    imageUrls: List<String>,
) {
    val view: View

    private val urls = imageUrls.take(MAX_IMAGES)
    private val handler = Handler(Looper.getMainLooper())
    private var pager: ViewPager2? = null
    private var indicator: TextView? = null
    private var running = false

    private val advance = object : Runnable {
        override fun run() {
            val currentPager = pager ?: return
            if (!running || urls.size < 2) return
            currentPager.setCurrentItem((currentPager.currentItem + 1) % urls.size, true)
            schedule(AUTO_ADVANCE_MILLIS)
        }
    }

    init {
        val context = fragment.requireContext()
        view = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(context, 96), dp(context, 68))
            background = ContextCompat.getDrawable(context, R.drawable.card_bg_blue)
        }
        val placeholder = TextView(context).apply {
            text = "▧\n暂无图片"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.sc_text_dim))
            textSize = 10f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        (view as FrameLayout).addView(placeholder)

        when (urls.size) {
            0 -> Unit
            1 -> view.addView(createImageView(context, urls.single()))
            else -> addPager(context)
        }
    }

    fun resume() {
        running = true
        schedule(AUTO_ADVANCE_MILLIS)
    }

    fun pause() {
        running = false
        handler.removeCallbacks(advance)
    }

    private fun addPager(context: Context) {
        val host = view as FrameLayout
        pager = ViewPager2(context).also { viewPager ->
            viewPager.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            viewPager.adapter = ImageAdapter()
            viewPager.offscreenPageLimit = 1
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateIndicator(position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                        schedule(MANUAL_PAUSE_MILLIS)
                    }
                }
            })
            host.addView(viewPager)
        }
        indicator = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 8f
            gravity = Gravity.CENTER
            setPadding(dp(context, 4), dp(context, 1), dp(context, 4), dp(context, 1))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(context, 3) }
            host.addView(this)
        }
        updateIndicator(0)
    }

    private fun createImageView(context: Context, url: String): ImageView =
        ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            ImageLoader.load(
                fragment = fragment,
                imageView = this,
                url = url,
                headers = mapOf("Accept" to "image/*,*/*"),
            )
        }

    private fun schedule(delayMillis: Long) {
        handler.removeCallbacks(advance)
        if (running && urls.size > 1) handler.postDelayed(advance, delayMillis)
    }

    private fun updateIndicator(position: Int) {
        val dots = urls.indices.joinToString(" ") { if (it == position) "●" else "○" }
        indicator?.text = "$dots  ${position + 1}/${urls.size}"
    }

    private inner class ImageAdapter : RecyclerView.Adapter<ImageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder =
            ImageViewHolder(createImageView(parent.context, urls[viewType]))

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = urls.size

        override fun getItemViewType(position: Int): Int = position
    }

    private class ImageViewHolder(view: ImageView) : RecyclerView.ViewHolder(view)

    companion object {
        private const val MAX_IMAGES = 8
        private const val AUTO_ADVANCE_MILLIS = 4_000L
        private const val MANUAL_PAUSE_MILLIS = 8_000L

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
