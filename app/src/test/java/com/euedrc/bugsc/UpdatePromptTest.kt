package com.euedrc.bugsc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptTest {

    @Test
    fun promptsWhenRemoteNewerAndNotIgnored() {
        assertTrue(UpdatePrompt.shouldPrompt(current = "1.0.2", remote = "1.0.3", ignored = null))
    }

    @Test
    fun skipsWhenRemoteVersionIgnored() {
        assertFalse(UpdatePrompt.shouldPrompt(current = "1.0.2", remote = "1.0.3", ignored = "1.0.3"))
    }

    @Test
    fun skipsWhenRemoteNotNewer() {
        assertFalse(UpdatePrompt.shouldPrompt(current = "1.0.3", remote = "1.0.3", ignored = null))
        assertFalse(UpdatePrompt.shouldPrompt(current = "1.0.4", remote = "1.0.3", ignored = null))
    }

    @Test
    fun promptsWhenIgnoredVersionIsOlderThanRemote() {
        assertTrue(UpdatePrompt.shouldPrompt(current = "1.0.2", remote = "1.0.4", ignored = "1.0.3"))
    }
}
