package com.euedrc.bugsc.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.navigateToLoginGated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 一对一聊天：历史 + WebSocket 实时收发。参数 otherUserId / otherUserNickname。 */
class ChatFragment : Fragment() {

    private val otherUserId: Long by lazy { arguments?.getLong(ARG_OTHER_ID) ?: 0L }
    private val otherNickname: String by lazy { arguments?.getString(ARG_OTHER_NICK).orEmpty() }
    private val currentUserId: Long by lazy { AppServices.auth.currentUserId() }

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var ivProfileAvatar: ImageView
    private lateinit var tvTradeTime: TextView
    private lateinit var tvContact: TextView

    private var socket: ChatSocket? = null
    private val sentClientIds = HashSet<String>()
    private var publicProfile: ChatPublicProfile? = null
    private var conversationName = ""
    private var conversationAvatar = ""
    private var avatarBitmap: Bitmap? = null
    private val avatarViews = mutableListOf<ImageView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 进页门禁：未登录跳 SCM 登录（把本页弹出栈防循环）。
        if (!AppServices.auth.isLoggedIn()) {
            navigateToLoginGated(R.id.ScmLoginFragment, R.id.ChatFragment)
            return
        }

        container = view.findViewById(R.id.container_messages)
        scroll = view.findViewById(R.id.scroll_messages)
        etInput = view.findViewById(R.id.et_input)
        btnSend = view.findViewById(R.id.btn_send)
        tvStatus = view.findViewById(R.id.tv_chat_status)
        ivProfileAvatar = view.findViewById(R.id.iv_chat_avatar)
        tvTradeTime = view.findViewById(R.id.tv_chat_trade_time)
        tvContact = view.findViewById(R.id.tv_chat_contact)
        view.findViewById<TextView>(R.id.tv_chat_title).text = otherNickname.ifBlank { "聊天" }
        tvStatus.text = "连接中…"

        btnSend.setOnClickListener {
            track("send")
            send()
        }

