package com.euedrc.bugsc.wb

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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

/** 每日 WB（官网 warbond 限时折扣船）列表页。 */
class WbFragment : Fragment() {

    private lateinit var repo: WbRepository
    private lateinit var btnSync: Button
    private lateinit var tvUpdated: TextView
    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_wb, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AnalyticsTracker.get(requireContext()).trackPageView("daily_wb")
        repo = WbRepository(requireContext())
        btnSync = view.findViewById(R.id.btn_wb_sync)
        tvUpdated = view.findViewById(R.id.tv_wb_updated)
        tvStatus = view.findViewById(R.id.tv_wb_status)
        container = view.findViewById(R.id.container_wb_list)

        btnSync.setOnClickListener { sync() }
        render()
    }

    /** 从本地（缓存>assets）读取并渲染列表。 */
    private fun render() {
        val items = repo.loadWbItems()
        tvUpdated.text = formatUpdated(repo.generatedAt())
        container.removeAllViews()
        if (items.isEmpty()) {
            showStatus("暂无数据，点「同步」拉取")
            return
        }
        tvStatus.visibility = View.GONE
        for (item in items) container.addView(buildItemView(item))
    }

    /** 同步：禁用按钮 → IO 拉远程 → 刷新 → toast。 */
    private fun sync() {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("daily_wb", "refresh")
        btnSync.isEnabled = false
        btnSync.text = "同步中"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.refreshFromRemote() } }
            btnSync.isEnabled = true
            btnSync.text = "同步"
            result.onSuccess { updated ->
                render()
                toast(if (updated) "已更新最新折扣船" else "已是最新")
            }.onFailure {
                toast("同步失败：${it.message ?: "网络错误"}")
            }
        }
    }

    private fun buildItemView(item: WbRepository.WbItem): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.card_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            item.url?.let { url ->
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure { toast("无法打开链接") }
                }
            }
        }

        // 缩略图
        val thumb = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(54)).apply { marginEnd = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.sc_bg_deep))
        }
        card.addView(thumb)
        ImageLoader.load(
            fragment = this,
            imageView = thumb,
            url = item.thumbnail,
            headers = mapOf("Referer" to "https://robertsspaceindustries.com/"),
        )

        // 文字列
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = item.displayName
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // 价格行：WB 优惠价（高亮）+ 原价（删除线、灰）
        val priceRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            layoutParams = lp
        }
        item.warbondPrice?.let { wb ->
            priceRow.addView(TextView(ctx).apply {
                text = "WB ${money(item.currency, wb)}"
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_ok))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        item.standardPrice?.let { std ->
            priceRow.addView(TextView(ctx).apply {
                text = money(item.currency, std)
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
                textSize = 12f
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            })
        }
        col.addView(priceRow)
        card.addView(col)
        return card
    }

    private fun showStatus(msg: String) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun money(currency: String, amount: Double): String {
        val n = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
        val sym = if (currency == "USD") "$" else ""
        return "$sym$n"
    }

    /** generatedAt(UTC ISO) → 「更新于 x 前」相对时间；解析失败回退原串。 */
    private fun formatUpdated(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        val ts = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(iso)?.time
        }.getOrNull() ?: return "更新于 $iso"
        val rel = DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        )
        return "更新于 $rel"
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
