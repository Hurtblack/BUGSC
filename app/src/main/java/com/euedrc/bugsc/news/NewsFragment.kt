package com.euedrc.bugsc.news

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NewsFragment : Fragment() {

    private lateinit var repo: NewsRepository

    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_news, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = NewsRepository(requireContext())
        tvStatus = view.findViewById(R.id.tv_news_status)
        container = view.findViewById(R.id.container_news_list)
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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repo.fetchRemoteFirstPage()
                }
            }
            result.onSuccess { items ->
                val currentItems = repo.loadCachedFirstPage()?.items
                repo.saveFirstPage(items)
                if (currentItems != items) {
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
            loadThumb(thumb, item.thumbnailUrl)
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

    private fun loadThumb(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        view.tag = url
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Accept", "image/*,*/*")
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        BitmapFactory.decodeStream(conn.inputStream)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (view.tag == url && bmp != null) view.setImageBitmap(bmp)
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
