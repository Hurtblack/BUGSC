package com.euedrc.bugsc.market.publish

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.requireScmLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

class MyMarketOrdersFragment : Fragment() {
    private val marketPublish get() = AppServices.marketPublish
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var loadMore: Button
    private var filter: PublishCreatorType? = null
    private var pageNo = 1
    private var total = 0L
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_my_market_orders, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        requireScmLogin { bind(view) }
    }

    private fun bind(view: View) {
        status = view.findViewById(R.id.tv_my_orders_status)
        list = view.findViewById(R.id.container_my_orders)
        loadMore = view.findViewById(R.id.btn_my_orders_load_more)
        val all = view.findViewById<Button>(R.id.btn_my_order_all)
        val sell = view.findViewById<Button>(R.id.btn_my_order_sell)
        val buy = view.findViewById<Button>(R.id.btn_my_order_buy)
        fun switch(next: PublishCreatorType?) {
            filter = next
            paintFilters(all, sell, buy)
            loadFirst()
        }
        all.setOnClickListener {
            track("filter_all")
            switch(null)
        }
        sell.setOnClickListener {
            track("filter_sell")
            switch(PublishCreatorType.SELL)
        }
        buy.setOnClickListener {
            track("filter_buy")
            switch(PublishCreatorType.BUY)
        }
        loadMore.setOnClickListener {
            track("load_more")
            loadNext()
        }
        paintFilters(all, sell, buy)
        loadFirst()
    }

    private fun paintFilters(all: Button, sell: Button, buy: Button) {
        listOf(all to null, sell to PublishCreatorType.SELL, buy to PublishCreatorType.BUY).forEach { (button, type) ->
            val active = filter == type
            button.backgroundTintList = ColorStateList.valueOf(resources.getColor(if (active) R.color.sc_accent else R.color.sc_panel2, null))
            button.setTextColor(resources.getColor(if (active) R.color.sc_ink else R.color.sc_text_mid, null))
        }
    }

    private fun loadFirst() {
        pageNo = 1
        total = 0
        list.removeAllViews()
        status.text = "加载中…"
        loadMore.visibility = View.GONE
        fetch()
    }

    private fun loadNext() {
        pageNo += 1
        loadMore.isEnabled = false
        loadMore.text = "加载中…"
        fetch()
    }

    private fun fetch() {
        if (loading) return
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { marketPublish.ownOrders(pageNo, creatorType = filter) }
            }
            loading = false
            loadMore.isEnabled = true
            loadMore.text = "加载更多"
            result.onFailure {
                status.text = "加载失败：${it.message ?: "网络错误"}"
                status.setTextColor(resources.getColor(R.color.sc_danger, null))
            }.onSuccess { page ->
                total = page.total
                status.text = if (page.total == 0L) "暂无挂单" else "共 ${page.total} 个挂单"
                status.setTextColor(resources.getColor(R.color.sc_text_dim, null))
                page.items.forEach { list.addView(orderCard(it)) }
                loadMore.visibility = if (list.childCount < total) View.VISIBLE else View.GONE
            }
        }
    }

    private fun orderCard(order: OwnMarketOrder): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 10.dp }
            isClickable = true
            setOnClickListener {
                track("open_order_detail")
                findNavController().navigate(R.id.MarketDetailFragment, bundleOf("orderNumber" to order.orderNumber))
            }
            addView(TextView(requireContext()).apply {
                text = order.itemName.ifBlank { order.orderNumber }
                textSize = 16f
                setTextColor(resources.getColor(R.color.sc_text, null))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(requireContext()).apply {
                text = "${order.creatorType.label} · ${order.status.label} · ×${order.remainingQuantity} · ¤ ${format(order.unitPrice)}"
                textSize = 12f
                setTextColor(resources.getColor(R.color.sc_text_mid, null))
            })
            addView(TextView(requireContext()).apply {
                text = order.orderNumber
                textSize = 11f
                setTextColor(resources.getColor(R.color.sc_accent, null))
            })
            addView(actionRow(order))
        }

    private fun actionRow(order: OwnMarketOrder): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addAction(this, "编辑") {
                track("edit_order")
                showEditDialog(order)
            }
            addAction(this, if (order.status == PublishOrderStatus.VISIBLE) "下架" else "上架") {
                track(if (order.status == PublishOrderStatus.VISIBLE) "hide_order" else "show_order")
                mutate("状态已更新") {
                    marketPublish.setOrderStatus(
                        order.orderNumber,
                        if (order.status == PublishOrderStatus.VISIBLE) PublishOrderStatus.HIDDEN else PublishOrderStatus.VISIBLE,
                    )
                }
            }
            addAction(this, "补数量") {
                track("add_quantity")
                mutate("已补数量") { marketPublish.quantityAdd(order.orderNumber) }
            }
            addAction(this, "删除", danger = true) {
                track("delete_order")
                confirmDelete(order)
            }
        }

    private fun addAction(parent: LinearLayout, label: String, danger: Boolean = false, action: () -> Unit) {
        parent.addView(Button(requireContext()).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(resources.getColor(if (danger) R.color.sc_danger else R.color.sc_accent, null))
            backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.sc_panel2, null))
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { marginEnd = 4.dp })
    }

    private fun showEditDialog(order: OwnMarketOrder) {
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val price = EditText(requireContext()).apply {
            hint = "单价"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(order.unitPrice.toPlainString())
        }
        val qty = EditText(requireContext()).apply {
            hint = "数量"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(order.remainingQuantity.toString())
        }
        val quality = EditText(requireContext()).apply {
            hint = "品质 0-1000（可选）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val expire = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                PublishExpireTime.entries.map { it.label },
            )
        }
        box.addView(price)
        box.addView(qty)
        box.addView(quality)
        box.addView(expire)
        AlertDialog.Builder(requireContext())
            .setTitle("编辑挂单")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val nextPrice = price.text.toString().toBigDecimalOrNull()
                val nextQty = qty.text.toString().toIntOrNull()
                val nextQuality = quality.text.toString().trim().takeIf(String::isNotBlank)?.toIntOrNull()
                if (nextPrice == null || nextPrice <= BigDecimal.ZERO || nextQty == null || nextQty <= 0) {
                    toast("请输入有效单价和数量")
                    return@setPositiveButton
                }
                if (nextQuality != null && nextQuality !in 0..1000) {
                    toast("品质必须在 0 到 1000 之间")
                    return@setPositiveButton
                }
                mutate("挂单已更新") {
                    marketPublish.updateOrder(
                        orderNumber = order.orderNumber,
                        unitPrice = nextPrice,
                        remainingQuantity = nextQty,
                        status = order.status,
                        expireTime = PublishExpireTime.entries[expire.selectedItemPosition.coerceIn(0, PublishExpireTime.entries.lastIndex)],
                        quality = nextQuality,
                    )
                }
            }
            .show()
    }

    private fun confirmDelete(order: OwnMarketOrder) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除挂单")
            .setMessage("确定删除 ${order.itemName.ifBlank { order.orderNumber }}？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> mutate("挂单已删除") { marketPublish.deleteOrder(order.orderNumber) } }
            .show()
    }

    private fun mutate(success: String, block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            result.onFailure { Toast.makeText(requireContext(), it.message ?: "操作失败", Toast.LENGTH_LONG).show() }
                .onSuccess {
                    toast(success)
                    loadFirst()
                }
        }
    }

    private fun format(value: BigDecimal): String =
        NumberFormat.getNumberInstance(Locale.US).format(value)

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("my_market_orders", feature)
    }
}
