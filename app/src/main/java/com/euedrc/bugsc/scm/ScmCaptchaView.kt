package com.euedrc.bugsc.scm

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AJ-Captcha 文字点选验证码控件：显示原图 + 提示词，收集点击坐标换算回 310×155，
 * 集齐 wordList 长度后生成 captchaVerification 通过 [onVerified] 回调。
 * 登录 / 注册 / 发邮件三处复用；点错或失败由外部调 [showLoading] 后重新 [setChallenge]。
 */
class ScmCaptchaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** 集齐点击后回调最终的 captchaVerification。 */
    var onVerified: ((String) -> Unit)? = null
    /** 用户点击「换一张」时回调，由外部重新取图。 */
    var onRequestRefresh: (() -> Unit)? = null

    private val prompt: TextView
    private val image: CaptchaImageView
    private val hint: TextView

    private var challenge: CaptchaChallenge? = null
    private val points = mutableListOf<CaptchaCoord.Point>()
    private var wordsPrompt: String = ""

    init {
        orientation = VERTICAL
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)

        prompt = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#d8eaf2"))
            typeface = Typeface.DEFAULT_BOLD
            text = "加载验证码中…"
        }
        addView(prompt)

        image = CaptchaImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(160)).apply {
                topMargin = dp(6)
            }
            setBackgroundColor(Color.parseColor("#0a1420"))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            onTap = { vx, vy -> handleTap(vx, vy) }
        }
        addView(image)

        hint = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            textSize = 12f
            setTextColor(Color.parseColor("#21d4ff"))
            gravity = Gravity.END
            text = "看不清？换一张"
            setOnClickListener {
                showLoading()
                onRequestRefresh?.invoke()
            }
        }
        addView(hint)
    }

    fun showLoading() {
        challenge = null
        points.clear()
        image.clearMarks()
        image.setImageDrawable(null)
        prompt.text = "加载验证码中…"
    }

    fun showError(message: String) {
        prompt.text = message
    }

    fun setChallenge(challenge: CaptchaChallenge) {
        this.challenge = challenge
        points.clear()
        image.clearMarks()
        wordsPrompt = if (challenge.wordList.isEmpty()) {
            "请点击验证码"
        } else {
            "请依次点击：" + challenge.wordList.joinToString("、")
        }
        prompt.text = wordsPrompt
        val raw = challenge.originalImageBase64.substringAfter("base64,", challenge.originalImageBase64)
        val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) image.setImageBitmap(bmp) else prompt.text = "验证码图片解析失败"
    }

    private fun handleTap(viewX: Float, viewY: Float) {
        val ch = challenge ?: return
        if (points.size >= ch.wordList.size) return
        // 触摸点在原图坐标系
        val p = CaptchaCoord.toOriginal(viewX, viewY, image.width, image.height)
        points.add(p)
        image.addMark(viewX, viewY, points.size)

        // 保留提示词，进度附在后面；只有换验证码（setChallenge）时才重置。
        prompt.text = "$wordsPrompt（已点 ${points.size}/${ch.wordList.size}）"
        if (points.size == ch.wordList.size) {
            verifyWithServer(ch)
        }
    }

    /** AJ-Captcha 标准两步：先 /check 标记通过，成功才把 captchaVerification 交给业务。 */
    private fun verifyWithServer(ch: CaptchaChallenge) {
        val pointJson = CaptchaChallenge.pointJson(points)
        val encrypted = ScmCaptchaCrypto.encryptPointJson(pointJson, ch.secretKey)
        val verification = ScmCaptchaCrypto.buildVerification(ch.token, pointJson, ch.secretKey)
        val owner = findViewTreeLifecycleOwner()
        if (owner == null) {
            // 兜底：拿不到 lifecycle 就直接给（旧行为）
            onVerified?.invoke(verification)
            return
        }
        prompt.text = "校验中…"
        owner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ScmClient.checkCaptcha(ch.token, encrypted) }.getOrDefault(false)
            }
            if (ok) {
                prompt.text = "验证通过"
                onVerified?.invoke(verification)
            } else {
                prompt.text = "点选有误，正在换一张…"
                onRequestRefresh?.invoke()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 内部图片视图：在点击处画序号标记。 */
    private class CaptchaImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : AppCompatImageView(context, attrs) {

        var onTap: ((Float, Float) -> Unit)? = null
        private val marks = mutableListOf<Pair<Float, Float>>()

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#cc21d4ff")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0a1420")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        fun addMark(x: Float, y: Float, index: Int) {
            marks.add(x to y)
            invalidate()
        }

        fun clearMarks() {
            marks.clear()
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                performClick()
                onTap?.invoke(event.x, event.y)
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = 22f
            textPaint.textSize = 26f
            marks.forEachIndexed { i, (x, y) ->
                canvas.drawCircle(x, y, radius, circlePaint)
                canvas.drawText((i + 1).toString(), x, y + 9f, textPaint)
            }
        }
    }
}
