package com.euedrc.bugsc.wikelo

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.euedrc.bugsc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 维克洛兑换查询页面。
 *
 * 三层过滤: branch chip → category chip → 关键词搜索 + 隐藏不可用。
 * RecyclerView 列出筛选后的 [WikeloTrade], 每张卡片可展开材料行查看获取途径。
 */
class WikeloFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var repo: WikeloRepository? = null

    // 过滤状态
    private var branchFilter: WikeloBranch? = null   // null = 全部分支
    private var categoryFilter: String? = null       // null = 全部分类
    private var keyword: String = ""
    private var hideUnavailable: Boolean = false
    /** 已展开材料列表的任务索引集合 (按 trade 引用)。 */
    private val expandedMats = HashSet<WikeloTrade>()

    private lateinit var tvVersion: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: android.widget.EditText
    private lateinit var swHideOff: SwitchCompat
    private lateinit var barBranch: LinearLayout
    private lateinit var barCategory: LinearLayout
    private lateinit var rvList: RecyclerView

    private val adapter = TradeAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_wikelo, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvVersion = view.findViewById(R.id.tv_version)
        tvCount = view.findViewById(R.id.tv_count)
        tvEmpty = view.findViewById(R.id.tv_empty)
        etSearch = view.findViewById(R.id.et_search)
        swHideOff = view.findViewById(R.id.sw_hide_unavailable)
        barBranch = view.findViewById(R.id.bar_branch)
        barCategory = view.findViewById(R.id.bar_category)
        rvList = view.findViewById(R.id.rv_list)

        rvList.layoutManager = LinearLayoutManager(requireContext())
        rvList.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty()
                refresh()
            }
        })
        swHideOff.setOnCheckedChangeListener { _, isChecked ->
            hideUnavailable = isChecked
            refresh()
        }

        // 异步加载 (首次解析放 IO)
        scope.launch {
            val r = withContext(Dispatchers.IO) { WikeloRepository.get(requireContext()) }
            repo = r
            tvVersion.text = buildString {
                r.version?.let { append(it).append(" · ") }
                append(r.trades.size).append(" 项")
            }
            buildBranchBar()
            buildCategoryBar()
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    // ──────────── 过滤条 ────────────

    private fun buildBranchBar() {
        barBranch.removeAllViews()
        // "全部" 在最前
        addChip(barBranch, "全部", branchFilter == null) {
            branchFilter = null; categoryFilter = null
            buildBranchBar(); buildCategoryBar(); refresh()
        }
        for (b in WikeloBranch.entries) {
            addChip(barBranch, b.labelCn, branchFilter == b) {
                branchFilter = b; categoryFilter = null
                buildBranchBar(); buildCategoryBar(); refresh()
            }
        }
    }

    private fun buildCategoryBar() {
        barCategory.removeAllViews()
        val cats = repo?.categoriesIn(branchFilter).orEmpty()
        addChip(barCategory, "全部分类", categoryFilter == null) {
            categoryFilter = null; buildCategoryBar(); refresh()
        }
        for (c in cats) {
            addChip(barCategory, c, categoryFilter == c) {
                categoryFilter = c; buildCategoryBar(); refresh()
            }
        }
    }

    private fun addChip(parent: LinearLayout, text: String, selected: Boolean, onClick: () -> Unit) {
        val ctx = parent.context
        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 12f
        tv.setPadding(dp(12), dp(5), dp(12), dp(5))
        tv.setBackgroundResource(R.drawable.wikelo_chip_bg)
        tv.isSelected = selected
        tv.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (selected) R.color.sc_accent else R.color.sc_text_mid
            )
        )
        if (selected) tv.setTypeface(null, Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(6)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        parent.addView(tv)
    }

    // ──────────── 数据应用 ────────────

    private fun refresh() {
        val r = repo ?: return
        var list = r.tradesByCategory(branchFilter, categoryFilter)
        if (keyword.isNotBlank()) {
            val q = keyword.trim().lowercase()
            list = list.filter { t ->
                t.nameCn.lowercase().contains(q) ||
                        t.nameEn.lowercase().contains(q) ||
                        (t.rewardItem?.lowercase()?.contains(q) == true) ||
                        t.materials.any { it.nameCn.lowercase().contains(q) }
            }
        }
        if (hideUnavailable) list = list.filter { it.available }
        adapter.submit(list)
        tvCount.text = "${list.size} / ${r.trades.size}"
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ──────────── RecyclerView ────────────

    private inner class TradeAdapter : RecyclerView.Adapter<TradeVH>() {
        private var items: List<WikeloTrade> = emptyList()
        fun submit(list: List<WikeloTrade>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TradeVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wikelo_trade, parent, false)
            return TradeVH(v)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: TradeVH, position: Int) = holder.bind(items[position])
    }

    private inner class TradeVH(val view: View) : RecyclerView.ViewHolder(view) {
        val ivReward: ImageView = view.findViewById(R.id.iv_reward)
        val tvNameCn: TextView = view.findViewById(R.id.tv_name_cn)
        val tvNameEn: TextView = view.findViewById(R.id.tv_name_en)
        val tvReward: TextView = view.findViewById(R.id.tv_reward)
        val badges: LinearLayout = view.findViewById(R.id.container_badges)
        val divider: View = view.findViewById(R.id.divider_mats)
        val matsBox: LinearLayout = view.findViewById(R.id.container_mats)
        val tvNotes: TextView = view.findViewById(R.id.tv_notes)

        fun bind(t: WikeloTrade) {
            // 不可用置灰
            view.alpha = if (t.available) 1f else 0.5f

            tvNameCn.text = t.nameCn
            tvNameEn.text = t.nameEn.takeIf { it.isNotBlank() } ?: ""
            tvNameEn.visibility = if (tvNameEn.text.isNullOrBlank()) View.GONE else View.VISIBLE
            if (t.rewardItem.isNullOrBlank()) {
                tvReward.visibility = View.GONE
            } else {
                tvReward.visibility = View.VISIBLE
                tvReward.text = "🎁 ${t.rewardItem}"
            }

            // 图片预留: imageAsset 非空时显示 (当前数据均为 null)
            // TODO: 接入兑换物图片 → 将 assets/wikelo/img/{slug}.webp 加载到 ivReward
            ivReward.visibility = if (t.imageAsset != null) View.VISIBLE else View.GONE

            // 徽章
            badges.removeAllViews()
            addBadge(badges, t.category, R.color.sc_accent_ink, R.color.sc_accent)
            t.favorCost?.takeIf { it > 0 }?.let {
                addBadge(badges, "人情 ×$it", R.color.sc_warn_ink, R.color.sc_warn)
            }
            t.reputation.reward?.takeIf { it > 0 }?.let {
                addBadge(badges, "声望 +$it", R.color.sc_ok_ink, R.color.sc_ok)
            }
            t.reputation.requiredTier?.let {
                addBadge(badges, "需 Lv$it", R.color.sc_danger_ink, R.color.sc_danger)
            }
            if (t.newThisVersion) addBadge(badges, "新增", R.color.sc_purple_ink, R.color.sc_purple)
            if (!t.available) addBadge(badges, "当前不可用", R.color.sc_danger_ink, R.color.sc_danger)

            // 材料列表
            matsBox.removeAllViews()
            if (t.materials.isEmpty()) {
                divider.visibility = View.GONE
            } else {
                divider.visibility = View.VISIBLE
                val expanded = expandedMats.contains(t)
                for (m in t.materials) {
                    matsBox.addView(buildMatRow(m, expanded, t))
                }
            }

            tvNotes.text = t.notes ?: ""
            tvNotes.visibility = if (t.notes.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        private fun buildMatRow(m: WikeloMaterialReq, openInit: Boolean, owner: WikeloTrade): View {
            val ctx = view.context
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(0, dp(4), 0, dp(4))

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val arrow = TextView(ctx)
            arrow.text = if (openInit) "▼" else "▶"
            arrow.setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            arrow.setPadding(0, 0, dp(6), 0)

            val name = TextView(ctx)
            name.text = m.nameCn
            name.setTextColor(ContextCompat.getColor(ctx, R.color.sc_text))
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val nameLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            name.layoutParams = nameLp

            val qty = TextView(ctx)
            qty.text = if (m.qty != null) "×${m.qty}${m.unit.orEmpty()}" else "×?"
            qty.setTextColor(ContextCompat.getColor(ctx, R.color.sc_accent))
            qty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            qty.setTypeface(null, Typeface.BOLD)

            row.addView(arrow)
            row.addView(name)
            row.addView(qty)

            val src = TextView(ctx)
            src.setPadding(dp(14), dp(2), 0, dp(2))
            src.setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_mid))
            src.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val acq = repo?.acquisitionFor(m.nameCn)
            src.text = if (acq != null) "获取: ${acq.joinToString(" · ")}"
                       else "获取途径暂无数据"
            src.visibility = if (openInit) View.VISIBLE else View.GONE

            box.addView(row)
            box.addView(src)

            box.setOnClickListener {
                val nowVisible = src.visibility == View.VISIBLE
                src.visibility = if (nowVisible) View.GONE else View.VISIBLE
                arrow.text = if (nowVisible) "▶" else "▼"
                if (nowVisible) expandedMats.remove(owner) else expandedMats.add(owner)
            }
            return box
        }

        private fun addBadge(parent: LinearLayout, text: String, bgColorRes: Int, textColorRes: Int) {
            val ctx = parent.context
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            tv.setTypeface(null, Typeface.BOLD)
            tv.setPadding(dp(6), dp(2), dp(6), dp(2))
            tv.setBackgroundResource(R.drawable.wikelo_badge_bg)
            tv.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColorRes))
            tv.setTextColor(ContextCompat.getColor(ctx, textColorRes))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(5)
            tv.layoutParams = lp
            parent.addView(tv)
        }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
