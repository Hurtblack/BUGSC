package com.euedrc.bugsc.market

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.ImageLoader
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class MarketFragment : Fragment() {

    private val client = ScmMarketClient()

    private lateinit var tvTotal: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnTabSell: Button
    private lateinit var btnTabBuy: Button
    private lateinit var tvLoading: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var containerList: LinearLayout
    private lateinit var btnLoadMore: Button
    private lateinit var scrollView: ScrollView

    private var currentTab = TAB_SELL
    private var currentPage = 1
    private var totalCount = 0
    private var isLoading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_market, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AnalyticsTracker.get(requireContext()).trackPageView("market")

        tvTotal = view.findViewById(R.id.tv_total)
        etSearch = view.findViewById(R.id.et_search)
        btnSearch = view.findViewById(R.id.btn_search)
        btnTabSell = view.findViewById(R.id.btn_tab_sell)
        btnTabBuy = view.findViewById(R.id.btn_tab_buy)
        tvLoading = view.findViewById(R.id.tv_loading)
        tvEmpty = view.findViewById(R.id.tv_empty)
        containerList = view.findViewById(R.id.container_list)
        btnLoadMore = view.findViewById(R.id.btn_load_more)
        scrollView = view.findViewById(R.id.scroll_view)

        btnTabSell.setOnClickListener { switchTab(TAB_SELL) }
        btnTabBuy.setOnClickListener { switchTab(TAB_BUY) }
        btnSearch.setOnClickListener { doSearch() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        btnLoadMore.setOnClickListener { loadMore() }

        loadFirstPage()
    }

    private fun switchTab(tab: Int) {
        if (tab == currentTab) return
        currentTab = tab
        updateTabStyle()
        loadFirstPage()
    }

    private fun updateTabStyle() {
        val activeColor = Color.parseColor("#21D4FF")
        val inactiveColor = Color.parseColor("#111D2B")
        val activeText = Color.parseColor("#001119")
        val inactiveText = Color.parseColor("#7C95A8")

        btnTabSell.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (currentTab == TAB_SELL) activeColor else inactiveColor
        )
        btnTabSell.setTextColor(if (currentTab == TAB_SELL) activeText else inactiveText)
        btnTabBuy.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (currentTab == TAB_BUY) activeColor else inactiveColor
        )
        btnTabBuy.setTextColor(if (currentTab == TAB_BUY) activeText else inactiveText)
    }

    private fun doSearch() {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("market", "search")
        loadFirstPage()
    }

    private fun loadFirstPage() {
        currentPage = 1
        containerList.removeAllViews()
        showLoading()
        fetchPage()
    }

    private fun loadMore() {
        currentPage++
        btnLoadMore.text = "加载中…"
        btnLoadMore.isEnabled = false
        fetchPage()
    }

    private fun fetchPage() {
        if (isLoading) return
        isLoading = true
        val keyword = etSearch.text.toString().trim()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.fetchPage(currentTab, currentPage, PAGE_SIZE, keyword) }
            }
            isLoading = false
            result.onSuccess { page ->
                totalCount = page.total
                tvTotal.text = "${totalCount}个"
                if (currentPage == 1 && page.list.isEmpty()) {
                    showEmpty()
                } else {
                    showList()
                    page.list.forEach { order -> containerList.addView(buildOrderCard(order)) }
                    val loaded = containerList.childCount
                    btnLoadMore.visibility = if (loaded < totalCount) View.VISIBLE else View.GONE
                    btnLoadMore.text = "加载更多"
                    btnLoadMore.isEnabled = true
                }
            }.onFailure {
                if (currentPage == 1) showEmpty()
                Toast.makeText(context, "加载失败：${it.message}", Toast.LENGTH_SHORT).show()
                btnLoadMore.text = "加载更多"
                btnLoadMore.isEnabled = true
            }
        }
    }

    private fun buildOrderCard(order: MarketOrder): View {
        val card = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp }
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AnalyticsTracker.get(requireContext()).trackFeatureClick("market", "order_detail")
                findNavController().navigate(
                    R.id.MarketDetailFragment,
                    bundleOf("orderNumber" to order.orderNumber),
                )
            }
        }

        if (order.thumbnailUrl.isNotBlank()) {
            val thumb = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(84.dp, 84.dp).apply { marginEnd = 12.dp }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.card_bg_blue)
            }
            card.addView(thumb)
            ImageLoader.load(this, thumb, order.thumbnailUrlHd.ifBlank { order.thumbnailUrl })
        }

        val textArea = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }

        textArea.addView(cardText(order.itemName, "#d8eaf2", 15f, bold = true))

        val priceRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 4.dp }
            orientation = LinearLayout.HORIZONTAL
        }
        priceRow.addView(cardText("¤ ${formatPrice(order.unitPrice)} aUEC", "#21d4ff", 13f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        priceRow.addView(cardText("×${order.remainingQuantity}", "#7c95a8", 13f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        textArea.addView(priceRow)

        val metaLine = buildList {
            add(order.nickname)
            if (order.locationName.isNotBlank()) add(order.locationName)
        }.joinToString(" · ")
        if (metaLine.isNotBlank()) {
            textArea.addView(cardText(metaLine, "#7c95a8", 11f, topMarginDp = 4))
        }

        card.addView(textArea)
        return card
    }

    private fun formatPrice(price: Double): String =
        NumberFormat.getNumberInstance(Locale.US).format(price.toLong())

    private fun cardText(text: String, color: String, sizeSp: Float, bold: Boolean = false, topMarginDp: Int = 0) =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = topMarginDp.dp }
            this.text = text
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun showLoading() {
        tvLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.GONE
        btnLoadMore.visibility = View.GONE
    }

    private fun showEmpty() {
        tvLoading.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        containerList.visibility = View.GONE
        btnLoadMore.visibility = View.GONE
    }

    private fun showList() {
        tvLoading.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.VISIBLE
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_SELL = 1
        private const val TAB_BUY = 0
        private const val PAGE_SIZE = 10
    }
}
