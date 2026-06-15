package com.euedrc.bugsc.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ChatProfileTest {

    @Test
    fun parsesPublicProfileWithoutMaskingNickname() {
        val profile = ChatParser.parsePublicProfile(
            JSONObject(
                """
                {
                  "code": 0,
                  "data": {
                    "id": 42,
                    "nickname": "space_miner",
                    "avatar": "https://cdn.example/avatar.jpg",
                    "signInStatus": 1,
                    "tradeTime": [1,1,1,1,1,1,1],
                    "tradeStartTime": "09:00",
                    "tradeEndTime": "16:00",
                    "userMobile": "2145844403@qq.com"
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals("space_miner", profile.nickname)
        assertEquals("https://cdn.example/avatar.jpg", profile.avatar)
        assertEquals(listOf(true, true, true, true, true, true, true), profile.tradeDays)
        assertEquals("2145844403@qq.com", profile.contact)
    }

    @Test
    fun formatsTradeScheduleAndHiddenContact() {
        assertEquals(
            "交易时间：09:00 - 16:00（每天）UTC+8",
            ChatProfileFormatter.tradeSchedule(
                days = List(7) { true },
                start = "09:00",
                end = "16:00",
            )
        )
        assertEquals(
            "交易时间：13:00 - 04:00（周一、周三、周日）UTC+8",
            ChatProfileFormatter.tradeSchedule(
                days = listOf(true, false, true, false, false, false, true),
                start = "13:00",
                end = "04:00",
            )
        )
        assertEquals("联系方式：未公开", ChatProfileFormatter.contact(""))
        assertEquals("联系方式：QQ 123456", ChatProfileFormatter.contact("QQ 123456"))
    }

    @Test
    fun formatsMessageTimeWithoutDateWhenPossible() {
        assertEquals("14:42", ChatProfileFormatter.messageTime("2026-06-15 14:42:59"))
        assertEquals("刚刚", ChatProfileFormatter.messageTime(""))
    }

    @Test
    fun acceptsStringTradeDaysAndPrefersUnmaskedConversationName() {
        val profile = ChatParser.parsePublicProfile(
            JSONObject(
                """
                {
                  "code": 0,
                  "data": {
                    "id": 42,
                    "nickname": "M******T",
                    "tradeTime": "[1,1,1,1,1,1,1]",
                    "tradeStartTime": "13:00",
                    "tradeEndTime": "04:00"
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(List(7) { true }, profile.tradeDays)
        assertEquals(
            "MclocePT",
            ChatProfileFormatter.displayName("MclocePT", profile.nickname, "M******T"),
        )
    }

    @Test
    fun formatsEpochMillisMessageTime() {
        val formatted = ChatProfileFormatter.messageTime(
            "1781512919000",
            TimeZone.getTimeZone("Asia/Shanghai"),
        )
        assertTrue(formatted.matches(Regex("""\d{2}:\d{2}""")))
    }
}
