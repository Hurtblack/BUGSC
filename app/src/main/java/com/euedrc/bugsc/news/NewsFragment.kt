package com.euedrc.bugsc.news

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.ImageLoader
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
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
    private lateinit var container: LinearLayout
    private var currentQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_news, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = NewsRepository(requireContext())
        etSearch = view.findViewById(R.id.et_news_search)
        btnSearch = view.findViewById(R.id.btn_news_search)
        tvStatus = view.findViewById(R.id.tv_news_status)
        container = view.findViewById(R.id.container_news_list)
        btnSearch.setOnClickListener { submitSearch() }
        etSearch.setOnEditorActionListener { _, _, event ->
            if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                submitSearch()
                true
            } else {
                false
            }
        }
        val cached = repo.loadCachedFirstPage()
        if (cached != null && cached.items.isNotEmpty()) {
            render(cached.items)
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "正在拉取最新资讯..."
        }
        refreshFirstPage()
    }

    private fun refreshFirstPage() {
        val query = currentQuery.trim()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (query.isEmpty()) repo.fetchRemoteFirstPage() else repo.fetchRemoteFirstPage(query)
                }
            }
            result.onSuccess { items ->
                val currentItems = if (query.isEmpty()) repo.loadCachedFirstPage()?.items else null
                if (query.isEmpty()) repo.saveFirstPage(items)
                if (query.isNotEmpty() || currentItems != items) {
                    render(items)
                }
            }.onFailure {
                if (container.childCount == 0) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "拉取失败：${it.message ?: "网络错误"}"
                }
            }
        }
    }

    private fun submitSearch() {
        currentQuery = etSearch.text.toString().trim()
        AnalyticsTracker.get(requireContext()).trackFeatureClick("news", "search")
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = if (currentQuery.isEmpty()) "正在拉取最新资讯..." else "正在搜索“$currentQuery”..."
        container.removeAllViews()
        refreshFirstPage()
    }

    private fun render(items: List<NewsClient.NewsItem>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "暂无资讯"
            return
        }
        tvStatus.visibility = View.GONE
        for (item in items) {
            container.addView(buildItemView(item))
        }
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

        if (!item.thumbnailUrl.isNullOrBlank()) {
            val thumb = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(64)).apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ContextCompat.getDrawable(ctx, R.drawable.card_bg_blue)
            }
            card.addView(thumb)
            ImageLoader.load(this, thumb, item.thumbnailUrl, headers = mapOf("Accept" to "image/*,*/*"))
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = item.title
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
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

    private fun buildMeta(item: NewsClient.NewsItem): String {
        val author = item.author.ifBlank { "未知来源" }
        return "$author · ${formatPubDate(item.pubDate)}"
    }

    private fun formatPubDate(raw: String): String {
        if (raw.isBlank()) return "-"
        val ts = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw)?.time
        }.getOrNull() ?: return raw
        return DateUtils.getRelativeTimeSpanString(
            ts,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private fun openLink(url: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("news", "open_link")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("无法打开链接")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
