package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmApiTest {

    private class FakeKv : ScmKvStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 按 path 关键字给出脚本化响应；记录看到的请求以便断言鉴权头。 */
    private class ScriptedExecutor(
        val responder: (ScmHttpRequest, Int) -> ScmHttpResponse,
    ) : ScmHttpExecutor {
        val seen = mutableListOf<ScmHttpRequest>()
        override fun execute(request: ScmHttpRequest): ScmHttpResponse {
            seen.add(request)
            return responder(request, seen.size)
        }
    }

    private fun loggedInAuth(): ScmAuthSession =
        ScmAuthSession(FakeKv()).apply {
            save(AppAuthLoginRespVO(userId = 1, accessToken = "old-at", refreshToken = "rt", expiresTime = 1))
        }

    @Test
    fun on401RefreshesTokenAndRetriesWithNewBearer() {
        val auth = loggedInAuth()
        val exec = ScriptedExecutor { req, _ ->
            when {
                req.url.contains("/member/auth/refresh-token") ->
                    ScmHttpResponse(200, """{"code":0,"msg":"","data":{"accessToken":"new-at","refreshToken":"new-rt","expiresTime":42}}""")
                req.headers["Authorization"] == "Bearer old-at" -> ScmHttpResponse(401, "")
                else -> ScmHttpResponse(200, """{"code":0,"data":{"ok":1},"msg":""}""")
            }
        }
        val api = ScmApi(exec, auth)

        val resp = api.request("GET", "/member/user/get")

        assertEquals(200, resp.code)
        assertEquals("new-at", auth.session().accessToken)
        assertEquals("new-rt", auth.session().refreshToken)
        // 最后一次业务请求带的是刷新后的 token
        val last = exec.seen.last { it.url.contains("/member/user/get") }
        assertEquals("Bearer new-at", last.headers["Authorization"])
        // tenant 头始终存在
        assertEquals("1", last.headers["tenant-id"])
    }

    @Test
    fun businessCode401InBodyTriggersRefreshAndRetry() {
        // SCM 后端 token 过期返回 HTTP 200 + {code:401,...}，应等同 401 走刷新重试。
        val auth = loggedInAuth()
        val exec = ScriptedExecutor { req, _ ->
            when {
                req.url.contains("/member/auth/refresh-token") ->
                    ScmHttpResponse(200, """{"code":0,"msg":"","data":{"accessToken":"new-at","refreshToken":"new-rt","expiresTime":42}}""")
                req.headers["Authorization"] == "Bearer old-at" ->
                    ScmHttpResponse(200, """{"code":401,"data":null,"msg":"账号未登录"}""")
                else -> ScmHttpResponse(200, """{"code":0,"data":{"ok":1},"msg":""}""")
            }
        }
        val resp = ScmApi(exec, auth).request("GET", "/member/user/get")

        assertTrue(resp.body.contains("\"ok\":1"))
        assertEquals("new-at", auth.session().accessToken)
    }

    @Test
    fun businessCode401RefreshFailureClearsSession() {
        val auth = loggedInAuth()
        val exec = ScriptedExecutor { req, _ ->
            if (req.url.contains("/member/auth/refresh-token")) ScmHttpResponse(200, """{"code":401,"data":null,"msg":"refresh 过期"}""")
            else ScmHttpResponse(200, """{"code":401,"data":null,"msg":"账号未登录"}""")
        }
        ScmApi(exec, auth).request("GET", "/member/user/get")
        assertFalse(auth.isLoggedIn)
    }

    @Test
    fun refreshFailureClearsSession() {
        val auth = loggedInAuth()
        val exec = ScriptedExecutor { req, _ ->
            if (req.url.contains("/member/auth/refresh-token")) ScmHttpResponse(401, "")
            else ScmHttpResponse(401, "")
        }
        val api = ScmApi(exec, auth)

        val resp = api.request("GET", "/member/user/get")

        assertEquals(401, resp.code)
        assertFalse(auth.isLoggedIn)
    }

    @Test
    fun onlyRetriesOnce() {
        val auth = loggedInAuth()
        val exec = ScriptedExecutor { req, _ ->
            if (req.url.contains("/member/auth/refresh-token"))
                ScmHttpResponse(200, """{"code":0,"data":{"accessToken":"new-at","refreshToken":"new-rt","expiresTime":1},"msg":""}""")
            else ScmHttpResponse(401, "") // 业务接口始终 401
        }
        val api = ScmApi(exec, auth)

        val resp = api.request("GET", "/member/user/get")

        assertEquals(401, resp.code)
        // 业务请求最多两次（原始 + 刷新后重试一次），不无限重试
        assertEquals(2, exec.seen.count { it.url.contains("/member/user/get") })
        assertTrue(exec.seen.any { it.url.contains("/member/auth/refresh-token") })
    }
}
