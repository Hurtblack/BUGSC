package com.euedrc.bugsc.scm

/**
 * 把验证码 ImageView 上的触摸坐标换算回原图 310×155 坐标系。
 *
 * 假设 ImageView 用 fitCenter（等比缩放 + 居中留边），与 AJ-Captcha 文字点选取图一致。
 */
object CaptchaCoord {

    const val IMAGE_WIDTH = 310
    const val IMAGE_HEIGHT = 155

    data class Point(val x: Int, val y: Int)

    fun toOriginal(
        touchX: Float,
        touchY: Float,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int = IMAGE_WIDTH,
        imageHeight: Int = IMAGE_HEIGHT,
    ): Point {
        val scale = minOf(
            viewWidth.toFloat() / imageWidth,
            viewHeight.toFloat() / imageHeight,
        )
        val renderedW = imageWidth * scale
        val renderedH = imageHeight * scale
        val offsetX = (viewWidth - renderedW) / 2f
        val offsetY = (viewHeight - renderedH) / 2f

        val origX = ((touchX - offsetX) / scale).toInt()
        val origY = ((touchY - offsetY) / scale).toInt()
        return Point(
            x = origX.coerceIn(0, imageWidth),
            y = origY.coerceIn(0, imageHeight),
        )
    }
}
