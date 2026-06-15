package com.euedrc.bugsc.news

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil

class SlowMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private var running = false
    private var animator: ValueAnimator? = null

    private val continueScroll = Runnable { animateNextLeg() }

    init {
        setSingleLine(true)
        ellipsize = null
        setHorizontallyScrolling(true)
    }

    fun resumeSlowScroll() {
        if (running) return
        running = true
        post {
            scrollTo(0, 0)
            postDelayed(continueScroll, EDGE_PAUSE_MILLIS)
        }
    }

    fun pauseSlowScroll() {
        running = false
        removeCallbacks(continueScroll)
        animator?.cancel()
        animator = null
        scrollTo(0, 0)
    }

    override fun onDetachedFromWindow() {
        pauseSlowScroll()
        super.onDetachedFromWindow()
    }

    private fun animateNextLeg() {
        if (!running) return
        val overflow = overflowPixels()
        if (overflow <= 0) {
            scrollTo(0, 0)
            return
        }
        val leg = NewsMarqueeTiming.forwardLeg(overflow)
        scrollTo(leg.from, 0)
        animator = ValueAnimator.ofInt(leg.from, leg.to).apply {
            duration = NewsMarqueeTiming.durationMillis(
                distancePx = leg.to - leg.from,
                density = resources.displayMetrics.density,
                speedDpPerSecond = SPEED_DP_PER_SECOND,
            )
            interpolator = LinearInterpolator()
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (!running) return
                    scrollTo(0, 0)
                    postDelayed(continueScroll, EDGE_PAUSE_MILLIS)
                }
            })
            start()
        }
    }

    private fun overflowPixels(): Int {
        val contentWidth = width - compoundPaddingLeft - compoundPaddingRight
        if (contentWidth <= 0) return 0
        return ceil(paint.measureText(text?.toString().orEmpty()) - contentWidth)
            .toInt()
            .coerceAtLeast(0)
    }

    companion object {
        private const val SPEED_DP_PER_SECOND = 18f
        private const val EDGE_PAUSE_MILLIS = 1_200L
    }
}
