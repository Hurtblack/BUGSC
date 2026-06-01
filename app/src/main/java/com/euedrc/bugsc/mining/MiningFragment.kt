package com.euedrc.bugsc.mining

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 矿物查询入口。
 *
 * 上：搜索框 + 稀有度筛选 chip(共 7 档: 全部/legendary/epic/rare/uncommon/common/FPS)
 * 下：矿物列表 —— 点击展开 "在哪挖" 详情(按综合概率降序)
 *
 * 数据来源:[MiningRepository] (assets/mining 下的 JSON), 由 tools/export_mining 脚本生成。
 */
class MiningFragment : Fragment() {

    private lateinit var tvDataVer: TextView
    private lateinit var etSearch: EditText
    private lateinit var containerChips: LinearLayout
    private lateinit var tvCount: TextView
    private lateinit var containerList: LinearLayout

    private lateinit var repo: MiningRepository
    private var allElements: List<MiningElement> = emptyList()
    /** displayName -> 所有同名 element 的 GUID 列表 (上游有 Carinite ×3 这种重复, 合并展示)。 */
    private var nameAliases: Map<String, List<String>> = emptyMap()
    private var selectedRarity: String? = ALL    // null=FPS, ALL=不过滤
    private val expanded = HashSet<String>()     // 已展开的 element guid (代表项)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_mining, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvDataVer = view.findViewById(R.id.tv_data_ver)
        etSearch = view.findViewById(R.id.et_search)
        containerChips = view.findViewById(R.id.container_rarity_chips)
        tvCount = view.findViewById(R.id.tv_count)
        containerList = view.findViewById(R.id.container_list)

