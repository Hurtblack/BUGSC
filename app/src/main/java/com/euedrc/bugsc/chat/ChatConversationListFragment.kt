package com.euedrc.bugsc.chat

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.requireScmLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatConversationListFragment : Fragment() {
    private lateinit var container: LinearLayout
    private lateinit var status: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat_conversations, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        container = view.findViewById(R.id.container_conversations)
        status = view.findViewById(R.id.tv_conversation_status)
        requireScmLogin { }
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized && com.euedrc.bugsc.scm.ScmAuthStore.isLoggedIn) {
            load()
        }
    }

    private fun load() {
        status.text = "正在加载..."
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conversations = ChatClient.conversations()
                    ChatUnreadStore.refresh()
                    conversations
                }
            }
            result.onFailure {
                status.text = "加载失败：${it.message ?: "网络错误"}，点击重试"
                status.setOnClickListener { load() }
            }.onSuccess { conversations ->
                container.removeAllViews()
                conversations.forEach { container.addView(card(it)) }
                status.setOnClickListener(null)
                status.text = if (conversations.isEmpty()) "暂无消息" else "${conversations.size} 个会话"
            }
        }
    }

    private fun card(conversation: ChatConversation): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        val dp = resources.displayMetrics.density
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (8 * dp).toInt() }

        val title = if (conversation.unreadCount > 0) {
            "${conversation.otherUserNickname}  (${conversation.unreadCount})"
        } else {
            conversation.otherUserNickname
        }
        addView(text(title.ifBlank { "SCM 用户" }, 15f, Color.WHITE))
        addView(text(
            conversation.lastMessageContent.ifBlank { "暂无消息" },
            13f,
            resources.getColor(R.color.sc_text_mid, null),
        ))
        addView(text(
            "${onlineLabel(conversation.otherUserSignInStatus)} · " +
                ChatProfileFormatter.messageTime(conversation.lastMessageTime),
            11f,
            resources.getColor(R.color.sc_text_dim, null),
        ))
        setOnClickListener { openConversation(conversation) }
    }

    private fun openConversation(conversation: ChatConversation) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ChatClient.markRead(conversation.id) }
                runCatching { ChatUnreadStore.refresh() }
            }
            if (!isAdded) return@launch
            findNavController().navigate(
                R.id.ChatFragment,
                bundleOf(
                    ChatFragment.ARG_OTHER_ID to conversation.otherUserId,
                    ChatFragment.ARG_OTHER_NICK to conversation.otherUserNickname,
                ),
            )
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun onlineLabel(status: Int): String = when (status) {
        1 -> "在线"
        2 -> "游戏中"
        else -> "离线"
    }
}
