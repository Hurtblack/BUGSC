package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptchaCoordTest {

    // Original captcha image is always 310 x 155.

    @Test
    fun uniformScaleNoLetterbox() {
        // view 620x310 == img scaled x2, no letterbox.
        val p = CaptchaCoord.toOriginal(touchX = 280f, touchY = 160f, viewWidth = 620, viewHeight = 310)
        assertEquals(140, p.x)
        assertEquals(80, p.y)
    }

    @Test
    fun verticalLetterboxWhenViewTaller() {
        // view 620x620: scale=min(2,4)=2, rendered 620x310, offsetY=155.
        val p = CaptchaCoord.toOriginal(touchX = 280f, touchY = 315f, viewWidth = 620, viewHeight = 620)
        assertEquals(140, p.x)
        assertEquals(80, p.y)
    }

    @Test
    fun horizontalLetterboxWhenViewWider() {
        // view 930x155: scale=min(3,1)=1, rendered 310x155, offsetX=310.
        val p = CaptchaCoord.toOriginal(touchX = 450f, touchY = 80f, viewWidth = 930, viewHeight = 155)
        assertEquals(140, p.x)
        assertEquals(80, p.y)
    }

    @Test
    fun clampsToImageBounds() {
        val p = CaptchaCoord.toOriginal(touchX = 5000f, touchY = -10f, viewWidth = 620, viewHeight = 310)
        assertEquals(310, p.x)
        assertEquals(0, p.y)
    }
}