        // 边到边下 adjustResize 不生效：自行消费 IME inset，让输入栏随键盘上移；
        // 无键盘时退回导航栏高度，避免输入栏被手势导航条遮挡。
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = maxOf(ime.bottom, nav.bottom))
            if (ime.bottom > nav.bottom) scrollToBottom()
            insets
        }

        loadPublicProfile(view)
        loadHistory()
        connectSocket()
    }

    private fun loadPublicProfile(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ChatClient.publicProfile(otherUserId) }
            }
            result.onFailure {
                tvTradeTime.text = "交易资料加载失败"
                tvContact.text = "联系方式：未公开"
                tvContact.setTextColor(resources.getColor(R.color.sc_text_dim, null))
            }.onSuccess { profile ->
                publicProfile = profile
                view.findViewById<TextView>(R.id.tv_chat_title).text =
                    ChatProfileFormatter.displayName(
                        conversationName,
                        profile.nickname,
                        otherNickname,
                    ).ifBlank { "聊天" }
                tvTradeTime.text = ChatProfileFormatter.tradeSchedule(
                    profile.tradeDays,
                    profile.tradeStartTime,
                    profile.tradeEndTime,
                )
                tvContact.text = ChatProfileFormatter.contact(profile.contact)
                if (profile.contact.isBlank()) {
                    tvContact.setTextColor(resources.getColor(R.color.sc_text_dim, null))
                    tvContact.setOnClickListener(null)
                } else {
                    tvContact.setTextColor(resources.getColor(R.color.sc_accent, null))
                    tvContact.setOnClickListener {
                        track("copy_contact")
                        copyContact(profile.contact)
                    }
                }
                loadAvatar(conversationAvatar.ifBlank { profile.avatar })
            }
        }
    }

    private fun copyContact(contact: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("联系方式", contact))
        Toast.makeText(requireContext(), "联系方式已复制", Toast.LENGTH_SHORT).show()
    }

    private fun loadAvatar(url: String) {
        if (url.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeStream(URL(url).openStream()) }.getOrNull()
            } ?: return@launch
            avatarBitmap = bitmap
            ivProfileAvatar.setImageBitmap(bitmap)
            avatarViews.forEach { it.setImageBitmap(bitmap) }
        }
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 进会话顺带创建/恢复会话并标记已读路径
                    val conversation = ChatClient.createConversation(otherUserId)
                    conversation?.let { ChatClient.markRead(it.id) }
                    runCatching { ChatUnreadStore.refresh() }
                    conversation to ChatClient.history(otherUserId)
                }.getOrDefault(null to emptyList())
            }
            if (!isAdded) return@launch
            result.first?.let { conversation ->
                conversationName = conversation.otherUserNickname
                conversationAvatar = conversation.otherUserAvatar
                view?.findViewById<TextView>(R.id.tv_chat_title)?.text =
                    ChatProfileFormatter.displayName(
                        conversationName,
                        publicProfile?.nickname.orEmpty(),
                        otherNickname,
                    ).ifBlank { "聊天" }
                if (avatarBitmap == null) {
                    loadAvatar(conversationAvatar.ifBlank { publicProfile?.avatar.orEmpty() })
                }
            }
            container.removeAllViews()
            avatarViews.clear()
            result.second.forEach {
                addBubble(
                    text = it.content,
                    mine = it.isMine(currentUserId),
                    senderName = it.fromUserNickname,
                    avatarUrl = it.fromUserAvatar,
                    time = ChatProfileFormatter.messageTime(it.createTime),
                )
            }
            scrollToBottom()
        }
    }

    private fun connectSocket() {
        viewLifecycleOwner.lifecycleScope.launch {
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
        addBubble(
            text = msg.content,
            mine = msg.isMine(currentUserId),
            senderName = msg.fromUserNickname,
            avatarUrl = msg.fromUserAvatar,
            time = ChatProfileFormatter.messageTime(msg.createTime),
        )
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
        addBubble(
            text = text,
            mine = true,
            senderName = "",
            avatarUrl = "",
            time = "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())} · 已发送",
        )
        etInput.setText("")
        scrollToBottom()
    }

    private fun addBubble(
        text: String,
        mine: Boolean,
        senderName: String,
        avatarUrl: String,
        time: String,
    ) {
        val ctx = requireContext()
        val bubble = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (mine) Color.parseColor("#001119") else Color.parseColor("#d8eaf2"))
            setBackgroundResource(if (mine) R.drawable.chat_bubble_outgoing else R.drawable.chat_bubble_incoming)
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
        val messageColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (!mine) {
            messageColumn.addView(TextView(ctx).apply {
                this.text = senderName.ifBlank {
                    ChatProfileFormatter.displayName(
                        conversationName,
                        publicProfile?.nickname.orEmpty(),
                        otherNickname,
                    )
                }.ifBlank { "SCM 用户" }
                textSize = 11f
                setTextColor(resources.getColor(R.color.sc_text_mid, null))
                setPadding(dp(4), 0, 0, dp(3))
            })
        }
        bubble.layoutParams = lp
        messageColumn.addView(bubble)
        messageColumn.addView(TextView(ctx).apply {
            this.text = time
            textSize = 10f
            gravity = if (mine) Gravity.END else Gravity.START
            setTextColor(resources.getColor(R.color.sc_text_dim, null))
            setPadding(dp(4), dp(3), dp(4), 0)
        })

        val row = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            gravity = if (mine) Gravity.END else Gravity.START
            orientation = LinearLayout.HORIZONTAL
        }
        if (!mine) {
            val avatar = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(8)
                }
                setBackgroundResource(R.drawable.avatar_placeholder)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                avatarBitmap?.let(::setImageBitmap)
            }
            avatarViews += avatar
            row.addView(avatar)
            if (avatarBitmap == null && avatarUrl.isNotBlank() && publicProfile == null) {
                loadAvatar(avatarUrl)
            }
        }
        row.addView(messageColumn)
        container.addView(row)
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("chat", feature)
    }

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
