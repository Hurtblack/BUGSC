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
        val tvPoint = view.findViewById<TextView>(R.id.tv_point)
        val tvLocation = view.findViewById<TextView>(R.id.tv_location)
        val tvTradeTime = view.findViewById<TextView>(R.id.tv_trade_time)
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

        tvPrice.text = "¤ ${formatPrice(order.unitPrice)}"
        tvQuantity.text = "剩余数量：${order.remainingQuantity}"

        tvNickname.text = order.nickname
        tvPoint.text = "信用分 ${order.point}"

        tvLocation.text = "交易地点：${order.locationName}"
        tvTradeTime.text = "交易时间：${order.tradeStartTime} - ${order.tradeEndTime}"

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

    private fun formatPrice(price: Double): String =
        NumberFormat.getNumberInstance(Locale.US).format(price.toLong())

    companion object {
        private const val MARKET_URL = "https://flowcld.xyz/market"
    }
}
