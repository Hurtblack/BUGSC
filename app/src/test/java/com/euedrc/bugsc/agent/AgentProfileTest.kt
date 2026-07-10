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
        assertTrue(profile.persona.any { it.contains("随行") || it.contains("玩家搭档") })
        assertTrue(profile.persona.any { it.contains("自然") })
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

    @Test
    fun chatGreetingUsesShortIntroAndCapabilityHint() {
        val profile = AgentProfileProvider.defaultProfile()

        val greeting = AgentProfileProvider.chatGreeting(profile)

        assertTrue(greeting.startsWith("我是 SCMobiGlas App 内置的 AI 助手"))
        assertFalse(greeting.contains("负责把 App 本地数据"))
        assertTrue(greeting.contains("需要查船、矿物、蓝图、任务、市场行情或机库状态，直接告诉我目标就行"))
    }
}
