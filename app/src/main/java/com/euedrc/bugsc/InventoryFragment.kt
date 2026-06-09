package com.euedrc.bugsc

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InventoryFragment : Fragment() {

    private val prefs by lazy { requireContext().getSharedPreferences("inventory", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var client = RsiInventoryClient()
    private var pendingSecondStep = false
    private var pendingCaptcha = false

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etCaptcha: EditText
    private lateinit var etCode: EditText
    private lateinit var tvUsernameLabel: TextView
    private lateinit var tvPasswordLabel: TextView
    private lateinit var tvCaptchaLabel: TextView
    private lateinit var tvCodeLabel: TextView
    private lateinit var ivCaptcha: ImageView
    private lateinit var containerAccountProfile: LinearLayout
    private lateinit var ivProfileAvatar: ImageView
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileHandle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvDebug: TextView
    private lateinit var tvError: TextView
    private lateinit var tvItemCount: TextView
    private lateinit var tvEmptyInventory: TextView
    private lateinit var containerItems: LinearLayout
    private lateinit var containerPagination: LinearLayout
    private lateinit var tvPageInfo: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnLogout: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private var requestCountdownJob: kotlinx.coroutines.Job? = null
    private var inventoryItems: List<InventoryItem> = emptyList()
    private var currentDisplayPage = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_inventory, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        etCaptcha = view.findViewById(R.id.et_captcha)
        etCode = view.findViewById(R.id.et_code)
        tvUsernameLabel = view.findViewById(R.id.tv_username_label)
        tvPasswordLabel = view.findViewById(R.id.tv_password_label)
        tvCaptchaLabel = view.findViewById(R.id.tv_captcha_label)
        tvCodeLabel = view.findViewById(R.id.tv_code_label)
        ivCaptcha = view.findViewById(R.id.iv_captcha)
        containerAccountProfile = view.findViewById(R.id.container_account_profile)
        ivProfileAvatar = view.findViewById(R.id.iv_profile_avatar)
        tvProfileName = view.findViewById(R.id.tv_profile_name)
        tvProfileHandle = view.findViewById(R.id.tv_profile_handle)
        tvStatus = view.findViewById(R.id.tv_status)
        tvLastSync = view.findViewById(R.id.tv_last_sync)
        tvDebug = view.findViewById(R.id.tv_debug)
        tvError = view.findViewById(R.id.tv_error)
        tvItemCount = view.findViewById(R.id.tv_item_count)
        tvEmptyInventory = view.findViewById(R.id.tv_empty_inventory)
        containerItems = view.findViewById(R.id.container_items)
        containerPagination = view.findViewById(R.id.container_pagination)
        tvPageInfo = view.findViewById(R.id.tv_page_info)
        btnLogin = view.findViewById(R.id.btn_login)
        btnLogout = view.findViewById(R.id.btn_logout)
        btnRefresh = view.findViewById(R.id.btn_refresh)
        btnPrevPage = view.findViewById(R.id.btn_prev_page)
        btnNextPage = view.findViewById(R.id.btn_next_page)

        etUsername.setText(prefs.getString(KEY_USERNAME, "") ?: "")
        client = RsiInventoryClient(loadSession())
        updateLoginStateUi()
        renderProfile(loadCachedProfile())

        if (client.currentSession().isLoggedIn) {
            tvStatus.text = "已登录：${profileTitle(loadCachedProfile())}"
            loadUserProfile(forceRefresh = false)
            showCachedInventoryOrFetch()
        }

        btnLogin.setOnClickListener {
            when {
                pendingSecondStep -> submitSecondStepCode()
                pendingCaptcha -> submitCaptcha()
                else -> login()
            }
        }
        btnLogout.setOnClickListener { logout() }
        btnRefresh.setOnClickListener { fetchInventory(forceRefresh = true) }
        ivCaptcha.setOnClickListener { loadCaptchaImage() }
        btnPrevPage.setOnClickListener {
            if (currentDisplayPage > 0) {
                currentDisplayPage--
                renderCurrentPage()
            }
        }
        btnNextPage.setOnClickListener {
            if (currentDisplayPage < totalDisplayPages() - 1) {
                currentDisplayPage++
                renderCurrentPage()
            }
        }
    }

    private fun login() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showError("用户名和密码不能为空")
            return
        }

        tvStatus.text = "正在登录 RSI..."
        tvError.visibility = View.GONE
        btnLogin.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.login(username, password)
                }
                handleLoginResult(result, username)
            } catch (e: Exception) {
                tvStatus.text = "登录失败"
                showError(e.message ?: "登录失败")
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun submitCaptcha() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val captcha = etCaptcha.text.toString().trim()
        if (captcha.isEmpty()) {
            showError("请输入图片验证码")
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            showError("用户名和密码不能为空")
            return
        }

        tvStatus.text = "正在提交图片验证码..."
        tvError.visibility = View.GONE
        btnLogin.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.login(username, password, captcha)
                }
                handleLoginResult(result, username)
            } catch (e: Exception) {
                tvStatus.text = "图片验证码提交失败"
                showError(e.message ?: "图片验证码提交失败")
                loadCaptchaImage()
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun submitSecondStepCode() {
        val username = etUsername.text.toString().trim()
        val code = etCode.text.toString().trim()
        if (code.isEmpty()) {
            showError("请输入二步验证码")
            return
        }

        tvStatus.text = "正在提交二步验证码..."
        tvError.visibility = View.GONE
        btnLogin.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.submitCode(code)
                }
                handleLoginResult(result, username)
            } catch (e: Exception) {
                tvStatus.text = "验证码提交失败"
                showError(e.message ?: "验证码提交失败")
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun handleLoginResult(result: LoginResult, username: String) {
        if (result.success) {
            pendingSecondStep = false
            pendingCaptcha = false
            setCaptchaInputVisible(false)
            setCodeInputVisible(false)
            saveSession(username, result.session)
            etPassword.setText("")
            etCaptcha.setText("")
            etCode.setText("")
            updateLoginStateUi()
            tvStatus.text = "已登录：$username"
            loadUserProfile(forceRefresh = true)
            showCachedInventoryOrFetch()
            return
        }

        if (result.needCode) {
            pendingSecondStep = true
            pendingCaptcha = false
            setCaptchaInputVisible(false)
            setCodeInputVisible(true)
            btnLogin.text = "提交验证码"
            tvStatus.text = result.message
            tvError.visibility = View.GONE
            return
        }

        if (result.needCaptcha) {
            pendingCaptcha = true
            pendingSecondStep = false
            setCodeInputVisible(false)
            setCaptchaInputVisible(true)
            tvStatus.text = "请输入 RSI 图片验证码"
            tvError.visibility = View.GONE
            loadCaptchaImage()
            return
        }

        tvStatus.text = "登录失败"
        showError(result.message)
    }

    private fun loadCaptchaImage() {
        tvStatus.text = "正在加载 RSI 图片验证码..."
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    client.fetchCaptchaImage()
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("验证码图片解析失败")
                ivCaptcha.setImageBitmap(bitmap)
                tvStatus.text = "请输入图片中的验证码"
            } catch (e: Exception) {
                tvStatus.text = "验证码加载失败"
                showError(e.message ?: "验证码加载失败")
            }
        }
    }

    private fun showCachedInventoryOrFetch() {
        val cachedItems = loadCachedItems()
        if (cachedItems.isNotEmpty()) {
            tvStatus.text = "已加载本地缓存，共 ${cachedItems.size} 项"
            val lastSync = prefs.getString(KEY_LAST_SYNC, "") ?: ""
            tvLastSync.text = if (lastSync.isNotBlank()) "最近同步：$lastSync" else "最近同步：本地缓存"
            tvLastSync.visibility = View.VISIBLE
            tvError.visibility = View.GONE
            tvDebug.visibility = View.GONE
            renderItems(cachedItems)
        } else {
            fetchInventory(forceRefresh = false)
        }
    }

    private fun fetchInventory(forceRefresh: Boolean) {
        if (!client.currentSession().isLoggedIn) {
            showError("请先登录")
            return
        }

        if (forceRefresh) {
            loadUserProfile(forceRefresh = true)
        }
        tvStatus.text = if (forceRefresh) "正在刷新 RSI 机库库存..." else "正在首次拉取 RSI 机库库存..."
        tvError.visibility = View.GONE
        tvDebug.visibility = View.GONE
        btnRefresh.isEnabled = false

        scope.launch {
            try {
                val syncTime = formatDateTime(Date())
                val statusPrefix = if (forceRefresh) "正在刷新 RSI 机库库存..." else "正在首次拉取 RSI 机库库存..."
                val items = withContext(Dispatchers.IO) {
                    client.fetchInventory(
                        maxPages = MAX_PAGES,
                        onPageFetched = { page, _, allItems ->
                            scope.launch {
                                requestCountdownJob?.cancel()
                                tvStatus.text = "$statusPrefix 已加载第 $page 页，共 ${allItems.size} 项"
                                tvLastSync.text = "最近同步：$syncTime（进行中）"
                                tvLastSync.visibility = View.VISIBLE
                                tvDebug.visibility = View.GONE
                                saveCachedItems(allItems, syncTime)
                                renderItems(allItems)
                            }
                        },
                        onRequestStateChanged = { requestState ->
                            scope.launch {
                                startRequestCountdown(
                                    prefix = statusPrefix,
                                    requestState = requestState,
                                    loadedItems = inventoryItems.size
                                )
                            }
                        }
                    )
                }
                requestCountdownJob?.cancel()
                tvStatus.text = "库存已更新，共 ${items.size} 项"
                tvLastSync.text = "最近同步：$syncTime"
                tvLastSync.visibility = View.VISIBLE
                tvDebug.visibility = View.GONE
                saveSession(etUsername.text.toString().trim(), client.currentSession())
                saveCachedItems(items, syncTime)
                renderItems(items)
            } catch (e: InventoryParseException) {
                requestCountdownJob?.cancel()
                tvStatus.text = "库存拉取失败"
                showDebugLog()
                showError(formatFetchError(e))
            } catch (e: Exception) {
                requestCountdownJob?.cancel()
                tvStatus.text = "库存拉取失败"
                showDebugLog()
                showError(formatFetchError(e))
            } finally {
                requestCountdownJob?.cancel()
                btnRefresh.isEnabled = true
            }
        }
    }

    private fun startRequestCountdown(
        prefix: String,
        requestState: InventoryRequestState,
        loadedItems: Int
    ) {
        requestCountdownJob?.cancel()
        requestCountdownJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val elapsedMs = System.currentTimeMillis() - startedAt
                val remainingMs = (RsiInventoryClient.REQUEST_TIMEOUT_MS - elapsedMs).coerceAtLeast(0L)
                val remainingSeconds = kotlin.math.ceil(remainingMs / 1000.0).toInt()
                val loadedText = if (loadedItems > 0) "，已加载 ${loadedItems} 项" else ""
                tvStatus.text =
                    "$prefix 第 ${requestState.page} 页（第 ${requestState.attempt}/${requestState.maxAttempts} 次），本次请求剩余 ${remainingSeconds} 秒$loadedText"
                if (remainingMs == 0L) break
                delay(1000L)
            }
        }
    }

    private fun formatFetchError(error: Throwable): String {
        return when (error) {
            is InventoryParseException -> "[PARSE_ERROR] ${error.message ?: "页面解析失败"}，请退出后重新登录"
            is InventoryFetchException -> {
                val page = error.page?.let { " 第${it}页" }.orEmpty()
                when (error.type) {
                    InventoryErrorType.HTTP -> "[HTTP_${error.statusCode ?: "-"}]$page ${error.message ?: "请求失败"}"
                    InventoryErrorType.TIMEOUT -> "[TIMEOUT]$page 请求超时，请稍后重试"
                    InventoryErrorType.NETWORK -> "[NETWORK]$page ${error.message ?: "网络异常"}"
                }
            }
            else -> error.message ?: "库存拉取失败"
        }
    }

    private fun logout() {
        prefs.edit().clear().apply()
        client = RsiInventoryClient()
        pendingSecondStep = false
        pendingCaptcha = false
        etPassword.setText("")
        etCaptcha.setText("")
        etCode.setText("")
        setCaptchaInputVisible(false)
        setCodeInputVisible(false)
        tvStatus.text = "已退出登录"
        tvError.visibility = View.GONE
        tvLastSync.visibility = View.GONE
        tvDebug.visibility = View.GONE
        requestCountdownJob?.cancel()
        containerItems.removeAllViews()
        clearCachedItems()
        inventoryItems = emptyList()
        currentDisplayPage = 0
        updateLoginStateUi()
        renderProfile(RsiUserProfile())
        tvItemCount.text = "0 项"
        tvEmptyInventory.visibility = View.VISIBLE
        containerPagination.visibility = View.GONE
    }

    private fun renderItems(items: List<InventoryItem>) {
        inventoryItems = items
        currentDisplayPage = 0
        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        containerItems.removeAllViews()
        val totalPages = totalDisplayPages()
        val pageItems = inventoryItems
            .drop(currentDisplayPage * PAGE_SIZE)
            .take(PAGE_SIZE)
        tvItemCount.text = if (inventoryItems.isEmpty()) {
            "0 项"
        } else {
            "共 ${inventoryItems.size} 项 · ${currentDisplayPage + 1}/$totalPages"
        }
        tvEmptyInventory.visibility = if (inventoryItems.isEmpty()) View.VISIBLE else View.GONE
        containerPagination.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        tvPageInfo.text = "${currentDisplayPage + 1} / $totalPages"
        btnPrevPage.isEnabled = currentDisplayPage > 0
        btnNextPage.isEnabled = currentDisplayPage < totalPages - 1
        pageItems.forEach { containerItems.addView(createItemCard(it)) }
    }

    private fun createItemCard(item: InventoryItem): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4.dpToPx()
                bottomMargin = 4.dpToPx()
            }
            setBackgroundColor(Color.parseColor("#111d2b"))
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }

        val image = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(82.dpToPx(), 82.dpToPx()).apply {
                rightMargin = 10.dpToPx()
            }
            setBackgroundColor(Color.parseColor("#0a1420"))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = item.name
        }
        card.addView(image)
        loadItemImage(item.imageUrl, image)

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        content.addView(TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = item.name
            textSize = 14f
            setTextColor(Color.parseColor("#d8eaf2"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })

        val priceRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dpToPx() }
        }
        priceRow.addView(priceBlock("可融价值", item.priceCents))
        priceRow.addView(priceBlock("当前价格", item.currentPriceCents))
        content.addView(priceRow)

        val tags = listOfNotNull(
            item.insurance.takeIf { it.isNotBlank() },
            "P${item.page}",
            "礼物".takeIf { item.canGift },
            "可融".takeIf { item.canReclaim },
            "CCU".takeIf { item.canUpgrade }
        ).joinToString("  ·  ")
        content.addView(metaText(tags.ifBlank { item.status.ifBlank { item.date } }))

        if (item.date.isNotBlank()) {
            content.addView(metaText(item.date))
        }

        card.addView(content)
        return card
    }

    private fun priceBlock(label: String, cents: Int): TextView {
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "$label\n${priceText(cents)}"
            textSize = 12f
            setTextColor(Color.parseColor("#21d4ff"))
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun metaText(textValue: String): TextView {
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() }
            text = textValue
            textSize = 12f
            setTextColor(Color.parseColor("#7c95a8"))
        }
    }

    private fun setCodeInputVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        tvCodeLabel.visibility = visibility
        etCode.visibility = visibility
        btnLogin.text = if (visible) "提交验证码" else "登录并拉取库存"
    }

    private fun setCaptchaInputVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        tvCaptchaLabel.visibility = visibility
        ivCaptcha.visibility = visibility
        etCaptcha.visibility = visibility
        btnLogin.text = if (visible) "提交图片验证码" else "登录并拉取库存"
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun showDebugLog() {
        val log = client.debugLog()
        if (log.isBlank()) {
            tvDebug.visibility = View.GONE
        } else {
            tvDebug.text = log
            tvDebug.visibility = View.VISIBLE
        }
    }

    private fun updateLoginStateUi() {
        val loggedIn = client.currentSession().isLoggedIn
        val loginVisibility = if (loggedIn) View.GONE else View.VISIBLE
        val sessionVisibility = if (loggedIn) View.VISIBLE else View.GONE
        tvUsernameLabel.visibility = loginVisibility
        etUsername.visibility = loginVisibility
        tvPasswordLabel.visibility = loginVisibility
        etPassword.visibility = loginVisibility
        btnLogin.visibility = loginVisibility
        btnLogout.visibility = sessionVisibility
        btnRefresh.visibility = sessionVisibility
        containerAccountProfile.visibility = sessionVisibility
        if (loggedIn) {
            setCaptchaInputVisible(false)
            setCodeInputVisible(false)
        }
    }

    private fun loadUserProfile(forceRefresh: Boolean) {
        if (!client.currentSession().isLoggedIn) return
        val cached = loadCachedProfile()
        if (cached.displayName.isNotBlank() || cached.handle.isNotBlank() || cached.avatarUrl.isNotBlank()) {
            renderProfile(cached)
        }
        if (!forceRefresh && cached.displayName.isNotBlank() && cached.handle.isNotBlank() && cached.avatarUrl.isNotBlank()) {
            return
        }
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { client.fetchUserProfile() }.getOrNull()
            } ?: return@launch
            saveProfile(profile)
            saveSession(etUsername.text.toString().trim(), client.currentSession())
            renderProfile(profile)
            if (tvStatus.text.startsWith("已登录")) {
                tvStatus.text = "已登录：${profileTitle(profile)}"
            }
        }
    }

    private fun renderProfile(profile: RsiUserProfile) {
        val title = profileTitle(profile)
        tvProfileName.text = title.ifBlank { "已登录" }
        tvProfileHandle.text = when {
            profile.handle.isNotBlank() -> "@${profile.handle}"
            etUsername.text.isNotBlank() -> etUsername.text.toString()
            else -> "RSI SESSION"
        }
        ivProfileAvatar.setImageDrawable(null)
        ivProfileAvatar.tag = null
        loadProfileImage(profile.avatarUrl)
    }

    private fun profileTitle(profile: RsiUserProfile): String {
        return profile.displayName
            .ifBlank { profile.handle }
            .ifBlank { etUsername.text.toString().trim() }
            .ifBlank { "RSI 账号" }
    }

    private fun totalDisplayPages(): Int {
        return maxOf(1, (inventoryItems.size + PAGE_SIZE - 1) / PAGE_SIZE)
    }

    private fun loadItemImage(url: String, imageView: ImageView) {
        if (url.isBlank()) return
        imageView.tag = url
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Referer", "https://robertsspaceindustries.com/")
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        Log.d(TAG, "图片加载失败：$code $url")
                        return@runCatching null
                    }
                    try {
                        BitmapFactory.decodeStream(conn.inputStream)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (imageView.tag == url && bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadProfileImage(url: String) {
        if (url.isBlank()) return
        ivProfileAvatar.tag = url
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Referer", "https://robertsspaceindustries.com/")
                    val code = conn.responseCode
                    if (code !in 200..299) return@runCatching null
                    try {
                        BitmapFactory.decodeStream(conn.inputStream)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (ivProfileAvatar.tag == url && bitmap != null) {
                ivProfileAvatar.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadSession(): RsiSession {
        return RsiSession(
            token = prefs.getString(KEY_TOKEN, "") ?: "",
            device = prefs.getString(KEY_DEVICE, "") ?: "",
            accountAuth = prefs.getString(KEY_AUTH, "") ?: "",
            upgradeContext = prefs.getString(KEY_UPGRADE_CONTEXT, "") ?: ""
        )
    }

    private fun loadCachedItems(): List<InventoryItem> {
        if (prefs.getInt(KEY_CACHE_VERSION, 0) != CACHE_VERSION) return emptyList()
        return runCatching {
            InventoryCacheCodec.decodeItems(prefs.getString(KEY_ITEMS_CACHE, "") ?: "")
        }.getOrDefault(emptyList())
    }

    private fun loadCachedProfile(): RsiUserProfile {
        return RsiUserProfile(
            displayName = prefs.getString(KEY_PROFILE_NAME, "") ?: "",
            handle = prefs.getString(KEY_PROFILE_HANDLE, "") ?: "",
            avatarUrl = prefs.getString(KEY_PROFILE_AVATAR, "") ?: ""
        )
    }

    private fun saveProfile(profile: RsiUserProfile) {
        prefs.edit()
            .putString(KEY_PROFILE_NAME, profile.displayName)
            .putString(KEY_PROFILE_HANDLE, profile.handle)
            .putString(KEY_PROFILE_AVATAR, profile.avatarUrl)
            .apply()
    }

    private fun saveCachedItems(items: List<InventoryItem>, syncTime: String) {
        prefs.edit()
            .putInt(KEY_CACHE_VERSION, CACHE_VERSION)
            .putString(KEY_ITEMS_CACHE, InventoryCacheCodec.encodeItems(items))
            .putString(KEY_LAST_SYNC, syncTime)
            .apply()
    }

    private fun clearCachedItems() {
        prefs.edit()
            .remove(KEY_ITEMS_CACHE)
            .remove(KEY_LAST_SYNC)
            .remove(KEY_CACHE_VERSION)
            .apply()
    }

    private fun saveSession(username: String, session: RsiSession) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_DEVICE, session.device)
            .putString(KEY_AUTH, session.accountAuth)
            .putString(KEY_UPGRADE_CONTEXT, session.upgradeContext)
            .apply()
    }

    private fun priceText(cents: Int): String {
        if (cents <= 0) return "-"
        return "$${"%.2f".format(Locale.US, cents / 100.0)}"
    }

    private fun formatDateTime(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(date)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        requestCountdownJob?.cancel()
        super.onDestroyView()
        scope.cancel()
    }

    companion object {
        private const val MAX_PAGES = 30
        private const val PAGE_SIZE = 10
        private const val TAG = "InventoryFragment"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val KEY_USERNAME = "inventoryUsername"
        private const val KEY_TOKEN = "inventoryRsiToken"
        private const val KEY_DEVICE = "inventoryRsiDevice"
        private const val KEY_AUTH = "inventoryRsiAccountAuth"
        private const val KEY_UPGRADE_CONTEXT = "inventoryRsiUpgradeContext"
        private const val KEY_ITEMS_CACHE = "inventoryItemsCache"
        private const val KEY_LAST_SYNC = "inventoryLastSync"
        private const val KEY_CACHE_VERSION = "inventoryCacheVersion"
        private const val KEY_PROFILE_NAME = "inventoryProfileName"
        private const val KEY_PROFILE_HANDLE = "inventoryProfileHandle"
        private const val KEY_PROFILE_AVATAR = "inventoryProfileAvatar"
        private const val CACHE_VERSION = 2
    }
}
