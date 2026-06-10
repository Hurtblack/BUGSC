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

    @Test
    fun parsesGiteeReleaseWithoutHtmlUrl() {
        val json = """
            {
              "tag_name": "v1.0.3",
              "name": "v1.0.3",
              "body": "更新说明",
              "assets": [
                {
                  "name": "SCMobiGlas-v1.0.3.apk",
                  "browser_download_url": "https://gitee.com/hurtblack/BUGSC/releases/download/v1.0.3/SCMobiGlas-v1.0.3.apk"
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdateClient.parseGiteeRelease(json)

        assertNotNull(release)
        assertEquals("1.0.3", release?.versionName)
        assertEquals("https://gitee.com/hurtblack/BUGSC/releases/tag/v1.0.3", release?.pageUrl)
        assertEquals(
            "https://gitee.com/hurtblack/BUGSC/releases/download/v1.0.3/SCMobiGlas-v1.0.3.apk",
            release?.apkUrl,
        )
    }

    @Test
    fun fallsBackToNextSourceWhenFirstFails() {
        val sources = listOf(
            AppUpdateClient.UpdateSource("GitHub", "https://gh.example/latest", AppUpdateClient::parseRelease),
            AppUpdateClient.UpdateSource("Backup", "https://backup.example/latest", AppUpdateClient::parseRelease),
        )
        val client = AppUpdateClient(sources = sources) { url ->
            if (url.startsWith("https://gh.example")) throw IllegalStateException("network down")
            """{"tag_name":"v9.9.9","html_url":"https://example.com/r","assets":[]}"""
        }

        val release = client.fetchLatestRelease()

        assertEquals("9.9.9", release?.versionName)
    }

    @Test
    fun throwsWhenAllSourcesFail() {
        val sources = listOf(
            AppUpdateClient.UpdateSource("GitHub", "https://gh.example/latest", AppUpdateClient::parseRelease),
        )
        val client = AppUpdateClient(sources = sources) { _ ->
            throw IllegalStateException("network down")
        }

        val result = runCatching { client.fetchLatestRelease() }

        assertTrue(result.isFailure)
    }
}
