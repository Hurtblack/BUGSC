package com.euedrc.bugsc.market

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.ImageLoader
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.requireScmLogin
import com.euedrc.bugsc.market.transaction.AddressBranch
import com.euedrc.bugsc.market.transaction.AddressChoice
import com.euedrc.bugsc.market.transaction.AddressTree
import com.euedrc.bugsc.market.transaction.TransactionContractException
import com.euedrc.bugsc.market.transaction.TransactionDraft
import com.euedrc.bugsc.market.transaction.TransactionRules
import com.euedrc.bugsc.ui.ImagePreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MarketDetailFragment : Fragment() {

    private val marketOrders get() = AppServices.marketOrders
    private val transactions get() = AppServices.transactions
    private var selectedLocationId: Long? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_market_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val orderNumber = arguments?.getString("orderNumber") ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val order = withContext(Dispatchers.IO) {
                runCatching { marketOrders.fetchDetail(orderNumber) }.getOrNull()
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
                AnalyticsTracker.get(requireContext()).trackFeatureClick("market_detail", "open_location")
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

        val btnContact = view.findViewById<Button>(R.id.btn_contact)
        val myId = if (AppServices.auth.isLoggedIn()) AppServices.auth.currentUserId() else 0L
        if (order.creatorId <= 0 || (myId != 0L && order.creatorId == myId)) {
            btnContact.visibility = View.GONE
            view.findViewById<View>(R.id.container_transaction_form).visibility = View.GONE
            btnGoMarket.visibility = View.GONE
        } else {
            btnContact.setOnClickListener {
                AnalyticsTracker.get(requireContext()).trackFeatureClick("market_detail", "contact")
                requireScmLogin {
                    findNavController().navigate(
                        R.id.ChatFragment,
                        androidx.core.os.bundleOf(
                            com.euedrc.bugsc.chat.ChatFragment.ARG_OTHER_ID to order.creatorId,
                            com.euedrc.bugsc.chat.ChatFragment.ARG_OTHER_NICK to order.nickname,
                        ),
                    )
                }
            }
            bindTransactionForm(view, order, btnGoMarket)
        }

        val itemImageUrl = order.thumbnailUrlHd.ifBlank { order.thumbnailUrl }
        loadImage(ivItem, itemImageUrl)
        bindImagePreview(ivItem, itemImageUrl)
        loadImage(ivAvatar, order.avatar)
    }

    private fun bindTransactionForm(view: View, order: MarketOrder, submit: Button) {
        val quantity = view.findViewById<EditText>(R.id.et_transaction_quantity)
        val shippingFee = view.findViewById<EditText>(R.id.et_shipping_fee)
        val total = view.findViewById<TextView>(R.id.tv_transaction_total)
        val hint = view.findViewById<TextView>(R.id.tv_trade_window_hint)
        val quantityLabel = view.findViewById<TextView>(R.id.tv_quantity_label)
        quantityLabel.text = "交易数量（最多 ${order.remainingQuantity} 个）"

        fun updateTotal() {
            val count = quantity.text.toString().toIntOrNull() ?: 0
            val amount = TransactionRules.total(BigDecimal.valueOf(order.unitPrice), count)
            total.text = "交易总额（UEC）：${NumberFormat.getNumberInstance(Locale.US).format(amount)}"
        }
        quantity.addTextChangedListener { updateTotal() }
        updateTotal()

        val open = currentTradeWindow(order)
        hint.text = when (open) {
            true -> "当前在交易时间内"
            false -> "当前不在创建者交易时间内，建议先联系交易者"
            null -> "建议先确认创建者交易时间后再提交"
        }
        hint.setTextColor(resources.getColor(if (open == true) R.color.sc_ok else R.color.sc_warn, null))

        loadAddressSpinners(view)
        submit.text = if (order.isSell) "创建购买交易" else "创建出售交易"
        submit.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("market_detail", "create_transaction")
            requireScmLogin {
                val validation = TransactionRules.validate(
                    quantityText = quantity.text.toString(),
                    maxQuantity = order.remainingQuantity,
                    locationId = selectedLocationId,
                    shippingFeeText = shippingFee.text.toString(),
                )
                quantity.error = validation.quantityError
                shippingFee.error = validation.shippingFeeError
                if (validation.locationError != null) {
                    view.findViewById<TextView>(R.id.tv_selected_location).apply {
                        text = validation.locationError
                        setTextColor(resources.getColor(R.color.sc_danger, null))
                    }
                }
                if (!validation.isValid) return@requireScmLogin
                createTransaction(
                    order = order,
                    draft = TransactionDraft(
                        orderNumber = order.orderNumber,
                        number = validation.quantity!!,
                        locationId = selectedLocationId!!,
                        shippingFee = validation.shippingFee!!,
                    ),
                    submit = submit,
                )
            }
        }
    }

    private fun loadAddressSpinners(view: View) {
        val system = view.findViewById<Spinner>(R.id.spinner_system)
        val planet = view.findViewById<Spinner>(R.id.spinner_planet)
        val station = view.findViewById<Spinner>(R.id.spinner_station)
        val selected = view.findViewById<TextView>(R.id.tv_selected_location)
        selectedLocationId = null
        showLocationPrompt(selected, "请选择收货位置")
        bindSpinner(planet, "请先选择星系", emptyList()) {}
        bindSpinner(station, "请先选择星体", emptyList()) {}
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AddressTree.build(transactions.addressList()) }
            }
            result.onFailure {
                selectedLocationId = null
                selected.text = "收货位置加载失败，点击重试"
                selected.setTextColor(resources.getColor(R.color.sc_danger, null))
                selected.setOnClickListener { loadAddressSpinners(view) }
            }.onSuccess { tree ->
                selected.setOnClickListener(null)
                bindSpinner(system, "请选择星系", tree.roots) { systemBranch ->
                    selectedLocationId = null
                    bindSpinner(station, "请先选择星体", emptyList()) {}
                    if (systemBranch.children.isEmpty()) {
                        selectedLocationId = systemBranch.node.id
                        showSelectedLocation(selected, systemBranch)
                    } else {
                        showLocationPrompt(selected, "请选择星体")
                    }
                    bindSpinner(planet, "请选择星体", systemBranch.children) { planetBranch ->
                        selectedLocationId = null
                        if (planetBranch.children.isEmpty()) {
                            selectedLocationId = planetBranch.node.id
                            showSelectedLocation(selected, planetBranch)
                        } else {
                            showLocationPrompt(selected, "请选择站点")
                        }
                        bindSpinner(station, "请选择站点", planetBranch.children) { stationBranch ->
                            selectedLocationId = stationBranch.node.id
                            showSelectedLocation(selected, stationBranch)
                        }
                    }
                }
            }
        }
    }

    private fun showLocationPrompt(selected: TextView, text: String) {
        selected.text = text
        selected.setTextColor(resources.getColor(R.color.sc_text_dim, null))
    }

    private fun showSelectedLocation(selected: TextView, branch: AddressBranch) {
        selected.text = branch.node.name
        selected.setTextColor(resources.getColor(R.color.sc_text, null))
    }

    private fun bindSpinner(
        spinner: Spinner,
        prompt: String,
        branches: List<AddressBranch>,
        onSelected: (AddressBranch) -> Unit,
    ) {
        val choices = AddressChoice.withPrompt(prompt, branches)
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            choices.map { it.label },
        )
        spinner.isEnabled = branches.isNotEmpty()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                choices.getOrNull(position)?.branch?.let(onSelected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun createTransaction(order: MarketOrder, draft: TransactionDraft, submit: Button) {
        submit.isEnabled = false
        submit.text = "正在提交..."
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (transactions.checkOngoing(order.orderNumber)) {
                        val existing = transactions.page(1, orderNumber = order.orderNumber).items.firstOrNull()
                        ExistingTransaction(existing?.transactionNumber.orEmpty())
                    } else {
                        CreatedTransaction(transactions.create(draft).identifier)
                    }
                }
            }
            submit.isEnabled = true
            submit.text = if (order.isSell) "创建购买交易" else "创建出售交易"
            result.onFailure {
                if (it is TransactionContractException) {
                    showTransactionDialog(
                        title = "交易已提交",
                        message = it.message ?: "后台未返回交易编号，请前往“我的交易”查看",
                        transactionNumber = "",
                    )
                } else {
                    Toast.makeText(requireContext(), it.message ?: "创建交易失败", Toast.LENGTH_LONG).show()
                }
            }.onSuccess { outcome ->
                when (outcome) {
                    is ExistingTransaction -> showTransactionDialog(
                        title = "已有进行中的交易",
                        message = if (outcome.number.isBlank()) {
                            "该订单已有进行中的交易，无需重复创建。请前往“我的交易”查看"
                        } else {
                            "该订单已有进行中的交易，无需重复创建。\n交易编号：${outcome.number}"
                        },
                        transactionNumber = outcome.number,
                    )
                    is CreatedTransaction -> showTransactionDialog(
                        title = "交易创建成功",
                        message = "交易编号：${outcome.number}",
                        transactionNumber = outcome.number,
                    )
                }
            }
        }
    }

    private fun showTransactionDialog(title: String, message: String, transactionNumber: String) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("留在当前页", null)
        if (transactionNumber.isNotBlank()) {
            builder.setPositiveButton("查看详情") { _, _ ->
                AnalyticsTracker.get(requireContext()).trackFeatureClick("market_detail", "open_transaction_detail")
                findNavController().navigate(
                    R.id.TransactionDetailFragment,
                    bundleOf("transactionNumber" to transactionNumber),
                )
            }
        } else {
            builder.setPositiveButton("我的交易") { _, _ ->
                AnalyticsTracker.get(requireContext()).trackFeatureClick("market_detail", "open_transactions")
                findNavController().navigate(R.id.TransactionListFragment)
            }
        }
        builder.show()
    }

    private fun currentTradeWindow(order: MarketOrder): Boolean? {
        val days = runCatching {
            val array = JSONArray(order.tradeTime)
            (0 until array.length()).map { array.optInt(it) == 1 }
        }.getOrNull() ?: return null
        return runCatching {
            val now = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"))
            val dayIndex = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
            com.euedrc.bugsc.market.transaction.TradeWindow.isOpen(
                days,
                order.tradeStartTime,
                order.tradeEndTime,
                dayIndex,
                now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE),
            )
        }.getOrNull()
    }

    private fun loadImage(iv: ImageView, url: String) {
        if (url.isBlank()) return
        ImageLoader.load(this, iv, url)
    }

    private fun bindImagePreview(iv: ImageView, url: String) {
        if (url.isBlank()) {
            iv.isClickable = false
            iv.setOnClickListener(null)
            return
        }
        iv.isClickable = true
        iv.isFocusable = true
        iv.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("market_detail", "preview_image")
            ImagePreviewDialog.show(this, listOf(url))
        }
    }

    private fun bindItemDetails(container: LinearLayout, order: MarketOrder) {
        if (order.itemDetails.isEmpty()) return
        container.removeAllViews()
        container.addView(TextView(requireContext()).apply {
            text = if (order.itemDetails.size == 1) "商品详情" else "包含商品（${order.itemDetails.size}）"
            textSize = 13f
            setTextColor(resources.getColor(R.color.sc_text, null))
        })
        order.itemDetails.forEach { item ->
            val row = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
                val quality = item.quality?.let { "  品质 $it" }.orEmpty()
                text = "${item.itemName}  ×${item.quantity}  ¤ ${formatPrice(item.unitPrice)} aUEC$quality"
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

    private sealed interface CreateOutcome
    private data class CreatedTransaction(val number: String) : CreateOutcome
    private data class ExistingTransaction(val number: String) : CreateOutcome
}
