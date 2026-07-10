package com.euedrc.bugsc.agent

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.market.publish.MarketPublishJson
import com.euedrc.bugsc.market.publish.PublishItemValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class AgentChatFragment : Fragment() {

    private val profile = AgentProfileProvider.defaultProfile()
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var runStatus: TextView
    private lateinit var settingsStore: AgentSettingsStore
    private lateinit var historyStore: AgentHistoryStore
    private val localProvider: LocalAgentDataProvider by lazy { LocalAgentDataProvider(requireContext()) }
    private val marketPublish get() = AppServices.marketPublish
    private val transactions get() = AppServices.transactions
    private val auth get() = AppServices.auth
    private var conversationVersion: Long = 0L
    private var pendingOrderDraft: ScmOrderDraftResolution.Resolved? = null
    private var pendingOrderParse: ScmOrderDraftParseResult? = null
    private var runTimer: Job? = null
    private var runStartedAt: Long = 0L
    private var runStatusLabel: String = "思考中"
    private var inFlightJob: Job? = null
    private var activeAssistantBubble: TextView? = null
    private val activeAssistantText = StringBuilder()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_agent_chat, parent, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        settingsStore = AgentStores.settings(requireContext())
        historyStore = AgentStores.history(requireContext())
        container = view.findViewById(R.id.container_agent_messages)
        scroll = view.findViewById(R.id.scroll_agent_messages)
        input = view.findViewById(R.id.et_agent_input)
        send = view.findViewById(R.id.btn_agent_send)
        stop = view.findViewById(R.id.btn_agent_stop)
        runStatus = view.findViewById(R.id.tv_agent_run_status)
        view.findViewById<TextView>(R.id.tv_agent_title).text = profile.displayName
        view.findViewById<TextView>(R.id.tv_agent_status).text = getString(R.string.agent_status_deepseek)
        view.findViewById<TextView>(R.id.tv_agent_tagline).text = profile.tagline
        view.findViewById<Button>(R.id.btn_agent_new_chat).setOnClickListener {
            track("new_chat")
            startNewChat()
        }
        view.findViewById<Button>(R.id.btn_agent_history).setOnClickListener {
            track("open_history")
            showConversationHistory()
        }
        view.findViewById<Button>(R.id.btn_agent_settings).setOnClickListener {
            track("open_settings")
            findNavController().navigate(R.id.AgentSettingsFragment)
        }
        send.setOnClickListener {
            track("send")
            sendMessage()
        }
        stop.setOnClickListener {
            track("stop")
            cancelCurrentRun(showStopped = true)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = maxOf(ime.bottom, nav.bottom))
            if (ime.bottom > nav.bottom) scrollToBottom()
            insets
        }
        renderHistory()
        renderConfigState()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) renderConfigState()
    }

    override fun onDestroyView() {
        cancelCurrentRun(showStopped = false)
        runTimer?.cancel()
        runTimer = null
        activeAssistantBubble = null
        inFlightJob = null
        super.onDestroyView()
    }

    private fun renderConfigState() {
        val configured = settingsStore.settings().isConfigured
        input.hint = if (configured) getString(R.string.agent_input_hint) else getString(R.string.agent_input_config_hint)
    }

    private fun renderHistory() {
        container.removeAllViews()
        val history = historyStore.load()
        if (history.isEmpty()) {
            addBubble(AgentProfileProvider.chatGreeting(profile), mine = false)
        } else {
            history.forEach { addBubble(it.content, mine = it.role == AgentMessageRole.USER) }
        }
        scrollToBottom()
    }

    private fun sendMessage() {
        if (inFlightJob != null) return
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        val settings = settingsStore.settings()
        if (!settings.isConfigured) {
            Toast.makeText(requireContext(), R.string.agent_input_config_hint, Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.AgentSettingsFragment)
            return
        }
        val requestVersion = conversationVersion
        val historyBeforeSend = historyStore.load()
        val userMsg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.USER, text, System.currentTimeMillis(), AgentMessageStatus.SENT)
        historyStore.append(userMsg)
        addBubble(text, mine = true)
        input.setText("")
        val assistantBubble = addBubble("…", mine = false)
        activeAssistantBubble = assistantBubble
        activeAssistantText.clear()
        setAgentRunning(true)
        startRunStatus()
        val job = lifecycleScope.launch {
            val draftState = ScmOrderDraftToolState(pendingParse = pendingOrderParse)
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val runtime = buildRuntime(draftState = draftState)
                    val answer = runtime.answer(text, historyBeforeSend)
                    answer to runtime.lastToolEvidence
                }
                if (!isAdded || requestVersion != conversationVersion) return@launch
                val (answer, toolEvidence) = outcome
                updateBubble(assistantBubble, answer)
                val msg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.ASSISTANT, answer, System.currentTimeMillis(), AgentMessageStatus.SENT, toolSummary = toolEvidence)
                historyStore.append(msg)
                pendingOrderParse = draftState.pendingParse
                draftState.resolved?.let { resolution ->
                    pendingOrderDraft = resolution
                    addOrderActions(resolution)
                }
                finishRunStatus()
                scrollToBottom()
            } catch (error: CancellationException) {
                if (isAdded && requestVersion == conversationVersion) {
                    updateBubble(assistantBubble, "已停止。")
                    finishRunStatus("已停止")
                }
            } catch (error: Throwable) {
                if (isAdded && requestVersion == conversationVersion) {
                    val message = userFacingError(error)
                    updateBubble(assistantBubble, message)
                    val msg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.ASSISTANT, message, System.currentTimeMillis(), AgentMessageStatus.FAILED)
                    historyStore.append(msg)
                    finishRunStatus()
                    scrollToBottom()
                }
            } finally {
                if (inFlightJob === coroutineContext[Job]) {
                    inFlightJob = null
                    activeAssistantBubble = null
                    activeAssistantText.clear()
                    setAgentRunning(false)
                }
            }
        }
        inFlightJob = job
    }

    private fun sendOrderDraftMessage(text: String, parsed: ScmOrderDraftParseResult) {
        if (inFlightJob != null) return
        val requestVersion = conversationVersion
        val userMsg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.USER, text, System.currentTimeMillis(), AgentMessageStatus.SENT)
        historyStore.append(userMsg)
        addBubble(text, mine = true)
        input.setText("")
        if (!auth.isLoggedIn()) {
            appendAssistantMessage("需要先登录 SCM，才能创建订单。")
            return
        }
        lifecycleScope.launch {
            val index = localProvider.entityIndex()
            val resolution = withContext(Dispatchers.IO) {
                runCatching {
                    ScmOrderDraftResolver(
                        itemSearch = { keyword ->
                            ScmSearchTermExpander.expand(keyword, index)
                                .asSequence()
                                .map { candidate -> kotlinx.coroutines.runBlocking { marketPublish.searchItems(candidate) } }
                                .firstOrNull { it.isNotEmpty() }
                                .orEmpty()
                        },
                        addressList = { kotlinx.coroutines.runBlocking { transactions.addressList() } },
                    ).resolve(parsed)
                }.getOrElse { ScmOrderDraftResolution.NeedMoreInfo(it.message ?: "订单草稿解析失败。") }
            }
            if (!isAdded || requestVersion != conversationVersion) return@launch
            when (resolution) {
                is ScmOrderDraftResolution.NeedMoreInfo -> {
                    pendingOrderParse = parsed
                    appendAssistantMessage(resolution.message)
                }
                is ScmOrderDraftResolution.Resolved -> {
                    pendingOrderParse = null
                    pendingOrderDraft = resolution
                    appendAssistantMessage(resolution.confirmationMarkdown())
                    addOrderActions(resolution)
                    scrollToBottom()
                }
            }
        }
    }

    private fun startNewChat() {
        conversationVersion += 1L
        cancelCurrentRun(showStopped = false)
        pendingOrderDraft = null
        pendingOrderParse = null
        historyStore.archiveCurrentConversation()
        historyStore.clear()
        input.setText("")
        renderHistory()
        Toast.makeText(requireContext(), R.string.agent_new_chat_started, Toast.LENGTH_SHORT).show()
    }

    private fun showConversationHistory() {
        val sessions = historyStore.loadArchivedSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(requireContext(), R.string.agent_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }
        panel.addView(
            TextView(requireContext()).apply {
                text = getString(R.string.agent_history)
                setTextColor("#d8eaf2".toColorInt())
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        panel.addView(
            TextView(requireContext()).apply {
                text = getString(R.string.agent_history_recent_sessions, sessions.size)
                setTextColor("#7c95a8".toColorInt())
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(3), 0, dp(12))
            },
        )
        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        sessions.forEach { session ->
            list.addView(historySessionRow(dialog, session))
        }
        panel.addView(
            ScrollView(requireContext()).apply {
                isFillViewport = false
                addView(list)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(resources.displayMetrics.heightPixels * 55 / 100, dp(420)),
            ),
        )
        panel.addView(
            Button(requireContext()).apply {
                text = getString(R.string.agent_cancel)
                isAllCaps = false
                setTextColor("#21d4ff".toColorInt())
                setBackgroundResource(R.drawable.button_outline_accent)
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 92 / 100),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun historySessionRow(dialog: Dialog, session: AgentConversationSession): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.agent_history_item_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                track("restore_history")
                dialog.dismiss()
                restoreConversation(session.id)
            }
        }
        row.addView(
            TextView(requireContext()).apply {
                text = session.title
                setTextColor("#d8eaf2".toColorInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            },
        )
        row.addView(
            TextView(requireContext()).apply {
                text = "${session.messages.size} 条消息 · ${formatSessionTime(session.updatedAt)}"
                setTextColor("#7c95a8".toColorInt())
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            },
        )
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun formatSessionTime(timestamp: Long): String =
        if (timestamp <= 0L) {
            "--"
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
        }

    private fun restoreConversation(sessionId: String) {
        conversationVersion += 1L
        cancelCurrentRun(showStopped = false)
        pendingOrderDraft = null
        pendingOrderParse = null
        historyStore.archiveCurrentConversation()
        if (historyStore.restoreArchivedSession(sessionId)) {
            input.setText("")
            renderHistory()
            Toast.makeText(requireContext(), R.string.agent_history_restored, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildRuntime(
        draftState: ScmOrderDraftToolState = ScmOrderDraftToolState(),
    ): AgentRuntime {
        val index = localProvider.entityIndex()
        val tools = AgentLocalSearchTools.create(localProvider, index) +
            OnlineShipAgentTools.create(AppServices.shipOnline) +
            ScmAgentTools.create(entityIndex = index) +
            AppUtilityTools.create(requireContext()) +
            ScmOrderDraftTool(
                state = draftState,
                isLoggedIn = { auth.isLoggedIn() },
                itemSearch = { keyword ->
                    ScmSearchTermExpander.expand(keyword, index)
                        .asSequence()
                        .map { candidate -> kotlinx.coroutines.runBlocking { marketPublish.searchItems(candidate) } }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                },
                addressList = { kotlinx.coroutines.runBlocking { transactions.addressList() } },
                entityIndex = index,
            )
        return AgentRuntime(
            analyzer = QueryAnalyzer(index),
            promptBuilder = AgentPromptBuilder(profile),
            deepSeekClient = DeepSeekClient(UrlConnectionDeepSeekTransport()),
            settingsProvider = { settingsStore.settings() },
            toolRegistry = AgentToolRegistry(tools),
            toolCallingEnabled = true,
            observer = ::onRuntimeEvent,
        )
    }

    private fun onRuntimeEvent(event: AgentRuntimeEvent) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            when (event) {
                is AgentRuntimeEvent.Thinking -> updateRunStatus("思考中")
                is AgentRuntimeEvent.AnswerDelta -> appendActiveAssistantDelta(event.delta)
                is AgentRuntimeEvent.ToolCallStarted -> updateRunStatus("工具：${event.tool}")
                is AgentRuntimeEvent.ToolCallFinished -> updateRunStatus("工具完成：${event.tool}")
                is AgentRuntimeEvent.ModelJson -> Unit
                is AgentRuntimeEvent.FinalAnswerReady -> Unit
            }
        }
    }

    private fun startRunStatus() {
        runTimer?.cancel()
        runStartedAt = System.currentTimeMillis()
        runStatusLabel = "思考中"
        runStatus.visibility = View.VISIBLE
        renderRunStatus()
        runTimer = lifecycleScope.launch {
            while (true) {
                delay(1000)
                renderRunStatus()
            }
        }
    }

    private fun updateRunStatus(label: String) {
        runStatusLabel = label
        if (::runStatus.isInitialized) {
            runStatus.visibility = View.VISIBLE
            renderRunStatus()
        }
    }

    private fun renderRunStatus() {
        val seconds = elapsedRunSeconds(minimum = 0L)
        runStatus.text = "$runStatusLabel · ${seconds}s"
    }

    private fun finishRunStatus(prefix: String = "回答用时") {
        runTimer?.cancel()
        runTimer = null
        val seconds = elapsedRunSeconds(minimum = 1L)
        runStatus.text = "$prefix ${seconds}s"
        runStatus.visibility = View.VISIBLE
        runStatus.postDelayed({
            if (::runStatus.isInitialized && runTimer == null) {
                runStatus.visibility = View.GONE
            }
        }, 1600L)
    }

    private fun elapsedRunSeconds(minimum: Long): Long =
        max(minimum, (System.currentTimeMillis() - runStartedAt) / 1000L)

    private fun addBubble(text: String, mine: Boolean): TextView {
        val bubble = TextView(requireContext()).apply {
            textSize = 14f
            setTextColor(if (mine) "#001119".toColorInt() else "#d8eaf2".toColorInt())
            setBackgroundResource(if (mine) R.drawable.chat_bubble_outgoing else R.drawable.chat_bubble_incoming)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            typeface = Typeface.DEFAULT
            AgentMarkdownFormatter.bind(this, text, mine)
        }
        val row = LinearLayout(requireContext()).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(
            bubble,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (mine) dp(48) else 0
                marginEnd = if (mine) 0 else dp(48)
            },
        )
        container.addView(row)
        return bubble
    }

    private fun updateBubble(bubble: TextView, text: String) {
        AgentMarkdownFormatter.bind(bubble, text, mine = false)
        scrollToBottom()
    }

    private fun appendActiveAssistantDelta(delta: String) {
        val bubble = activeAssistantBubble ?: return
        activeAssistantText.append(delta)
        updateBubble(bubble, activeAssistantText.toString())
    }

    private fun appendAssistantMessage(text: String) {
        val msg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.ASSISTANT, text, System.currentTimeMillis(), AgentMessageStatus.SENT)
        historyStore.append(msg)
        if (isAdded) {
            addBubble(text, mine = false)
            scrollToBottom()
        }
    }

    private fun addOrderActions(draft: ScmOrderDraftResolution.Resolved) {
        val row = LinearLayout(requireContext()).apply {
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(6))
        }
        val confirm = Button(requireContext()).apply {
            text = "确认创建"
            isAllCaps = false
            setOnClickListener {
                track("confirm_order_draft")
                row.visibility = View.GONE
                createPendingOrder(draft)
            }
        }
        val cancel = Button(requireContext()).apply {
            text = "取消"
            isAllCaps = false
            setOnClickListener {
                track("cancel_order_draft")
                if (pendingOrderDraft == draft) pendingOrderDraft = null
                pendingOrderParse = null
                appendAssistantMessage("已取消创建订单。")
                row.visibility = View.GONE
            }
        }
        row.addView(confirm)
        row.addView(cancel)
        container.addView(row)
    }

    private fun createPendingOrder(draft: ScmOrderDraftResolution.Resolved) {
        if (pendingOrderDraft != draft) {
            appendAssistantMessage("这个订单草稿已失效，请重新发起创建。")
            return
        }
        pendingOrderDraft = null
        pendingOrderParse = null
        appendAssistantMessage("正在创建 SCM 订单…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val item = PublishItemValue(
                        item = draft.item,
                        quantity = draft.parsed.quantity,
                        price = requireNotNull(draft.parsed.unitPrice),
                        quality = draft.parsed.quality,
                    )
                    marketPublish.createOrder(
                        creatorType = requireNotNull(draft.parsed.creatorType),
                        locationId = draft.location.id,
                        status = draft.parsed.status,
                        expireTime = draft.parsed.expireTime,
                        items = listOf(item),
                    )
                    marketPublish.ownOrders(1, creatorType = draft.parsed.creatorType)
                        .let { MarketPublishJson.findCreatedOrderNumber(it, draft.parsed.creatorType, draft.item.itemName) }
                }
            }
            if (!isAdded) return@launch
            result.onFailure {
                appendAssistantMessage("创建失败：${it.message ?: "SCM 请求失败"}")
            }.onSuccess { orderNumber ->
                appendAssistantMessage(
                    if (orderNumber.isBlank()) {
                        "订单已创建，可在“我的挂单”查看。"
                    } else {
                        "订单已创建：$orderNumber"
                    },
                )
            }
        }
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setAgentRunning(running: Boolean) {
        if (::send.isInitialized) send.isEnabled = !running
        if (::input.isInitialized) input.isEnabled = !running
        if (::stop.isInitialized) stop.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun cancelCurrentRun(showStopped: Boolean) {
        val job = inFlightJob ?: return
        if (showStopped) {
            job.cancel(CancellationException("user stopped agent run"))
        } else {
            job.cancel()
        }
    }

    private fun userFacingError(error: Throwable): String =
        if (error is DeepSeekClientException) error.userMessage else getString(R.string.agent_request_failed)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("agent", feature)
    }
}
