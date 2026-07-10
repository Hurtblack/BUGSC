package com.euedrc.bugsc.news

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.ui.ImagePreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NewsFragment : Fragment() {

    private lateinit var repo: NewsRepository
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPaging: TextView
    private lateinit var container: LinearLayout
    private lateinit var scrollView: ScrollView

    private val pagination = NewsPaginationState()
    private val carousels = mutableListOf<NewsImageCarousel>()
    private val marqueeTitles = mutableListOf<SlowMarqueeTextView>()
    private var currentQuery = ""
    private var requestGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_news, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = NewsRepository(requireContext())
        etSearch = view.findViewById(R.id.et_news_search)
        btnSearch = view.findViewById(R.id.btn_news_search)
        tvStatus = view.findViewById(R.id.tv_news_status)
        tvPaging = view.findViewById(R.id.tv_news_paging)
        container = view.findViewById(R.id.container_news_list)
        scrollView = view.findViewById(R.id.scroll_news)

        btnSearch.setOnClickListener { submitSearch() }
        etSearch.setOnEditorActionListener { _, _, event ->
            if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                submitSearch()
                true
            } else {
                false
            }
        }
        tvPaging.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("news", "load_more")
            if (!pagination.isLoading) loadNextPage()
        }
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = scrollView.getChildAt(0)?.height ?: return@setOnScrollChangeListener
            if (pagination.canAutoLoad &&
                contentHeight - scrollView.height - scrollY <= dp(240)
            ) {
                loadNextPage()
            }
        }

        val cached = repo.loadCachedFirstPage()
        if (cached != null && cached.items.isNotEmpty()) {
            pagination.seedFirstPage(cached.items, Int.MAX_VALUE)
            renderItems(cached.items, replace = true)
        } else {
            showMainStatus("正在拉取最新资讯...")
        }
        loadFirstPage()
    }

    override fun onStart() {
        super.onStart()
        carousels.forEach(NewsImageCarousel::resume)
        marqueeTitles.forEach(SlowMarqueeTextView::resumeSlowScroll)
    }

    override fun onStop() {
        carousels.forEach(NewsImageCarousel::pause)
        marqueeTitles.forEach(SlowMarqueeTextView::pauseSlowScroll)
        super.onStop()
    }

    override fun onDestroyView() {
        carousels.forEach(NewsImageCarousel::pause)
        carousels.clear()
        marqueeTitles.clear()
        super.onDestroyView()
    }

    private fun loadFirstPage() {
        val generation = ++requestGeneration
        val query = currentQuery
        pagination.reset(Int.MAX_VALUE)
        val page = pagination.beginNextPage() ?: return
        if (container.childCount == 0) showMainStatus(
            if (query.isEmpty()) "正在拉取最新资讯..." else "正在搜索“$query”..."
        )
        showPagingStatus("正在加载...", clickable = false)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pageInfo = repo.fetchPageInfo(query)
                    repo.fetchPage(pageNumber = page, searchText = query) to pageInfo
                }
            }
            if (generation != requestGeneration) return@launch
            result.onSuccess { (items, pageInfo) ->
                pagination.updateTotalPages(pageInfo.totalPages)
                val uniqueItems = pagination.complete(page, items)
                if (query.isEmpty()) repo.saveFirstPage(items)
                renderItems(uniqueItems, replace = true)
                updatePagingStatus()
                scrollView.post(::maybeLoadMore)
            }.onFailure {
                pagination.fail(page)
                if (container.childCount == 0) {
                    showMainStatus("拉取失败：${it.message ?: "网络错误"}")
                }
                showPagingStatus("加载失败，点击重试", clickable = true)
            }
        }
    }

    private fun loadNextPage() {
        if (!this::container.isInitialized) return
        val page = pagination.beginNextPage() ?: return
        val generation = requestGeneration
        val query = currentQuery
        showPagingStatus("正在加载更多...", clickable = false)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.fetchPage(pageNumber = page, searchText = query) }
            }
            if (generation != requestGeneration) return@launch
            result.onSuccess { items ->
                appendItems(pagination.complete(page, items))
                updatePagingStatus()
                scrollView.post(::maybeLoadMore)
            }.onFailure {
                pagination.fail(page)
                showPagingStatus("加载失败，点击重试", clickable = true)
            }
        }
    }

    private fun maybeLoadMore() {
        if (!pagination.canAutoLoad) return
        val contentHeight = scrollView.getChildAt(0)?.height ?: return
        if (contentHeight - scrollView.height - scrollView.scrollY <= dp(240)) loadNextPage()
    }

    private fun submitSearch() {
        currentQuery = etSearch.text.toString().trim()
        AnalyticsTracker.get(requireContext()).trackFeatureClick("news", "search")
        clearRenderedItems()
        loadFirstPage()
    }

    private fun renderItems(items: List<NewsClient.NewsItem>, replace: Boolean) {
        if (replace) clearRenderedItems()
        if (items.isEmpty() && container.childCount == 0) {
            showMainStatus("暂无资讯")
            return
        }
        tvStatus.visibility = View.GONE
        appendItems(items)
    }

    private fun appendItems(items: List<NewsClient.NewsItem>) {
        items.forEach { container.addView(buildItemView(it)) }
    }

    private fun clearRenderedItems() {
        carousels.forEach(NewsImageCarousel::pause)
        carousels.clear()
        marqueeTitles.clear()
        container.removeAllViews()
    }

    private fun buildItemView(item: NewsClient.NewsItem): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.card_bg)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { openLink(item.link) }
        }

        val carousel = NewsImageCarousel(this, item.imageUrls) { imageUrls, index ->
            showImagePreview(imageUrls, index)
        }
        carousel.view.layoutParams = LinearLayout.LayoutParams(dp(96), dp(68)).apply {
            marginEnd = dp(12)
        }
        carousels += carousel
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) carousel.resume()
        card.addView(carousel.view)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = SlowMarqueeTextView(ctx).apply {
            text = item.title
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        marqueeTitles += title
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) title.resumeSlowScroll()
        header.addView(title)
        header.addView(TextView(ctx).apply {
            text = item.tag.ifBlank { "资讯" }
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_ok))
            textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = ContextCompat.getDrawable(ctx, R.drawable.tag_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        })
        content.addView(header)
        content.addView(TextView(ctx).apply {
            text = buildMeta(item)
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        })
        content.addView(TextView(ctx).apply {
            text = item.summary.ifBlank { "点击查看原文" }
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_mid))
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        })
        card.addView(content)
        return card
    }

    private fun updatePagingStatus() {
        if (pagination.hasMorePages) {
            tvPaging.visibility = View.GONE
        } else {
            showPagingStatus("已加载全部资讯", clickable = false)
        }
    }

    private fun showPagingStatus(text: String, clickable: Boolean) {
        tvPaging.visibility = View.VISIBLE
        tvPaging.text = text
        tvPaging.isClickable = clickable
        tvPaging.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (clickable) R.color.sc_accent else R.color.sc_text_dim,
            )
        )
    }

    private fun showMainStatus(text: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
    }

    private fun buildMeta(item: NewsClient.NewsItem): String {
        val author = item.author.ifBlank { "未知来源" }
        return "$author · ${formatPubDate(item.pubDate)}"
    }

    private fun formatPubDate(raw: String): String {
        if (raw.isBlank()) return "-"
        val timestamp = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw)?.time
        }.getOrNull() ?: return raw
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private fun openLink(url: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("news", "open_link")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImagePreview(imageUrls: List<String>, initialIndex: Int) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("news", "preview_image")
        ImagePreviewDialog.show(this, imageUrls, initialIndex)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
