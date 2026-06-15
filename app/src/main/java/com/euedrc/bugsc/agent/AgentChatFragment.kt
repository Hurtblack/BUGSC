package com.euedrc.bugsc.agent

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.market.publish.MarketPublishClient
import com.euedrc.bugsc.market.publish.MarketPublishJson
import com.euedrc.bugsc.market.publish.PublishItemValue
import com.euedrc.bugsc.market.transaction.TransactionClient
import com.euedrc.bugsc.scm.ScmAuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AgentChatFragment : Fragment() {

    private val profile = AgentProfileProvider.defaultProfile()
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var settingsStore: AgentSettingsStore
    private lateinit var historyStore: AgentHistoryStore
    private val localProvider: LocalAgentDataProvider by lazy { LocalAgentDataProvider(requireContext()) }
    private val marketPublishClient: MarketPublishClient by lazy { MarketPublishClient() }
    private val transactionClient: TransactionClient by lazy { TransactionClient() }
    private var conversationVersion: Long = 0L
    private var pendingOrderDraft: ScmOrderDraftResolution.Resolved? = null
    private var pendingOrderParse: ScmOrderDraftParseResult? = null

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
        view.findViewById<TextView>(R.id.tv_agent_title).text = profile.displayName
        view.findViewById<TextView>(R.id.tv_agent_status).text = getString(R.string.agent_status_deepseek)
        view.findViewById<TextView>(R.id.tv_agent_tagline).text = profile.tagline
        view.findViewById<Button>(R.id.btn_agent_new_chat).setOnClickListener {
            startNewChat()
        }
        view.findViewById<Button>(R.id.btn_agent_settings).setOnClickListener {
            findNavController().navigate(R.id.AgentSettingsFragment)
        }
        send.setOnClickListener { sendMessage() }
        renderHistory()
        renderConfigState()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) renderConfigState()
    }

    private fun renderConfigState() {
        val configured = settingsStore.settings().isConfigured
        input.hint = if (configured) getString(R.string.agent_input_hint) else getString(R.string.agent_input_config_hint)
    }

    private fun renderHistory() {
        container.removeAllViews()
        val history = historyStore.load()
        if (history.isEmpty()) {
            addBubble(profile.roleDescription, mine = false)
        } else {
            history.forEach { addBubble(it.content, mine = it.role == AgentMessageRole.USER) }
        }
        scrollToBottom()
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        val pendingParse = pendingOrderParse
        val orderDraft = if (pendingParse != null) {
            ScmOrderDraftParser.mergeFollowUp(pendingParse, text)
        } else {
            ScmOrderDraftParser.parse(text)
        }
        if (orderDraft.isOrderIntent) {
            sendOrderDraftMessage(text, orderDraft)
            return
        }
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
        lifecycleScope.launch {
            val answer = withContext(Dispatchers.IO) {
                runCatching { buildRuntime().answer(text, historyBeforeSend) }
                    .getOrElse { it.message ?: getString(R.string.agent_request_failed) }
            }
            if (!isAdded || requestVersion != conversationVersion) return@launch
            val msg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.ASSISTANT, answer, System.currentTimeMillis(), AgentMessageStatus.SENT)
            historyStore.append(msg)
            addBubble(answer, mine = false)
            scrollToBottom()
        }
    }

    private fun sendOrderDraftMessage(text: String, parsed: ScmOrderDraftParseResult) {
        val requestVersion = conversationVersion
        val userMsg = AgentMessage(UUID.randomUUID().toString(), AgentMessageRole.USER, text, System.currentTimeMillis(), AgentMessageStatus.SENT)
        historyStore.append(userMsg)
        addBubble(text, mine = true)
        input.setText("")
        if (!ScmAuthStore.isLoggedIn) {
            appendAssistantMessage("需要先登录 SCM，才能创建订单。")
            return
        }
        lifecycleScope.launch {
            val resolution = withContext(Dispatchers.IO) {
                runCatching {
                    ScmOrderDraftResolver(
                        itemSearch = { marketPublishClient.searchItems(it) },
                        addressList = { transactionClient.addressList() },
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
        pendingOrderDraft = null
        pendingOrderParse = null
        historyStore.clear()
        input.setText("")
        renderHistory()
        Toast.makeText(requireContext(), R.string.agent_new_chat_started, Toast.LENGTH_SHORT).show()
    }

    private fun buildRuntime(): AgentRuntime {
        val index = localProvider.entityIndex()
        return AgentRuntime(
            analyzer = QueryAnalyzer(index),
            promptBuilder = AgentPromptBuilder(profile),
            deepSeekClient = DeepSeekClient(UrlConnectionDeepSeekTransport()),
            settingsProvider = { settingsStore.settings() },
            planner = AgentPlanner(AgentSkillCardProvider.defaultCards()),
            toolRegistry = AgentToolRegistry(AgentLocalSearchTools.create(localProvider, index) + ScmAgentTools.create()),
        )
    }

    private fun addBubble(text: String, mine: Boolean) {
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
                row.visibility = View.GONE
                createPendingOrder(draft)
            }
        }
        val cancel = Button(requireContext()).apply {
            text = "取消"
            isAllCaps = false
            setOnClickListener {
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
                        quality = null,
                    )
                    marketPublishClient.createOrder(
                        creatorType = requireNotNull(draft.parsed.creatorType),
                        locationId = draft.location.id,
                        status = draft.parsed.status,
                        expireTime = draft.parsed.expireTime,
                        items = listOf(item),
                    )
                    marketPublishClient.ownOrders(1, creatorType = draft.parsed.creatorType)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