        lifecycleScope.launch {
            // 解析 ~400KB JSON, 放 IO
            repo = withContext(Dispatchers.IO) { MiningRepository.get(requireContext()) }

            // 上游 sm.scmdb.net 同一矿物可能有多个 GUID (例: Carinite ×3, 不同变体).
            // 按中文显示名分组, 代表项优先选有 scanSignature / 有 rarity 的那个。
            val grouped = repo.elements.values.groupBy { it.displayName }
            nameAliases = grouped.mapValues { (_, list) -> list.map { it.guid } }
            allElements = grouped.values.map { siblings ->
                siblings.maxByOrNull { e ->
                    (if (e.scanSignature != null) 100 else 0) +
                    (if (e.rarity != null) 10 else 0) +
                    (if (e.fpsScanSignature != null) 1 else 0)
                } ?: siblings.first()
            }.sortedWith(compareBy({ rarityOrder(it.rarity) }, { it.displayName }))
            tvDataVer.text = "SC " + (repo.gameVersion ?: "—")
            buildChips()
            applyFilter()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (::repo.isInitialized) applyFilter() }
        })
    }

    // ──────────── 筛选 chip ────────────

    private fun buildChips() {
        containerChips.removeAllViews()
        for ((key, label) in RARITY_CHIPS) {
            val tv = makeChip(label, isSelected = (key == selectedRarity))
            tv.setOnClickListener {
                selectedRarity = key
                buildChips()
                applyFilter()
            }
            containerChips.addView(tv)
        }
    }

    private fun makeChip(label: String, isSelected: Boolean): TextView {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            text = label
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(R.drawable.tag_bg)
            setTextColor(
                ContextCompat.getColor(ctx,
                    if (isSelected) R.color.sc_accent else R.color.sc_text_dim)
            )
            typeface = android.graphics.Typeface.MONOSPACE
            if (isSelected) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(6)
        tv.layoutParams = lp
        return tv
    }

    // ──────────── 列表渲染 ────────────

    private fun applyFilter() {
        val q = etSearch.text?.toString()?.trim().orEmpty()
        val byKw = if (q.isEmpty()) allElements
                   else allElements.filter {
                       it.nameEn.contains(q, ignoreCase = true) ||
                       (it.nameCn?.contains(q) == true)
                   }
        val list = when (selectedRarity) {
            ALL -> byKw
            FPS_KEY -> byKw.filter { it.rarity == null }
            else -> byKw.filter { it.rarity == selectedRarity }
        }
        renderList(list)
    }

    private fun renderList(list: List<MiningElement>) {
        tvCount.text = "${list.size} 个结果"
        containerList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (e in list) {
            containerList.addView(buildRow(inflater, e))
        }
    }

    private fun buildRow(inflater: LayoutInflater, e: MiningElement): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            isClickable = true; isFocusable = true
        }

        // 标题行: 中文 + 英文 + 稀有度 tag
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tvName = TextView(ctx).apply {
            text = e.displayName
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvRarity = TextView(ctx).apply {
            text = rarityLabel(e.rarity)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundResource(R.drawable.tag_bg)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setTextColor(rarityColor(ctx, e.rarity))
        }
        titleRow.addView(tvName); titleRow.addView(tvRarity)
        card.addView(titleRow)

        // 副标题: 英文名 (如果有中文才显示英文)
        if (e.nameCn != null && e.nameCn != e.nameEn) {
            card.addView(TextView(ctx).apply {
                text = e.nameEn
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(2)
                layoutParams = lp
            })
        }

        // 属性摘要
        val sig = e.scanSignature ?: e.fpsScanSignature
        card.addView(TextView(ctx).apply {
            text = buildString {
                if (sig != null) append("扫描签名 ").append(sig).append("  ")
                append("密度 ").append("%.2f".format(e.density)).append("  ")
                append("不稳定性 ").append("%.0f".format(e.instability))
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_mid))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        })

        // 展开/收起出现地点
        val detail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded.contains(e.guid)) View.VISIBLE else View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        if (detail.visibility == View.VISIBLE) populateOccurrences(detail, e)
        card.addView(detail)

        card.setOnClickListener {
            if (expanded.remove(e.guid)) {
                detail.visibility = View.GONE
                detail.removeAllViews()
            } else {
                expanded.add(e.guid)
                populateOccurrences(detail, e)
                detail.visibility = View.VISIBLE
            }
        }
        return card
    }

    /** 占位 Row, 解决 inner data class 跨方法引用. */
    private data class OccRow(
        val sysCn: String, val sysEn: String,
        val locCn: String, val locEn: String,
        val typeCn: String?, val compName: String,
        val partMin: Double, val partMax: Double,
        val partProb: Double, val locProb: Double,
        val overall: Double,
    )

    private fun populateOccurrences(parent: LinearLayout, e: MiningElement) {
        parent.removeAllViews()
        val ctx = requireContext()

        // 合并所有同显示名 element 的 occurrences (Carinite 等多变体)
        val guids = nameAliases[e.displayName] ?: listOf(e.guid)
        val occs = guids.flatMap { repo.findElementOccurrences(it) }

        if (occs.isEmpty()) {
            parent.addView(TextView(ctx).apply {
                text = "未找到出现地点 (此矿物可能仅出现在特殊事件)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
            })
            return
        }

        // 按地点去重, 同地点保留概率最高那条; 然后 按 系统 → 综合概率 排序
        val rows = occs.groupBy { it.location.locationName }
            .map { (_, list) ->
                val pick = list.maxBy { it.overallProbability }
                OccRow(
                    sysCn = pick.location.systemCn ?: pick.location.system,
                    sysEn = pick.location.system,
                    locCn = pick.location.locationNameCn ?: pick.location.locationName,
                    locEn = pick.location.locationName,
                    typeCn = pick.location.locationTypeCn,
                    compName = pick.composition.name,
                    partMin = pick.minPercent, partMax = pick.maxPercent,
                    partProb = pick.part.probability,
                    locProb = pick.probabilityInGroup,
                    overall = pick.overallProbability,
                )
            }
            .sortedWith(compareBy({ it.sysCn }, { -it.overall }))

        parent.addView(TextView(ctx).apply {
            text = "◆ 出现地点 · ${rows.size} 处"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_accent))
        })

        var lastSystem: String? = null
        for (r in rows.take(60)) {
            if (r.sysCn != lastSystem) {
                lastSystem = r.sysCn
                parent.addView(makeSystemHeader(ctx, r.sysCn, r.sysEn))
            }
            parent.addView(makeLocationRow(ctx, r))
        }
        if (rows.size > 60) {
            parent.addView(TextView(ctx).apply {
                text = "... 还有 ${rows.size - 60} 处"
                textSize = 10f
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            })
        }
    }

    /** 系统分组小标题, 如 "派罗 · PYRO"。 */
    private fun makeSystemHeader(ctx: android.content.Context, cn: String, en: String): TextView =
        TextView(ctx).apply {
            text = "$cn · ${en.uppercase()}"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_accent))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            lp.bottomMargin = dp(2)
            layoutParams = lp
        }

    /** 单条地点行: 大字粗体中文星球名 / 小字英文 + 类型 / 第二行组合 / 第三行数据条。 */
    private fun makeLocationRow(ctx: android.content.Context, r: OccRow): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(R.drawable.tag_bg)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }

        // 第一行: 中文地名 (大) + 类型 tag (右上)
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = r.locCn
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (r.typeCn != null) {
            headerRow.addView(TextView(ctx).apply {
                text = r.typeCn
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundResource(R.drawable.tag_bg)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_accent))
            })
        }
        box.addView(headerRow)

        // 第二行: 英文地名 (小, 灰)
        if (r.locEn != r.locCn) {
            box.addView(TextView(ctx).apply {
                text = r.locEn
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(1)
                layoutParams = lp
            })
        }

        // 第三行: 组合名 (中)
        box.addView(TextView(ctx).apply {
            text = "组合  ${r.compName}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_mid))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        })

        // 第四行: 数据条 (小, 等宽)
        box.addView(TextView(ctx).apply {
            text = "含量 %.0f-%.0f%%   ·   组合概率 %.0f%%   ·   出现权重 %.1f%%".format(
                r.partMin, r.partMax, r.partProb * 100, r.locProb * 100)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.sc_text_dim))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(2)
            layoutParams = lp
        })

        return box
    }

    // ──────────── 工具 ────────────

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rarityLabel(r: String?): String = when (r) {
        "legendary" -> "传说"
        "epic" -> "史诗"
        "rare" -> "稀有"
        "uncommon" -> "罕见"
        "common" -> "普通"
        null -> "FPS"
        else -> r
    }

    private fun rarityColor(ctx: android.content.Context, r: String?): Int = when (r) {
        "legendary" -> Color.parseColor("#FFB347")
        "epic" -> Color.parseColor("#C792EA")
        "rare" -> Color.parseColor("#5BA9FF")
        "uncommon" -> Color.parseColor("#5FCE7B")
        "common" -> ContextCompat.getColor(ctx, R.color.sc_text_mid)
        null -> Color.parseColor("#8A8A8A")
        else -> ContextCompat.getColor(ctx, R.color.sc_text_mid)
    }

    private fun rarityOrder(r: String?): Int = when (r) {
        "legendary" -> 0
        "epic" -> 1
        "rare" -> 2
        "uncommon" -> 3
        "common" -> 4
        null -> 5
        else -> 9
    }

    companion object {
        private const val ALL = "__all__"
        private const val FPS_KEY = "__fps__"
        private val RARITY_CHIPS = listOf(
            ALL to "全部",
            "legendary" to "传说",
            "epic" to "史诗",
            "rare" to "稀有",
            "uncommon" to "罕见",
            "common" to "普通",
            FPS_KEY to "FPS手持",
        )
    }
}
