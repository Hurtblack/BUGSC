package com.euedrc.bugsc.shipfit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class PowerDistributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(216, 234, 242)
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val emptyColor = Color.rgb(43, 72, 98)
    private val usedColor = Color.rgb(74, 222, 128)
    private val overflowColor = Color.rgb(255, 138, 61)
    private val textDimColor = Color.rgb(124, 149, 168)
    private var summary: PowerSummary? = null
    private var allocations: MutableList<Int> = MutableList(6) { 0 }
    private var onAllocationChanged: ((List<Int>) -> Unit)? = null

    private val rows = 10
    private var lastStartX = 0f
    private var lastStartY = 0f
    private var lastCellWidth = 0f
    private var lastCellHeight = 0f
    private var lastGap = 0f
    private var lastColumnGap = 0f

    fun setSummary(value: PowerSummary) {
        summary = value
        if (allocations.size != value.groups.size) {
            allocations = MutableList(value.groups.size) { 0 }
        } else {
            allocations = allocations.mapIndexed { index, allocated ->
                val required = value.groups.getOrNull(index)?.second ?: 0
                allocated.coerceIn(0, min(required, rows))
            }.toMutableList()
            trimAllocationsToTotal(value.totalSegments)
        }
        onAllocationChanged?.invoke(allocations.toList())
        invalidate()
    }

    fun setOnAllocationChanged(listener: (List<Int>) -> Unit) {
        onAllocationChanged = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (150f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = summary ?: return
        val labels = listOf("武器", "引擎", "护盾", "量子", "雷达", "冷却")
        val values = s.groups.map { it.second }
        val top = 12f * resources.displayMetrics.density
        val bottomText = 22f * resources.displayMetrics.density
        val gap = 4f * resources.displayMetrics.density
        val columnGap = 11f * resources.displayMetrics.density
        val usableWidth = width - paddingLeft - paddingRight - columnGap * (labels.size - 1)
        val cellWidth = min(30f * resources.displayMetrics.density, usableWidth / labels.size)
        val cellHeight = 8f * resources.displayMetrics.density
        val startX = paddingLeft + (usableWidth - cellWidth * labels.size) / 2f
        val stackHeight = rows * cellHeight + (rows - 1) * gap
        val startY = max(top, height - paddingBottom - bottomText - stackHeight)
        lastStartX = startX
        lastStartY = startY
        lastCellWidth = cellWidth
        lastCellHeight = cellHeight
        lastGap = gap
        lastColumnGap = columnGap

        labels.forEachIndexed { index, label ->
            val x = startX + index * (cellWidth + columnGap)
            val required = values.getOrNull(index).orZero()
            val requiredVisible = min(required, rows)
            val allocated = allocations.getOrNull(index).orZero().coerceIn(0, requiredVisible)
            for (row in 0 until rows) {
                val y = startY + (rows - 1 - row) * (cellHeight + gap)
                paint.color = when {
                    row < allocated -> usedColor
                    row < requiredVisible -> overflowColor
                    else -> emptyColor
                }
                canvas.drawRoundRect(RectF(x, y, x + cellWidth, y + cellHeight), 4f, 4f, paint)
            }
            textPaint.color = if (allocated > 0) Color.rgb(216, 234, 242) else textDimColor
            canvas.drawText(label, x + cellWidth / 2f, height - paddingBottom - 4f * resources.displayMetrics.density, textPaint)
            if (required > 0 || allocated > 0) {
                textPaint.color = if (allocated >= requiredVisible && required <= rows) usedColor else overflowColor
                canvas.drawText(
                    "$allocated/$required",
                    x + cellWidth / 2f,
                    startY + stackHeight / 2f + textPaint.textSize / 3f,
                    textPaint,
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true
        val s = summary ?: return true
        val columnWidth = lastCellWidth + lastColumnGap
        if (columnWidth <= 0f || event.y < lastStartY) return true
        val index = ((event.x - lastStartX) / columnWidth).toInt()
        if (index !in s.groups.indices) return true
        val columnLeft = lastStartX + index * columnWidth
        if (event.x < columnLeft || event.x > columnLeft + lastCellWidth) return true
        val stackHeight = rows * lastCellHeight + (rows - 1) * lastGap
        val relativeFromBottom = (lastStartY + stackHeight - event.y).coerceIn(0f, stackHeight)
        val selected = kotlin.math.ceil(relativeFromBottom / (lastCellHeight + lastGap)).toInt().coerceIn(0, rows)
        val required = s.groups.getOrNull(index)?.second ?: 0
        val current = allocations.getOrNull(index).orZero()
        val otherAllocated = allocations.sum() - current
        val available = (s.totalSegments - otherAllocated).coerceAtLeast(0)
        allocations[index] = selected.coerceIn(0, min(min(required, rows), available))
        invalidate()
        onAllocationChanged?.invoke(allocations.toList())
        return true
    }

    private fun trimAllocationsToTotal(totalSegments: Int) {
        var overflow = allocations.sum() - totalSegments
        var index = allocations.lastIndex
        while (overflow > 0 && index >= 0) {
            val removable = min(allocations[index], overflow)
            allocations[index] -= removable
            overflow -= removable
            index--
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
