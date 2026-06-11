package com.euedrc.bugsc.market

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MarketDetailFragment : Fragment() {

    private val client = ScmMarketClient()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_market_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val orderNumber = arguments?.getString("orderNumber") ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val order = withContext(Dispatchers.IO) {
                runCatching { client.fetchDetail(orderNumber) }.getOrNull()
            }
            if (order == null) {
                Toast.makeText(context, "加载订单详情失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            bindOrder(view, order)
        }
    }

    private fun bindOrder(view: View, order: MarketOrder) {
        val ivItem = view.findViewById<ImageView>(R.id.iv_item)
        val tvItemName = view.findViewById<TextView>(R.id.tv_item_name)
        val tvOrderType = view.findViewById<TextView>(R.id.tv_order_type)
        val tvPrice = view.findViewById<TextView>(R.id.tv_price)
        val tvQuantity = view.findViewById<TextView>(R.id.tv_quantity)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_avatar)
        val tvNickname = view.findViewById<TextView>(R.id.tv_nickname)
        val tvUserStatus = view.findViewById<TextView>(R.id.tv_user_status)
        val tvPoint = view.findViewById<TextView>(R.id.tv_point)
        val tvLocation = view.findViewById<TextView>(R.id.tv_location)
        val tvTradeDays = view.findViewById<TextView>(R.id.tv_trade_days)
        val tvTradeTime = view.findViewById<TextView>(R.id.tv_trade_time)
        val containerItems = view.findViewById<LinearLayout>(R.id.container_items)
        val tvCreateTime = view.findViewById<TextView>(R.id.tv_create_time)
        val tvExpireTime = view.findViewById<TextView>(R.id.tv_expire_time)
        val tvRemark = view.findViewById<TextView>(R.id.tv_remark)
        val btnGoMarket = view.findViewById<Button>(R.id.btn_go_market)

        tvItemName.text = order.itemName

        if (order.isSell) {
            tvOrderType.text = "出售"
            tvOrderType.setTextColor(resources.getColor(R.color.sc_ok, null))
        } else {
            tvOrderType.text = "求购"
            tvOrderType.setTextColor(resources.getColor(R.color.sc_warn, null))
        }

        tvPrice.text = "¤ ${formatPrice(order.unitPrice)} aUEC"
        tvQuantity.text = "剩余数量：${order.remainingQuantity}"

        tvNickname.text = order.nickname
        val (statusText, statusColor) = when (order.creatorStatus) {
            1 -> "● 在线" to R.color.sc_ok
            2 -> "● 游戏中" to R.color.sc_warn
            else -> "● 离线" to R.color.sc_text_dim
        }
        tvUserStatus.text = statusText
        tvUserStatus.setTextColor(resources.getColor(statusColor, null))
        tvPoint.text = "信用分 ${order.point}"

        if (order.locationUrl.isNotBlank()) {
            tvLocation.text = "交易地点：${order.locationName} ↗"
            tvLocation.setTextColor(resources.getColor(R.color.sc_accent, null))
            tvLocation.setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(order.locationUrl))) }
            }
        } else {
            tvLocation.text = "交易地点：${order.locationName}"
        }
        formatTradeDays(order.tradeTime)?.let {
            tvTradeDays.text = "交易日：$it"
            tvTradeDays.visibility = View.VISIBLE
        }
        tvTradeTime.text = "交易时间：${order.tradeStartTime} - ${order.tradeEndTime}"

        bindItemDetails(containerItems, order)

        tvCreateTime.text = "发布时间：${formatDate(order.createTime)}"
        tvExpireTime.text = "有效期至：${formatDate(order.expireTime)}"

        if (order.remark.isNotBlank()) {
            tvRemark.text = "备注：${order.remark}"
            tvRemark.visibility = View.VISIBLE
        }

        btnGoMarket.text = if (order.isSell) "前往购买" else "前往出售"
        btnGoMarket.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MARKET_URL)))
            }
        }

        loadImage(ivItem, order.thumbnailUrlHd)
        loadImage(ivAvatar, order.avatar)
    }

    private fun loadImage(iv: ImageView, url: String) {
        if (url.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeStream(URL(url).openStream()) }.getOrNull()
            }
            bitmap?.let { iv.setImageBitmap(it) }
        }
    }

    private fun bindItemDetails(container: LinearLayout, order: MarketOrder) {
        if (order.itemDetails.size <= 1) return
        container.removeAllViews()
        container.addView(TextView(requireContext()).apply {
            text = "包含商品（${order.itemDetails.size}）"
            textSize = 13f
            setTextColor(resources.getColor(R.color.sc_text, null))
        })
        order.itemDetails.forEach { item ->
            val row = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
                text = "${item.itemName}  ×${item.quantity}  ¤ ${formatPrice(item.unitPrice)} aUEC"
                textSize = 12f
                setTextColor(resources.getColor(R.color.sc_text_mid, null))
            }
            container.addView(row)
        }
        container.visibility = View.VISIBLE
    }

    // tradeTime 形如 "[1, 1, 1, 1, 1, 1, 1]"，下标 0-6 对应周一到周日
    private fun formatTradeDays(tradeTime: String): String? {
        val flags = runCatching {
            org.json.JSONArray(tradeTime).let { arr -> (0 until arr.length()).map { arr.getInt(it) == 1 } }
        }.getOrNull() ?: return null
        if (flags.size != 7) return null
        if (flags.all { it }) return "每天"
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val days = names.filterIndexed { i, _ -> flags[i] }
        return if (days.isEmpty()) null else days.joinToString("、")
    }

    private fun formatDate(millis: Long): String =
        if (millis <= 0) "—"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun formatPrice(price: Double): String =
        NumberFormat.getNumberInstance(Locale.US).format(price.toLong())

    companion object {
        private const val MARKET_URL = "https://flowcld.xyz/market"
    }
}
