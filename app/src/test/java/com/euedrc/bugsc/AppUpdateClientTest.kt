package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateClientTest {

    @Test
    fun parsesLatestReleaseAndSelectsApkAsset() {
        val json = """
            {
              "tag_name": "v1.0.2",
              "name": "1.0.2",
              "html_url": "https://github.com/Hurtblack/BUGSC/releases/tag/v1.0.2",
              "body": "bug fixes",
              "assets": [
                {
                  "name": "bug公民-release-v1.0.2.apk",
                  "browser_download_url": "https://github.com/Hurtblack/BUGSC/releases/download/v1.0.2/app.apk"
                },
                {
                  "name": "mapping.txt",
                  "browser_download_url": "https://example.com/mapping.txt"
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdateClient.parseRelease(json)

        assertNotNull(release)
        assertEquals("1.0.2", release?.versionName)
        assertEquals("https://github.com/Hurtblack/BUGSC/releases/download/v1.0.2/app.apk", release?.apkUrl)
    }

    @Test
    fun returnsNullWhenReleaseHasNoVersionTag() {
        val json = """
            {
              "tag_name": "",
              "html_url": "https://github.com/Hurtblack/BUGSC/releases/latest",
              "assets": []
            }
        """.trimIndent()

        val release = AppUpdateClient.parseRelease(json)

        assertNull(release)
    }

    @Test
    fun detectsWhenRemoteVersionIsNewer() {
        assertTrue(AppUpdateClient.isNewerVersion(current = "1.0.1", remote = "1.0.2"))
        assertTrue(AppUpdateClient.isNewerVersion(current = "1.0.9", remote = "1.0.10"))
        assertFalse(AppUpdateClient.isNewerVersion(current = "1.0.2", remote = "1.0.2"))
        assertFalse(AppUpdateClient.isNewerVersion(current = "1.0.3", remote = "1.0.2"))
    }
}
