package com.euedrc.bugsc.chat

import android.graphics.Color
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.navigateToLoginGated
import com.euedrc.bugsc.scm.ScmAuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 一对一聊天：历史 + WebSocket 实时收发。参数 otherUserId / otherUserNickname。 */
class ChatFragment : Fragment() {

    private val otherUserId: Long by lazy { arguments?.getLong(ARG_OTHER_ID) ?: 0L }
    private val otherNickname: String by lazy { arguments?.getString(ARG_OTHER_NICK).orEmpty() }
    private val currentUserId: Long by lazy { ScmAuthStore.session().userId }

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView

    private var socket: ChatSocket? = null
    private val sentClientIds = HashSet<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 进页门禁：未登录跳 SCM 登录（把本页弹出栈防循环）。
        if (!ScmAuthStore.isLoggedIn) {
            navigateToLoginGated(R.id.ScmLoginFragment, R.id.ChatFragment)
            return
        }

        container = view.findViewById(R.id.container_messages)
        scroll = view.findViewById(R.id.scroll_messages)
        etInput = view.findViewById(R.id.et_input)
        btnSend = view.findViewById(R.id.btn_send)
        tvStatus = view.findViewById(R.id.tv_chat_status)
        view.findViewById<TextView>(R.id.tv_chat_title).text = otherNickname.ifBlank { "聊天" }
        tvStatus.text = "连接中…"

        btnSend.setOnClickListener { send() }

        // 边到边下 adjustResize 不生效：自行消费 IME inset，让输入栏随键盘上移；
        // 无键盘时退回导航栏高度，避免输入栏被手势导航条遮挡。
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = maxOf(ime.bottom, nav.bottom))
            if (ime.bottom > nav.bottom) scrollToBottom()
            insets
        }

        loadHistory()
        connectSocket()
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            val msgs = withContext(Dispatchers.IO) {
                runCatching {
                    // 进会话顺带创建/恢复会话并标记已读路径
                    ChatClient.createConversation(otherUserId)
                    ChatClient.history(otherUserId)
                }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            container.removeAllViews()
            msgs.forEach { addBubble(it.content, it.isMine(currentUserId)) }
            scrollToBottom()
        }
    }

    private fun connectSocket() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 登录用户用 chat/ws-ticket 一次性票据连接（替代真实 token 暴露在 URL）；
            // ws 域名按地区动态解析（与官网一致），websocket/temp-token 是给匿名公开页用的，member 不走那条。
            val conn = withContext(Dispatchers.IO) {
                runCatching {
                    val t = ChatClient.wsTicket() ?: return@runCatching null
                    val base = ChatClient.resolveWsBaseUrl()
                    base + ChatSocket.WS_PATH to t
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (conn == null) {
                tvStatus.text = "连接失败"
                return@launch
            }
            socket = ChatSocket(
                otherUserId = otherUserId,
                onMessage = { msg -> onIncoming(msg) },
                onStatus = { ok -> if (isAdded) tvStatus.text = if (ok) "在线" else "已断开" },
            ).also { it.connect(conn.first, conn.second) }
        }
    }

    private fun onIncoming(msg: ChatMessage) {
        // 自己发的已乐观上屏，去重
        if (msg.clientMessageId.isNotEmpty() && sentClientIds.remove(msg.clientMessageId)) return
        // 只处理本会话
        val inThis = (msg.fromUserId == otherUserId && msg.toUserId == currentUserId) ||
            (msg.fromUserId == currentUserId && msg.toUserId == otherUserId)
        if (!inThis) return
        addBubble(msg.content, msg.isMine(currentUserId))
        scrollToBottom()
    }

    private fun send() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        val s = socket
        if (s == null) {
            tvStatus.text = "未连接，稍候重试"
            return
        }
        val clientId = UUID.randomUUID().toString()
        sentClientIds.add(clientId)
        s.sendMessage(text, clientId)
        addBubble(text, mine = true)
        etInput.setText("")
        scrollToBottom()
    }

    private fun addBubble(text: String, mine: Boolean) {
        val ctx = requireContext()
        val bubble = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (mine) Color.parseColor("#001119") else Color.parseColor("#d8eaf2"))
            setBackgroundColor(if (mine) Color.parseColor("#21d4ff") else Color.parseColor("#111d2b"))
            val p = dp(10)
            setPadding(p, dp(8), p, dp(8))
            typeface = Typeface.DEFAULT
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
            gravity = if (mine) Gravity.END else Gravity.START
            marginStart = if (mine) dp(48) else 0
            marginEnd = if (mine) 0 else dp(48)
        }
        val row = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            gravity = if (mine) Gravity.END else Gravity.START
        }
        bubble.layoutParams = lp
        row.addView(bubble)
        container.addView(row)
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        socket?.close()
        socket = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_OTHER_ID = "otherUserId"
        const val ARG_OTHER_NICK = "otherUserNickname"
    }
}
