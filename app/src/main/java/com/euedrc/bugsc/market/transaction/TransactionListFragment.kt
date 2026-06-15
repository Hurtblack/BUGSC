package com.euedrc.bugsc.market.transaction

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import java.text.NumberFormat
import java.util.Locale

class TransactionListFragment : Fragment() {
    private val client = TransactionClient()
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private lateinit var more: Button
    private lateinit var filterButtons: List<Pair<Button, TransactionFilter>>
    private var filter = TransactionFilter.ALL
    private var pageNo = 1
    private var total = 0L
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_transaction_list, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        container = view.findViewById(R.id.container_transactions)
        status = view.findViewById(R.id.tv_transaction_status)
        more = view.findViewById(R.id.btn_transaction_more)
        filterButtons = listOf(
            R.id.btn_filter_all to TransactionFilter.ALL,
            R.id.btn_filter_ongoing to TransactionFilter.ONGOING,
            R.id.btn_filter_completed to TransactionFilter.COMPLETED,
            R.id.btn_filter_cancelled to TransactionFilter.CANCELLED,
        ).map { (id, value) ->
            view.findViewById<Button>(id) to value
        }
        filterButtons.forEach { (button, value) ->
            button.setOnClickListener {
                if (filter == value) return@setOnClickListener
                filter = value
                updateFilterStyle()
                loadFirst()
            }
        }
        more.setOnClickListener { loadPage(pageNo + 1) }
        updateFilterStyle()
        requireScmLogin { loadFirst() }
    }

    private fun updateFilterStyle() {
        val activeBg = ColorStateList.valueOf(resources.getColor(R.color.sc_accent, null))
        val inactiveBg = ColorStateList.valueOf(resources.getColor(R.color.sc_panel2, null))
        val activeText = resources.getColor(R.color.sc_ink, null)
        val inactiveText = resources.getColor(R.color.sc_text_mid, null)
        filterButtons.forEach { (button, value) ->
            val active = value == filter
            button.backgroundTintList = if (active) activeBg else inactiveBg
            button.setTextColor(if (active) activeText else inactiveText)
            button.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun loadFirst() {
        pageNo = 1
        total = 0
        container.removeAllViews()
        loadPage(1)
    }

    private fun loadPage(page: Int) {
        if (loading) return
        loading = true
        status.text = "正在加载..."
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.page(page, transactionStatus = filter.apiStatus) }
            }
            loading = false
            result.onFailure {
                status.text = "加载失败：${it.message ?: "网络错误"}，点击重试"
                status.setOnClickListener { loadPage(page) }
            }.onSuccess { data ->
                pageNo = page
                total = data.total
                data.items.forEach { container.addView(card(it)) }
                status.setOnClickListener(null)
                status.text = if (container.childCount == 0) "暂无交易" else "已加载 ${container.childCount} 条"
                more.visibility = if (container.childCount < total) View.VISIBLE else View.GONE
            }
        }
    }

    private fun card(record: TransactionRecord): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        addView(label(record.itemsName.ifBlank { "交易订单" }, 16f, Color.WHITE))
        addView(label(record.transactionNumber, 11f, resources.getColor(R.color.sc_accent, null)))
        addView(label(
            "${if (record.creatorType == 1) "购买" else "出售"} · 数量 ${record.number} · " +
                "${NumberFormat.getNumberInstance(Locale.US).format(record.amount)} UEC",
            13f,
            resources.getColor(R.color.sc_text_mid, null),
        ))
        addView(label("${record.transactionStatusLabel} · ${record.createTime}", 12f, resources.getColor(R.color.sc_warn, null)))
        setOnClickListener {
            findNavController().navigate(
                R.id.TransactionDetailFragment,
                bundleOf("transactionNumber" to record.transactionNumber),
            )
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
    }
}
