package com.euedrc.bugsc.agent

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.core.graphics.toColorInt

object AgentMarkdownFormatter {

    fun bind(textView: TextView, text: String, mine: Boolean) {
        textView.text = format(text, mine)
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.linksClickable = true
    }

    fun format(text: String, mine: Boolean): Spanned {
        val out = SpannableStringBuilder()
        var inCodeBlock = false
        val lines = text.replace("\r\n", "\n").split('\n')
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                return@forEachIndexed
            }
            if (index > 0 && out.isNotEmpty() && !line.trimStart().startsWith("```")) {
                out.append('\n')
            }
            if (inCodeBlock) {
                appendCodeLine(out, line)
            } else {
                appendMarkdownLine(out, line, mine)
            }
        }
        return out
    }

    private fun appendMarkdownLine(out: SpannableStringBuilder, line: String, mine: Boolean) {
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val start = out.length
            appendInline(out, heading.groupValues[2], mine)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(if (level == 1) 1.22f else 1.12f), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            val start = out.length
            appendInline(out, bullet.groupValues[1], mine)
            out.setSpan(BulletSpan(12), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard(28, 28), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val numbered = NUMBERED.matchEntire(line)
        if (numbered != null) {
            val start = out.length
            out.append(numbered.groupValues[1]).append(". ")
            appendInline(out, numbered.groupValues[2], mine)
            out.setSpan(LeadingMarginSpan.Standard(28, 28), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val quote = QUOTE.matchEntire(line)
        if (quote != null) {
            val start = out.length
            out.append("│ ")
            appendInline(out, quote.groupValues[1], mine)
            out.setSpan(ForegroundColorSpan(if (mine) OUTGOING_QUOTE else INCOMING_QUOTE), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        appendInline(out, line, mine)
    }

    private fun appendInline(out: SpannableStringBuilder, text: String, mine: Boolean) {
        var cursor = 0
        while (cursor < text.length) {
            val next = nextInlineToken(text, cursor)
            if (next == null) {
                out.append(text.substring(cursor))
                return
            }
            if (next.index > cursor) {
                out.append(text.substring(cursor, next.index))
            }
            val close = text.indexOf(next.marker, next.index + next.marker.length)
            if (close < 0) {
                out.append(text.substring(next.index))
                return
            }
            val content = text.substring(next.index + next.marker.length, close)
            val start = out.length
            out.append(content)
            when (next.marker) {
                "**", "__" -> out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                "`" -> applyInlineCode(out, start, out.length, mine)
            }
            cursor = close + next.marker.length
        }
    }

    private fun nextInlineToken(text: String, start: Int): InlineToken? =
        listOf("**", "__", "`")
            .mapNotNull { marker ->
                val index = text.indexOf(marker, start)
                if (index >= 0) InlineToken(index, marker) else null
            }
            .minByOrNull { it.index }

    private fun applyInlineCode(out: SpannableStringBuilder, start: Int, end: Int, mine: Boolean) {
        out.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(if (mine) OUTGOING_CODE_BG else INCOMING_CODE_BG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendCodeLine(out: SpannableStringBuilder, line: String) {
        val start = out.length
        out.append(line)
        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(INCOMING_CODE_BG), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private data class InlineToken(val index: Int, val marker: String)

    private val HEADING = Regex("""^(#{1,3})\s+(.+)$""")
    private val BULLET = Regex("""^\s*[-*]\s+(.+)$""")
    private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.+)$""")
    private val QUOTE = Regex("""^\s*>\s+(.+)$""")
    private val INCOMING_CODE_BG = "#23333b".toColorInt()
    private val OUTGOING_CODE_BG = "#b8e5f4".toColorInt()
    private val INCOMING_QUOTE = "#8fb1bf".toColorInt()
    private val OUTGOING_QUOTE = "#315465".toColorInt()
}
