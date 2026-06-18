package com.euedrc.bugsc.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class MobiGlasBottomBarTest {

    @Test
    fun tabWidthShrinksToFitCompactPhoneWidth() {
        val width = calculateMobiGlasTabWidth(maxWidth = 320.dp, itemCount = 4)

        assertEquals(72f, width.value, 0.01f)
    }

    @Test
    fun tabWidthKeepsDesignMaxOnNormalPhoneWidth() {
        val width = calculateMobiGlasTabWidth(maxWidth = 360.dp, itemCount = 4)

        assertEquals(78f, width.value, 0.01f)
    }

    @Test
    fun tabWidthKeepsDesignMaxOnWideWidth() {
        val width = calculateMobiGlasTabWidth(maxWidth = 430.dp, itemCount = 4)

        assertEquals(78f, width.value, 0.01f)
    }
}
