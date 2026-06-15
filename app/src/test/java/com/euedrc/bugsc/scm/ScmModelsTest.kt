package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmModelsTest {

    @Test
    fun parsesCommonResultEnvelope() {
        val r = ScmResponse.parse("""{"code":0,"data":{"x":1},"msg":""}""")
        assertEquals(0, r.code)
        assertTrue(r.isSuccess)
        assertEquals(1, r.data!!.getInt("x"))
    }

    @Test
    fun parsesErrorEnvelopeWithMsg() {
        val r = ScmResponse.parse("""{"code":1004003001,"data":null,"msg":"密码错误"}""")
        assertEquals(1004003001, r.code)
        assertTrue(!r.isSuccess)
        assertEquals("密码错误", r.msg)
        assertNull(r.data)
    }

    @Test
    fun loginSuccessYieldsTokens() {
        val json = """
            {"code":0,"msg":"","data":{
              "userId":555,"accessToken":"at-abc","refreshToken":"rt-xyz","expiresTime":1781091291000
            }}
        """.trimIndent()
        val outcome = ScmLogin.interpret(ScmResponse.parse(json))
        assertTrue(outcome is ScmLoginOutcome.Success)
        val login = (outcome as ScmLoginOutcome.Success).login
        assertEquals(555L, login.userId)
        assertEquals("at-abc", login.accessToken)
        assertEquals("rt-xyz", login.refreshToken)
        assertEquals(1781091291000L, login.expiresTime)
    }

    @Test
    fun loginNewDeviceRequiresEmailCode() {
        val json = """{"code":${ScmLogin.CODE_EMAIL_CODE_REQUIRED},"msg":"请输入邮箱验证码","data":null}"""
        val outcome = ScmLogin.interpret(ScmResponse.parse(json))
        assertTrue(outcome is ScmLoginOutcome.NeedEmailCode)
    }

    @Test
    fun loginErrorMapsToFailureWithMsg() {
        val json = """{"code":1004003001,"msg":"密码错误","data":null}"""
        val outcome = ScmLogin.interpret(ScmResponse.parse(json))
        assertTrue(outcome is ScmLoginOutcome.Failure)
        assertEquals("密码错误", (outcome as ScmLoginOutcome.Failure).msg)
    }

    @Test
    fun parsesUserInfo() {
        val json = """
            {
              "id":555,"nickname":"流浪","avatar":"https://a/x.png","email":"a@b.com",
              "language":0,"verifyKeyStatus":1,"rsiAccurate":1,"sponsorLevel":2,
              "sellOrderCount":7,"buyOrderCount":3
            }
        """.trimIndent()
        val u = AppMemberUserInfoRespVO.parse(org.json.JSONObject(json))
        assertEquals("流浪", u.nickname)
        assertEquals("https://a/x.png", u.avatar)
        assertEquals("a@b.com", u.email)
        assertEquals(2, u.sponsorLevel)
        assertEquals(7, u.sellOrderCount)
        assertEquals(3, u.buyOrderCount)
        assertEquals(1, u.rsiAccurate)
    }

    @Test
    fun userInfoRsiAccurateNullWhenUnverified() {
        val json = """{"id":1,"nickname":"n","rsiAccurate":null}"""
        val u = AppMemberUserInfoRespVO.parse(org.json.JSONObject(json))
        assertNull(u.rsiAccurate)
    }

    @Test
    fun userInfoCacheRoundTripPreservesProfileFields() {
        val json = """
            {
              "id":555,
              "nickname":"流浪",
              "avatar":"https://a/x.png",
              "email":"a@b.com",
              "mark":"常驻奥里森",
              "language":0,
              "verifyKeyStatus":1,
              "rsiAccurate":1,
              "sponsorLevel":2,
              "sellOrderCount":7,
              "buyOrderCount":3,
              "userMobile":"123",
              "createTime":1718000000,
              "organization":{"name":"SCM"},
              "signInStatus":1,
              "achievement":{
                "point":88,
                "orderLimit":12,
                "groups":[{"title":"认证商人"},{"name":"开拓者"}]
              }
            }
        """.trimIndent()
        val original = AppMemberUserInfoRespVO.parse(org.json.JSONObject(json))

        val cached = AppMemberUserInfoRespVO.parseCache(original.toCacheJson())

        assertEquals(original, cached)
    }

    @Test
    fun userInfoCacheRejectsInvalidJson() {
        assertNull(AppMemberUserInfoRespVO.parseCache("{broken"))
    }
}
