package com.euedrc.bugsc

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class RsiSession(
    val token: String = "",
    val device: String = "",
    val accountAuth: String = "",
    val upgradeContext: String = ""
) {
    val isLoggedIn: Boolean get() = token.isNotEmpty()
}

data class LoginResult(
    val success: Boolean,
    val message: String,
    val session: RsiSession,
    val needCode: Boolean = false,
    val needCaptcha: Boolean = false
)

data class RsiUserProfile(
    val displayName: String = "",
    val handle: String = "",
    val avatarUrl: String = ""
)

data class InventoryRequestState(
    val page: Int,
    val attempt: Int,
    val maxAttempts: Int
)

enum class InventoryErrorType {
    HTTP,
    TIMEOUT,
    NETWORK
}

class InventoryFetchException(
    val type: InventoryErrorType,
    val page: Int? = null,
    val statusCode: Int? = null,
    message: String
) : Exception(message)

class RsiInventoryClient(
    private var session: RsiSession = RsiSession()
) {
    internal data class PaginationInfo(
        val maxLinkedPage: Int? = null
    )

    private val debugLines = mutableListOf<String>()
    private var csrfToken: String = ""

    fun currentSession(): RsiSession = session

    fun debugLog(): String = debugLines.takeLast(30).joinToString("\n")

    fun login(email: String, password: String, captcha: String? = null): LoginResult {
        val body = JSONObject().apply {
            put("username", email)
            put("password", password)
            put("captcha", captcha)
            put("remember", true)
        }
        return handleLoginResponse(postJson("api/launcher/v3/signin", body))
    }

    fun submitCode(code: String): LoginResult {
        val body = JSONObject().apply {
            put("code", code)
            put("device_name", "Refuge")
            put("device_type", "computer")
            put("duration", "year")
        }
        return handleLoginResponse(postJson("api/launcher/v3/signin/multiStep", body))
    }

    fun fetchCaptchaImage(): ByteArray {
        val conn = openConnection("api/launcher/v3/signin/captcha")
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write("{}") }
            readBytesResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun fetchInventory(
        maxPages: Int = 30,
        onPageFetched: ((page: Int, pageItems: List<InventoryItem>, allItems: List<InventoryItem>) -> Unit)? = null,
        onRequestStateChanged: ((InventoryRequestState) -> Unit)? = null
    ): List<InventoryItem> {
        if (!session.isLoggedIn) throw IllegalStateException("请先登录")
        debugLines.clear()
        debug("开始拉取库存：token=${session.token.masked()} device=${session.device.masked()} auth=${session.accountAuth.masked()}")
        val items = mutableListOf<InventoryItem>()
        val seenIds = mutableSetOf<String>()
        for (page in 1..maxPages) {
            val html = try {
                getTextWithRetry("account/pledges?page=$page", page, onRequestStateChanged)
            } catch (e: InventoryFetchException) {
                debug("第 $page 页请求失败：${e.message}")
                throw e
            }
            debugPage(page, html)
            val paginationInfo = extractPaginationInfo(html)
            val pageItems = try {
                InventoryParser.parseHangarItems(html, page)
            } catch (e: InventoryParseException) {
                debug("第 $page 页解析失败：${e.message}")
                debug("第 $page 页片段：${html.debugSnippet()}")
                throw e
            }
            debug("第 $page 页解析到 ${pageItems.size} 项")
            if (pageItems.isEmpty()) break
            // RSI 对超出范围的页码会重定向回第 1 页，导致重复返回相同内容。
            // 用 seenIds 检测：若本页无任何新条目，说明已到末页，提前结束。
            val newItems = pageItems.filter { seenIds.add(it.id) }
            if (newItems.isEmpty()) {
                debug("第 $page 页全部重复（RSI 重定向），停止拉取")
                break
            }
            items += newItems
            onPageFetched?.invoke(page, newItems, items.toList())
            val maxLinkedPage = paginationInfo.maxLinkedPage
            if (maxLinkedPage != null && page >= maxLinkedPage) {
                debug("第 $page 页已达到分页上限 $maxLinkedPage，停止拉取")
                break
            }
        }
        debug("库存拉取完成：共 ${items.size} 项")
        return items
    }

    fun fetchUserProfile(): RsiUserProfile {
        if (!session.isLoggedIn) throw IllegalStateException("请先登录")
        refreshCsrfToken()
        val response = postJson(
            "graphql",
            JSONObject().apply {
                put("query", ACCOUNT_QUERY)
                put("variables", JSONObject())
            }
        )
        val account = response.optJSONObject("data")
            ?.optJSONObject("account")
            ?: throw Exception("未能读取 RSI 账号信息")
        val displayName = account.optString("displayname")
        val handle = account.optString("nickname")
        val graphAvatar = account.optString("avatar").normalizeRsiUrl()
        val citizenAvatar = if (graphAvatar.isBlank() && handle.isNotBlank()) {
            fetchCitizenAvatar(handle)
        } else {
            ""
        }
        return RsiUserProfile(
            displayName = displayName,
            handle = handle,
            avatarUrl = graphAvatar.ifBlank { citizenAvatar }
        )
    }

    private fun handleLoginResponse(response: JSONObject): LoginResult {
        val code = response.optString("code")
        val message = response.optString("msg", code.ifEmpty { "登录失败" })
        val data = response.optJSONObject("data")
        data?.optString("device_id")?.takeIf { it.isNotEmpty() }?.let {
            session = session.copy(device = it)
        }
        data?.optString("session_id")?.takeIf { it.isNotEmpty() }?.let {
            session = session.copy(token = it)
        }

        return when {
            response.optInt("success") == 1 || code == "ErrNoGamePackage" -> {
                LoginResult(true, message.ifEmpty { "登录成功" }, session)
            }
            code == "ErrMultiStepRequired" -> {
                LoginResult(false, "请输入邮箱二步验证码", session, needCode = true)
            }
            code == "ErrCaptchaRequiredLauncher" -> {
                LoginResult(false, "RSI 要求验证码，当前页面暂不支持验证码登录", session, needCaptcha = true)
            }
            code == "ErrWrongPassword_email" -> {
                LoginResult(false, "邮箱或密码错误", session)
            }
            code == "ErrMaxThrottleLogin" -> {
                LoginResult(false, "登录过于频繁，请稍后再试", session)
            }
            else -> LoginResult(false, message, session)
        }
    }

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val conn = openConnection(endpoint)
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val text = readResponse(conn)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(endpoint: String): String {
        val conn = openConnection(endpoint)
        return try {
            conn.requestMethod = "GET"
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(endpoint: String): HttpURLConnection {
        val conn = URL("$BASE_URL$endpoint").openConnection() as HttpURLConnection
        conn.connectTimeout = REQUEST_TIMEOUT_MS.toInt()
        conn.readTimeout = REQUEST_TIMEOUT_MS.toInt()
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json, text/html, */*")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", BASE_URL)
        conn.setRequestProperty("Connection", "close")
        conn.setRequestProperty("Cookie", cookieHeader())
        if (session.token.isNotEmpty()) {
            conn.setRequestProperty("x-rsi-token", session.token)
        }
        if (session.device.isNotEmpty()) {
            conn.setRequestProperty("x-rsi-device", session.device)
        }
        if (csrfToken.isNotEmpty()) {
            conn.setRequestProperty("x-csrf-token", csrfToken)
        }
        return conn
    }

    private fun refreshCsrfToken() {
        val html = getText("")
        csrfToken = Regex("""csrf-token"\s+content="([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun fetchCitizenAvatar(handle: String): String {
        return runCatching {
            val html = getText("citizens/$handle")
            Regex("""<div[^>]+class="[^"]*\bleft-col\b[^"]*"[\s\S]*?<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.normalizeRsiUrl()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        captureSetCookies(conn)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
        debug(
            "${conn.requestMethod} ${conn.url} -> $code, " +
                "contentType=${conn.contentType.orEmpty()}, bytes=${body.length}"
        )
        if (code !in 200..299) {
            val message = runCatching {
                val json = JSONObject(body)
                json.optString("msg")
                    .ifEmpty { json.optString("message") }
                    .ifEmpty { json.optString("error") }
            }.getOrNull().orEmpty()
            throw InventoryFetchException(
                type = InventoryErrorType.HTTP,
                statusCode = code,
                message = message.ifEmpty { "请求失败($code)" }
            )
        }
        return body
    }

    private fun getTextWithRetry(
        endpoint: String,
        page: Int,
        onRequestStateChanged: ((InventoryRequestState) -> Unit)? = null
    ): String {
        var lastError: Exception? = null
        repeat(PLEDGE_FETCH_RETRY) { attempt ->
            val attemptIndex = attempt + 1
            try {
                if (attemptIndex > 1) {
                    debug("第 $page 页重试中：第 $attemptIndex/$PLEDGE_FETCH_RETRY 次")
                }
                onRequestStateChanged?.invoke(
                    InventoryRequestState(
                        page = page,
                        attempt = attemptIndex,
                        maxAttempts = PLEDGE_FETCH_RETRY
                    )
                )
                return getText(endpoint)
            } catch (e: SocketTimeoutException) {
                lastError = InventoryFetchException(
                    type = InventoryErrorType.TIMEOUT,
                    page = page,
                    message = e.message ?: "请求超时"
                )
                debug("第 $page 页请求超时：${e.message.orEmpty().ifBlank { "Socket timeout" }}")
            } catch (e: IOException) {
                lastError = InventoryFetchException(
                    type = InventoryErrorType.NETWORK,
                    page = page,
                    message = e.message ?: e.javaClass.simpleName
                )
                debug("第 $page 页网络异常：${e.message.orEmpty().ifBlank { e.javaClass.simpleName }}")
            } catch (e: InventoryFetchException) {
                lastError = e.copyWithPage(page)
                debug("第 $page 页请求异常：${e.message.orEmpty()}")
            }
            if (attemptIndex < PLEDGE_FETCH_RETRY) {
                Thread.sleep(PLEDGE_RETRY_DELAY_MS)
            }
        }
        throw (lastError ?: InventoryFetchException(
            type = InventoryErrorType.NETWORK,
            page = page,
            message = "第 $page 页请求失败"
        ))
    }

    private fun InventoryFetchException.copyWithPage(fallbackPage: Int): InventoryFetchException {
        return if (page != null) this else InventoryFetchException(
            type = type,
            page = fallbackPage,
            statusCode = statusCode,
            message = message ?: "请求失败"
        )
    }

    private fun readBytesResponse(conn: HttpURLConnection): ByteArray {
        val code = conn.responseCode
        captureSetCookies(conn)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: ByteArray(0)
        if (code !in 200..299) {
            throw Exception("验证码加载失败($code)")
        }
        return bytes
    }

    private fun captureSetCookies(conn: HttpURLConnection) {
        val setCookies = conn.headerFields
            .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values
            .flatten()
        captureCookies(setCookies)
    }

    private fun captureCookies(cookies: List<String>) {
        cookies.forEach { cookie ->
            val pair = cookie.substringBefore(";")
            val name = pair.substringBefore("=")
            val value = pair.substringAfter("=", "")
            when (name) {
                "Rsi-Token" -> session = session.copy(token = value)
                "_rsi_device" -> session = session.copy(device = value)
                "Rsi-Account-Auth" -> session = session.copy(accountAuth = value)
                "Rsi-ShipUpgrades-Context" -> session = session.copy(upgradeContext = value)
            }
        }
    }

    private fun cookieHeader(): String {
        return RsiCookieStore.cookieHeader(session)
    }

    private fun debugPage(page: Int, html: String) {
        val title = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        debug(
            "第 $page 页检查：title=${title.ifEmpty { "-" }}, " +
                "hasListItems=${html.contains("list-items")}, " +
                "hasPledgeRow=${html.contains("js-pledge-id")}, " +
                "hasSignin=${html.contains("signin", ignoreCase = true) || html.contains("login", ignoreCase = true)}"
        )
    }

    private fun debug(message: String) {
        debugLines += message
        Log.d(TAG, message)
    }

    internal fun extractPaginationInfo(html: String): PaginationInfo {
        val linkedPages = Regex("""account/pledges\?page=(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toList()
        return PaginationInfo(
            maxLinkedPage = linkedPages.maxOrNull()
        )
    }

    private fun String.masked(): String {
        if (isBlank()) return "-"
        return if (length <= 8) "***" else "${take(4)}...${takeLast(4)}"
    }

    private fun String.debugSnippet(): String {
        return replace(Regex("""\s+"""), " ")
            .take(700)
            .trim()
    }

    private fun String.normalizeRsiUrl(): String {
        val value = trim()
        return when {
            value.isBlank() -> ""
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://robertsspaceindustries.com$value"
            else -> value
        }
    }

    companion object {
        private const val TAG = "RsiInventory"
        private const val BASE_URL = "https://robertsspaceindustries.com/"
        private const val PLEDGE_FETCH_RETRY = 3
        private const val PLEDGE_RETRY_DELAY_MS = 1500L
        const val REQUEST_TIMEOUT_MS = 20_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val ACCOUNT_QUERY = """query account {
  account {
    isAnonymous
    ... on RsiAuthenticatedAccount {
      avatar
      displayname
      nickname
      username
      profileUrl
      __typename
    }
    __typename
  }
}"""
    }
}
