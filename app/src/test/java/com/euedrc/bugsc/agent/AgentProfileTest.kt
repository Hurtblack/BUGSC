package com.euedrc.bugsc.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfileTest {

    @Test
    fun defaultProfileDefinesIdentityWithoutBindingRuntimeToScm() {
        val profile = AgentProfileProvider.defaultProfile()

        assertTrue(profile.displayName.isNotBlank())
        assertTrue(profile.codename.isNotBlank())
        assertTrue(profile.roleDescription.contains("App 内置"))
        assertTrue(profile.roleDescription.contains("不是 SCM 官方机器人"))
        assertTrue(profile.roleDescription.contains("不是 SCM 后端用户"))
        assertTrue(profile.capabilities.any { it.contains("飞船") })
        assertTrue(profile.capabilities.any { it.contains("矿物") })
        assertTrue(profile.capabilities.any { it.contains("蓝图") })
        assertTrue(profile.capabilities.any { it.contains("任务") })
        assertTrue(profile.dataSources.any { it.contains("本地数据") })
        assertTrue(profile.dataSources.any { it.contains("DeepSeek") })
        assertTrue(profile.limitations.any { it.contains("数据可能") })
    }

    @Test
    fun privacyNotesExplainLocalKeyAndDeepSeekContext() {
        val profile = AgentProfileProvider.defaultProfile()
        val privacyText = profile.privacyNotes.joinToString("\n")

        assertTrue(privacyText.contains("API Key"))
        assertTrue(privacyText.contains("本机"))
        assertTrue(privacyText.contains("不会把 SCM token"))
        assertTrue(privacyText.contains("Cookie"))
        assertTrue(privacyText.contains("DeepSeek"))
        assertFalse(privacyText.contains("上传到 SCM"))
    }
}
