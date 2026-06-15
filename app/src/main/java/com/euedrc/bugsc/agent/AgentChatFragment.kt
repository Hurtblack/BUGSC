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
    private var conversationVersion: Long = 0L

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

    private fun startNewChat() {
        conversationVersion += 1L
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
            toolRegistry = AgentToolRegistry(AgentLocalSearchTools.create(localProvider, index)),
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

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
