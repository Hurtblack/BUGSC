package com.euedrc.bugsc.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.euedrc.bugsc.ImageLoader

object ImagePreviewDialog {

    fun show(fragment: Fragment, urls: List<String>, initialIndex: Int = 0) {
        val state = ImagePreviewState.create(urls, initialIndex) ?: return
        val context = fragment.requireContext()
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val indicator = TextView(context).apply {
            text = state.indicator
            visibility = if (state.indicator.isBlank()) View.GONE else View.VISIBLE
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(context, 26) }
        }
        val pager = ViewPager2(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            adapter = ImageAdapter(fragment, state.urls)
            offscreenPageLimit = 1
            setCurrentItem(state.index, false)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val current = ImagePreviewState.create(state.urls, position) ?: return
                    indicator.text = current.indicator
                    indicator.visibility = if (current.indicator.isBlank()) View.GONE else View.VISIBLE
                }
            })
        }
        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 32f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            layoutParams = FrameLayout.LayoutParams(dp(context, 48), dp(context, 48), Gravity.TOP or Gravity.END)
                .apply {
                    topMargin = dp(context, 22)
                    marginEnd = dp(context, 14)
                }
        }
        root.addView(pager)
        root.addView(indicator)
        root.addView(close)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        dialog.show()
    }

    private class ImageAdapter(
        private val fragment: Fragment,
        private val urls: List<String>,
    ) : RecyclerView.Adapter<ImageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
            val image = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            return ImageHolder(image)
        }

        override fun onBindViewHolder(holder: ImageHolder, position: Int) {
            ImageLoader.load(
                fragment = fragment,
                imageView = holder.image,
                url = urls[position],
                headers = mapOf("Accept" to "image/*,*/*"),
            )
        }

        override fun getItemCount(): Int = urls.size
    }

    private class ImageHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
