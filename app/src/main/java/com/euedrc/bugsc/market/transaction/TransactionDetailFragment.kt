package com.euedrc.bugsc.market.transaction

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.requireScmLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class TransactionDetailFragment : Fragment() {
    private val transactions get() = AppServices.transactions

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_transaction_detail, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val transactionNumber = arguments?.getString("transactionNumber").orEmpty()
        if (transactionNumber.isBlank()) {
            view.findViewById<TextView>(R.id.tv_transaction_detail_status).text = "缺少交易编号"
            return
        }
        requireScmLogin { load(view, transactionNumber) }
    }

    private fun load(view: View, transactionNumber: String) {
        val status = view.findViewById<TextView>(R.id.tv_transaction_detail_status)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { transactions.get(transactionNumber) } }
            result.onFailure { status.text = "加载失败：${it.message ?: "网络错误"}" }
                .onSuccess { record ->
                    if (record == null) {
                        status.text = "未找到交易详情"
                        return@onSuccess
                    }
                    status.visibility = View.GONE
                    view.findViewById<View>(R.id.container_transaction_detail).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.tv_transaction_item).text = record.itemsName.ifBlank { "交易订单" }
                    view.findViewById<TextView>(R.id.tv_transaction_number).text = record.transactionNumber
                    view.findViewById<TextView>(R.id.tv_transaction_type).text =
                        "订单类型：${if (record.creatorType == 1) "出售" else "求购"}"
                    view.findViewById<TextView>(R.id.tv_transaction_parties).text =
                        "卖家：${record.orderOwnerName}  ·  买家：${record.tradingerName}"
                    view.findViewById<TextView>(R.id.tv_transaction_amount).text =
                        "数量：${record.number}\n交易金额：${format(record.amount)} UEC\n运费：${format(record.shippingFee)} UEC"
                    view.findViewById<TextView>(R.id.tv_transaction_location).text =
                        "收货位置：${record.transactionLocationName.ifBlank { record.locationName }}\n收货方式：${record.deliveryMethodLabel}"
                    view.findViewById<TextView>(R.id.tv_transaction_state).text =
                        "交易状态：${record.transactionStatusLabel}  ·  发货状态：${deliveryStatus(record.deliveryStatus)}"
                    view.findViewById<TextView>(R.id.tv_transaction_dates).text = listOf(
                        record.createTime.takeIf(String::isNotBlank)?.let { "创建时间：$it" },
                        record.completionTime.takeIf(String::isNotBlank)?.let { "完成时间：$it" },
                        record.cancellationTime.takeIf(String::isNotBlank)?.let { "取消时间：$it" },
                        record.receiptDeadline.takeIf(String::isNotBlank)?.let { "收货截止：$it" },
                    ).filterNotNull().joinToString("\n")
                    bindActions(view, record)
                }
        }
    }

    private fun bindActions(view: View, record: TransactionRecord) {
        val container = view.findViewById<LinearLayout>(R.id.container_transaction_actions)
        val cancel = view.findViewById<Button>(R.id.btn_transaction_cancel)
        val deliver = view.findViewById<Button>(R.id.btn_transaction_deliver)
        cancel.visibility = View.GONE
        deliver.visibility = View.GONE
        val currentUserId = if (AppServices.auth.isLoggedIn()) AppServices.auth.currentUserId() else 0L
        val actions = TransactionActionPolicy.visibleActions(record, currentUserId)
        container.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE

        if (TransactionAction.CANCEL in actions) {
            cancel.visibility = View.VISIBLE
            cancel.setOnClickListener {
                track("cancel_transaction")
                confirmStatusUpdate(
                    view = view,
                    title = "取消交易",
                    message = "确定取消这笔交易？",
                    transactionNumber = record.transactionNumber,
                    deliveryStatus = DELIVERY_CANCELLED,
                    successMessage = "交易已取消",
                )
            }
        }
        if (TransactionAction.DELIVER in actions) {
            deliver.visibility = View.VISIBLE
            deliver.setOnClickListener {
                track("deliver_transaction")
                confirmStatusUpdate(
                    view = view,
                    title = "交付货物",
                    message = "确认你已经完成线下交付？",
                    transactionNumber = record.transactionNumber,
                    deliveryStatus = DELIVERY_DELIVERED,
                    successMessage = "已标记为交付货物",
                )
            }
        }
    }

    private fun confirmStatusUpdate(
        view: View,
        title: String,
        message: String,
        transactionNumber: String,
        deliveryStatus: Int,
        successMessage: String,
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("再想想", null)
            .setPositiveButton("确认") { _, _ ->
                updateStatus(view, transactionNumber, deliveryStatus, successMessage)
            }
            .show()
    }

    private fun updateStatus(view: View, transactionNumber: String, deliveryStatus: Int, successMessage: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { transactions.updateStatus(transactionNumber, deliveryStatus) }
            }
            result.onFailure {
                Toast.makeText(requireContext(), it.message ?: "状态更新失败", Toast.LENGTH_LONG).show()
            }.onSuccess {
                Toast.makeText(requireContext(), successMessage, Toast.LENGTH_SHORT).show()
                load(view, transactionNumber)
            }
        }
    }

    private fun format(value: java.math.BigDecimal): String =
        NumberFormat.getNumberInstance(Locale.US).format(value)

    private fun deliveryStatus(value: Int): String = when (value) {
        0 -> "待同意"
        1 -> "待发货"
        2 -> "已发货"
        3 -> "已收货"
        4 -> "已取消"
        else -> "未知"
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("transaction_detail", feature)
    }

    private companion object {
        const val DELIVERY_DELIVERED = 2
        const val DELIVERY_CANCELLED = 4
    }
}
