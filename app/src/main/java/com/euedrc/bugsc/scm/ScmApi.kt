package com.euedrc.bugsc.scm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ScmHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

data class ScmHttpResponse(
    val code: Int,
    val body: String,
)

/** HTTP 执行抽象，单测注入脚本化实现，Android 走 [UrlConnectionExecutor]。 */
interface ScmHttpExecutor {
    fun execute(request: ScmHttpRequest): ScmHttpResponse
}

/**
 * SCM `app-api` 客户端：统一加 `tenant-id` 与（已登录时）`Authorization: Bearer`，
 * 遇 HTTP 401 自动 `POST /member/auth/refresh-token` 刷新并重试一次；刷新失败清登录态。
 */
class ScmApi(
    private val executor: ScmHttpExecutor,
    private val auth: ScmAuthSession,
    private val baseUrl: String = BASE_URL,
) {
    fun request(method: String, path: String, body: String? = null): ScmHttpResponse {
        val first = executor.execute(build(method, path, body))
        if (!isAuthError(first)) return first

        // 未授权（HTTP 401 或 200 body code==401）→ 刷新一次
        if (!refreshToken()) {
            auth.clear()
            return first
        }
        return executor.execute(build(method, path, body))
    }

    /** SCM 后端 token 过期返回 HTTP 200 + {code:401}，这里统一识别为未授权。 */
    private fun isAuthError(resp: ScmHttpResponse): Boolean {
        if (resp.code == 401) return true
        if (resp.code in 200..299 && resp.body.isNotEmpty()) {
            return runCatching { org.json.JSONObject(resp.body).optInt("code", 0) == 401 }.getOrDefault(false)
        }
        return false
    }

    private fun build(method: String, path: String, body: String?): ScmHttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["tenant-id"] = TENANT_ID
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = "application/json"
        val token = auth.session().accessToken
        if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
        return ScmHttpRequest(method, baseUrl + path, headers, body)
    }

    private fun refreshToken(): Boolean {
        val refresh = auth.session().refreshToken
        if (refresh.isEmpty()) return false
        val headers = mapOf("tenant-id" to TENANT_ID, "Accept" to "application/json")
        val url = "$baseUrl/member/auth/refresh-token?refreshToken=$refresh"
        val resp = executor.execute(ScmHttpRequest("POST", url, headers))
        if (resp.code !in 200..299) return false
        val parsed = ScmResponse.parse(resp.body)
        val data = parsed.data ?: return false
        if (!parsed.isSuccess) return false
        val newAccess = data.optString("accessToken")
        if (newAccess.isEmpty()) return false
        auth.updateTokens(
            accessToken = newAccess,
            refreshToken = data.optString("refreshToken").ifEmpty { refresh },
            expiresTime = data.optLong("expiresTime"),
        )
        return true
    }

    companion object {
        const val BASE_URL = "https://flowcld.xyz/app-api"
        const val TENANT_ID = "1"
    }
}

/** Android 侧真实 HTTP 执行（HttpURLConnection），与项目现有网络栈一致。 */
class UrlConnectionExecutor(
    private val userAgent: String = "SCMobiGlas-Android-App",
    private val timeoutMs: Int = 15_000,
) : ScmHttpExecutor {
    override fun execute(request: ScmHttpRequest): ScmHttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = request.method
            conn.setRequestProperty("User-Agent", userAgent)
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(request.body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            ScmHttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
