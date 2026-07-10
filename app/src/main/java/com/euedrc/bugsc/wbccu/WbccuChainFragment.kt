package com.euedrc.bugsc.wbccu

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.euedrc.bugsc.InventoryCacheCodec
import com.euedrc.bugsc.InventoryItem
import com.euedrc.bugsc.InventoryShipPrice
import com.euedrc.bugsc.InventoryShipPriceAliases
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import org.json.JSONObject
import java.util.Locale

class WbccuChainFragment : Fragment() {

    private lateinit var store: WbccuChainStore
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvValidation: TextView
    private lateinit var containerSteps: LinearLayout
    private lateinit var btnPlanRoute: Button
    private lateinit var btnAddInventory: Button
    private lateinit var btnAddManual: Button
    private lateinit var btnImport: Button
    private lateinit var btnCopy: Button
    private lateinit var btnCopyInventory: Button
    private lateinit var btnClear: Button

    private var currentChain = emptyChain()
    private val shipAliases by lazy { loadShipAliases() }
    private val shipPrices by lazy { loadShipPrices() }
    private val shipOptions by lazy { buildShipOptions() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_wbccu_chain, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = WbccuChainStore(requireContext())
        tvStatus = view.findViewById(R.id.tv_wbccu_status)
        tvSummary = view.findViewById(R.id.tv_wbccu_summary)
        tvValidation = view.findViewById(R.id.tv_wbccu_validation)
        containerSteps = view.findViewById(R.id.container_wbccu_steps)
        btnPlanRoute = view.findViewById(R.id.btn_wbccu_add_base)
        btnAddInventory = view.findViewById(R.id.btn_wbccu_add_inventory)
        btnAddManual = view.findViewById(R.id.btn_wbccu_add_manual)
        btnImport = view.findViewById(R.id.btn_wbccu_import)
        btnCopy = view.findViewById(R.id.btn_wbccu_copy)
        btnCopyInventory = view.findViewById(R.id.btn_wbccu_copy_inventory)
        btnClear = view.findViewById(R.id.btn_wbccu_clear)

        currentChain = store.loadChains().firstOrNull() ?: emptyChain()
        btnPlanRoute.setOnClickListener {
            track("auto_plan")
            showAutoPlanDialog()
        }
        btnAddInventory.setOnClickListener {
            track("add_inventory")
            showInventoryPicker()
        }
        btnAddManual.setOnClickListener {
            track("add_manual")
            showManualDialog()
        }
        btnImport.setOnClickListener {
            track("import")
            showImportDialog()
        }
        btnCopy.setOnClickListener {
            track("copy_share")
            copyShareCode()
        }
        btnCopyInventory.setOnClickListener {
            track("copy_inventory_ccus")
            copyInventoryCcuList()
        }
        btnClear.setOnClickListener {
            track("clear")
            confirmClear()
        }
        render()
    }

    private fun render() {
        val validation = WbccuChainValidator.validate(currentChain)
        val summary = WbccuChainValidator.summarize(currentChain)
        tvStatus.text = if (currentChain.steps.isEmpty()) {
            "暂无链路。${cacheStatusText()}"
        } else {
            "当前链路：${currentChain.steps.size} 段。${cacheStatusText()}"
        }
        tvSummary.text = buildList {
            add("总实付 ${money(summary.totalPaidCents)}")
            add("待新增/计划 ${money(summary.missingCashCents)}")
            if (summary.ownedInventoryPaidCents > 0) add("库存已有 ${money(summary.ownedInventoryPaidCents)}")
            if (summary.ownedManualPaidCents > 0) add("手动已有 ${money(summary.ownedManualPaidCents)}")
            add("最终船价 ${money(summary.finalShipValueCents)}")
            add("CCU省 ${saving(summary.savingCents)}")
        }.joinToString(" · ")
        val validationText = (validation.errors + validation.warnings).joinToString("\n")
        tvValidation.visibility = if (validationText.isBlank()) View.GONE else View.VISIBLE
        tvValidation.text = validationText

        containerSteps.removeAllViews()
        if (currentChain.steps.isEmpty()) {
            containerSteps.addView(emptyText())
            return
        }
        currentChain.steps.forEachIndexed { index, step ->
            containerSteps.addView(stepCard(index, step))
        }
    }

    private fun stepCard(index: Int, step: WbccuChainStep): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dpToPx() }
            background = roundedBg("#0d1620", "#1b3145")
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())

            addView(TextView(requireContext()).apply {
                text = if (step.isBase) {
                    "#${index + 1}  起始飞船 · ${translated(step.toShipName)}"
                } else {
                    "#${index + 1}  ${translated(step.fromShipName)} -> ${translated(step.toShipName)}"
                }
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#d8eaf2"))
            })
            addView(metaText(originalLine(step)))
            addView(metaText(
                listOf(
                    sourceLabel(step),
                    saleLabel(step.saleType),
                    "实付 ${money(step.paidCents)}",
                    "目标 ${money(step.toShipPriceCents)}"
                ).filter { it.isNotBlank() }.joinToString(" · ")
            ))
            if (!step.isBase) {
                addView(metaText("标准差价 ${money(step.standardUpgradeCents)} · WB省 ${saving(step.savingCents)}"))
            }
            if (step.note.isNotBlank()) addView(metaText(step.note))
            addView(Button(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6.dpToPx() }
                text = "删除此段"
                textSize = 12f
                setTextColor(Color.parseColor("#ff8a3d"))
                setOnClickListener {
                    currentChain = currentChain.copy(
                        steps = currentChain.steps.filterIndexed { i, _ -> i != index },
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    persistAndRender()
                }
            })
        }
    }

    private fun showAutoPlanDialog() {
        val form = formLayout()
        val startInput = shipAutoInput("起始飞船，例如 自由枪骑兵 DUR / Freelancer DUR")
        val startPriceInput = moneyInput("起始船价")
        val startMatchText = matchText()
        val targetInput = shipAutoInput("目标飞船，例如 英仙座 / Perseus")
        val targetPriceInput = moneyInput("目标船价")
        val targetMatchText = matchText()
        val helpText = metaText("只使用本地库存 CCU/WB；断开的区间自动补标准 CCU。生成后会替换当前链路。")
        listOf(
            startInput,
            startMatchText,
            startPriceInput,
            targetInput,
            targetMatchText,
            targetPriceInput,
            helpText
        ).forEach { form.addView(it) }
        var startMatch: WbccuShipMatch? = null
        var targetMatch: WbccuShipMatch? = null
        bindShipAutoMatch(startInput, startPriceInput, startMatchText) { match ->
            startMatch = match
        }
        bindShipAutoMatch(targetInput, targetPriceInput, targetMatchText) { match ->
            targetMatch = match
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("一键规划 CCU 链")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成链路", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val start = startMatch ?: WbccuShipMatcher.match(
                query = startInput.text.toString(),
                shipPrices = shipPrices,
                shipAliases = shipAliases
            )
            val target = targetMatch ?: WbccuShipMatcher.match(
                query = targetInput.text.toString(),
                shipPrices = shipPrices,
                shipAliases = shipAliases
            )
            if (start == null || target == null) {
                toast("请选择起始飞船和目标飞船")
                return@setOnClickListener
            }
            val result = WbccuChainPlanner.plan(
                WbccuChainPlanRequest(
                    startShipName = start.name,
                    startShipPriceCents = parseMoney(startPriceInput.text.toString()).takeIf { it > 0 }
                        ?: start.priceCents,
                    targetShipName = target.name,
                    targetShipPriceCents = parseMoney(targetPriceInput.text.toString()).takeIf { it > 0 }
                        ?: target.priceCents,
                    inventoryCcus = cachedInventoryCcus()
                )
            )
            if (result.errors.isNotEmpty()) {
                toast(result.errors.first())
                return@setOnClickListener
            }
            val chain = currentChain.copy(
                steps = result.steps,
                updatedAtMillis = System.currentTimeMillis()
            )
            val validation = WbccuChainValidator.validate(chain)
            if (!validation.isValid) {
                toast(validation.errors.first())
                return@setOnClickListener
            }
            currentChain = chain
            persistAndRender()
            dialog.dismiss()
        }
        tintDialog(dialog)
    }

    private fun showInventoryPicker() {
        val inventoryCcus = cachedInventoryCcus()
        if (inventoryCcus.isEmpty()) {
            toast("没有本地库存 CCU 缓存。先去 RSI 机库库存刷新一下。")
            return
        }
        val wbCount = inventoryCcus.count { it.saleType == WbccuSaleType.WARBOND }
        val labels = inventoryCcus.map { candidate ->
            val item = candidate.item
            val summary = candidate.summary
            "${translated(summary.fromShipName)} -> ${translated(summary.toShipName)} · ${saleLabel(candidate.saleType)} · 实付 ${money(item.priceCents)} · 省 ${saving(summary.savingCents)}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("从 RSI 机库库存添加（WB $wbCount / CCU ${inventoryCcus.size}）")
            .setItems(labels) { _, which ->
                val candidate = inventoryCcus[which]
                val item = candidate.item
                val summary = candidate.summary
                val step = WbccuChainStep(
                    id = WbccuChainCodec.newId("inv"),
                    fromShipName = summary.fromShipName,
                    toShipName = summary.toShipName,
                    fromShipPriceCents = summary.fromShipPriceCents,
                    toShipPriceCents = summary.toShipPriceCents,
                    paidCents = item.priceCents,
                    saleType = candidate.saleType,
                    source = WbccuChainStepSource.INVENTORY,
                    inventoryItemId = item.id,
                    note = "库存物品：${item.name}"
                )
                addStep(step)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManualDialog() {
        val form = formLayout()
        val fromInput = shipAutoInput("起始船，例如 Starlancer TAC")
        val fromPriceInput = moneyInput("起始船价，例如 375")
        val fromMatchText = matchText()
        val toInput = shipAutoInput("目标船，例如 Tyilui")
        val toPriceInput = moneyInput("目标船价，例如 425")
        val toMatchText = matchText()
        val paidInput = moneyInput("实付，例如 10")
        val wbCheck = CheckBox(requireContext()).apply {
            text = "这是 Warbond / 折扣 CCU"
            setTextColor(Color.parseColor("#d8eaf2"))
        }
        val ownedCheck = CheckBox(requireContext()).apply {
            text = "我已经拥有这张 CCU（手动标记）"
            setTextColor(Color.parseColor("#d8eaf2"))
        }
        val waitWbCheck = CheckBox(requireContext()).apply {
            text = "暂时没有，等 WB 再买"
            setTextColor(Color.parseColor("#d8eaf2"))
        }
        val noteInput = textInput("备注，可空")
        listOf(
            fromInput,
            fromMatchText,
            fromPriceInput,
            toInput,
            toMatchText,
            toPriceInput,
            paidInput,
            wbCheck,
            ownedCheck,
            waitWbCheck,
            noteInput
        ).forEach {
            form.addView(it)
        }
        var fromMatch: WbccuShipMatch? = null
        var toMatch: WbccuShipMatch? = null
        bindShipAutoMatch(fromInput, fromPriceInput, fromMatchText) { match ->
            fromMatch = match
        }
        bindShipAutoMatch(toInput, toPriceInput, toMatchText) { match ->
            toMatch = match
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("添加 CCU")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val from = fromMatch?.name ?: fromInput.text.toString().trim()
            val to = toMatch?.name ?: toInput.text.toString().trim()
            val fromPrice = parseMoney(fromPriceInput.text.toString())
            val toPrice = parseMoney(toPriceInput.text.toString())
            val paid = parseMoney(paidInput.text.toString())
            if (ownedCheck.isChecked && waitWbCheck.isChecked) {
                toast("手动已拥有和等 WB 只能选一个")
                return@setOnClickListener
            }
            val step = WbccuChainStep(
                id = WbccuChainCodec.newId("manual"),
                fromShipName = from,
                toShipName = to,
                fromShipPriceCents = fromPrice,
                toShipPriceCents = toPrice,
                paidCents = paid,
                saleType = if (wbCheck.isChecked) WbccuSaleType.WARBOND else WbccuSaleType.STANDARD,
                source = when {
                    ownedCheck.isChecked -> WbccuChainStepSource.MANUAL_OWNED
                    waitWbCheck.isChecked -> WbccuChainStepSource.WAITING_WB
                    else -> WbccuChainStepSource.MANUAL
                },
                note = noteInput.text.toString().trim()
            )
            if (addStep(step)) dialog.dismiss()
        }
        tintDialog(dialog)
    }

    private fun showImportDialog() {
        val input = EditText(requireContext()).apply {
            hint = "粘贴 WBCCU:v1:..."
            minLines = 4
            setTextColor(Color.parseColor("#d8eaf2"))
            setHintTextColor(Color.parseColor("#7c95a8"))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("导入 WBCCU 分享码")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (val result = WbccuChainCodec.decodeShare(input.text.toString())) {
                is WbccuChainDecodeResult.Error -> toast(result.message)
                is WbccuChainDecodeResult.Success -> {
                    val imported = result.chain.copy(
                        id = WbccuChainCodec.newId("chain"),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    val validation = WbccuChainValidator.validate(imported)
                    if (!validation.isValid) {
                        toast(validation.errors.first())
                        return@setOnClickListener
                    }
                    currentChain = imported
                    persistAndRender()
                    dialog.dismiss()
                }
            }
        }
        tintDialog(dialog)
    }

    private fun copyShareCode() {
        if (currentChain.steps.isEmpty()) {
            toast("链路为空，没东西可分享")
            return
        }
        val code = WbccuChainCodec.encodeShare(currentChain)
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WBCCU 链路分享码", code))
        toast("WBCCU 分享码已复制")
    }

    private fun copyInventoryCcuList() {
        val inventoryCcus = cachedInventoryCcus()
        if (inventoryCcus.isEmpty()) {
            toast("没有本地库存 CCU 缓存。先去 RSI 机库库存刷新一下。")
            return
        }
        val text = WbccuInventoryCcuExporter.exportText(inventoryCcus)
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("库存 WBCCU 清单", text))
        toast("库存 WBCCU 清单已复制")
    }

    private fun confirmClear() {
        if (currentChain.steps.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("清空链路")
            .setMessage("只清空本地 WBCCU 链路，不会影响 RSI 库存。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                currentChain = emptyChain()
                persistAndRender()
            }
            .show()
    }

    private fun addStep(step: WbccuChainStep): Boolean {
        return tryReplaceSteps(currentChain.steps + step)
    }

    private fun tryReplaceSteps(steps: List<WbccuChainStep>): Boolean {
        val candidate = currentChain.copy(
            steps = steps,
            updatedAtMillis = System.currentTimeMillis()
        )
        val validation = WbccuChainValidator.validate(candidate)
        if (!validation.isValid) {
            toast(validation.errors.first())
            return false
        }
        currentChain = candidate
        persistAndRender()
        return true
    }

    private fun persistAndRender() {
        store.saveChains(listOf(currentChain))
        render()
    }

    private fun loadCachedItems(): List<InventoryItem> {
        val prefs = requireContext().getSharedPreferences("inventory", Context.MODE_PRIVATE)
        if (prefs.getInt("inventoryCacheVersion", 0) != 4) return emptyList()
        return InventoryCacheCodec.decodeItems(prefs.getString("inventoryItemsCache", "") ?: "")
    }

    private fun cachedInventoryCcus(): List<WbccuInventoryCcuCandidate> {
        return WbccuInventoryCcuMapper.candidates(loadCachedItems(), shipPrices)
    }

    private fun cacheStatusText(): String {
        val candidates = cachedInventoryCcus()
        val wbCount = candidates.count { it.saleType == WbccuSaleType.WARBOND }
        val standardCount = candidates.count { it.saleType == WbccuSaleType.STANDARD }
        val usedInventoryCount = currentChain.steps.count { it.source == WbccuChainStepSource.INVENTORY }
        return if (candidates.isEmpty()) {
            "库存：未读取到本地 CCU 缓存；先去 RSI 机库库存刷新。"
        } else {
            "库存：CCU ${candidates.size} 张 · WB ${wbCount} 张 · 标准 ${standardCount} 张 · 已用 ${usedInventoryCount} 张。"
        }
    }

    private fun loadShipAliases(): Map<String, String> = runCatching {
        val root = requireContext().assets.open("shipfit/zh_aliases.json")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
        val ships = root.optJSONObject("ships") ?: return@runCatching emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in ships.keys()) {
            val value = ships.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        out
    }.getOrDefault(emptyMap())

    private fun loadShipPrices(): List<InventoryShipPrice> = runCatching {
        val root = requireContext().assets.open("shipfit/rsi_ship_prices.json")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
        val ships = root.optJSONArray("ships") ?: return@runCatching emptyList()
        val out = ArrayList<InventoryShipPrice>()
        for (i in 0 until ships.length()) {
            val ship = ships.optJSONObject(i) ?: continue
            val name = ship.optString("name")
            val price = ship.optInt("sale_price_cents", 0)
            val id = ship.optInt("id").takeIf { it > 0 }
            if (name.isNotBlank() && price > 0) out += InventoryShipPrice(id, name, price)
        }
        InventoryShipPriceAliases.expand(out, shipAliases)
    }.getOrDefault(emptyList())

    private fun translated(name: String): String {
        val exact = shipAliases[name]
        if (exact != null) return exact
        val normalized = WbccuChainValidator.normalizeShipName(name)
        return shipAliases.entries.firstOrNull { (key, _) ->
            WbccuChainValidator.normalizeShipName(key) == normalized
        }?.value ?: name
    }

    private fun originalLine(step: WbccuChainStep): String {
        return if (step.isBase) {
            "${step.toShipName} · 船值 ${money(step.toShipPriceCents)}"
        } else {
            "${step.fromShipName} (${money(step.fromShipPriceCents)}) -> ${step.toShipName} (${money(step.toShipPriceCents)})"
        }
    }

    private fun sourceLabel(step: WbccuChainStep): String {
        return when (step.source) {
            WbccuChainStepSource.BASE -> "起始飞船"
            WbccuChainStepSource.INVENTORY -> "库存已拥有"
            WbccuChainStepSource.MANUAL_OWNED -> "手动已拥有"
            WbccuChainStepSource.MANUAL -> "需买"
            WbccuChainStepSource.WAITING_WB -> "等 WB"
            WbccuChainStepSource.CURRENT_WB -> "当前 WB"
            WbccuChainStepSource.IMPORTED -> "分享导入"
        }
    }

    private fun saleLabel(type: WbccuSaleType): String {
        return when (type) {
            WbccuSaleType.BASE -> "起点"
            WbccuSaleType.WARBOND -> "WB"
            WbccuSaleType.STANDARD -> "标准"
            WbccuSaleType.UNKNOWN -> ""
        }
    }

    private fun formLayout(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }
    }

    private fun textInput(hintValue: String): EditText {
        return EditText(requireContext()).apply {
            hint = hintValue
            setSingleLine(true)
            setTextColor(Color.parseColor("#d8eaf2"))
            setHintTextColor(Color.parseColor("#7c95a8"))
        }
    }

    private fun shipAutoInput(hintValue: String): AutoCompleteTextView {
        return AutoCompleteTextView(requireContext()).apply {
            hint = hintValue
            threshold = 1
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.parseColor("#d8eaf2"))
            setHintTextColor(Color.parseColor("#7c95a8"))
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, shipOptions))
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && text.isNotBlank()) showDropDown()
            }
        }
    }

    private fun moneyInput(hintValue: String): EditText {
        return textInput(hintValue).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
    }

    private fun matchText(): TextView {
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dpToPx() }
            textSize = 12f
            setTextColor(Color.parseColor("#21d4ff"))
            visibility = View.GONE
        }
    }

    private fun bindShipAutoMatch(
        shipInput: EditText,
        priceInput: EditText,
        matchText: TextView,
        onMatch: (WbccuShipMatch?) -> Unit
    ) {
        shipInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty()
                val match = shipOptionForInput(raw)?.match ?: WbccuShipMatcher.match(
                    query = raw,
                    shipPrices = shipPrices,
                    shipAliases = shipAliases
                )
                onMatch(match)
                if (match == null) {
                    matchText.visibility = View.GONE
                    return
                }
                matchText.visibility = View.VISIBLE
                matchText.text = "已匹配：${match.displayName} / ${match.name} · ${money(match.priceCents)}"
                priceInput.setText(formatMoneyInput(match.priceCents))
                priceInput.setSelection(priceInput.text.length)
            }
        })
    }

    private fun buildShipOptions(): List<ShipOption> {
        return shipPrices
            .filter { it.name.isNotBlank() && it.priceCents > 0 }
            .distinctBy { WbccuChainValidator.normalizeShipName(it.name) }
            .map { price ->
                val display = translated(price.name)
                ShipOption(
                    label = if (display == price.name) {
                        "${price.name} · ${money(price.priceCents)}"
                    } else {
                        "$display / ${price.name} · ${money(price.priceCents)}"
                    },
                    match = WbccuShipMatch(
                        name = price.name,
                        displayName = display,
                        priceCents = price.priceCents
                    )
                )
            }
            .sortedBy { it.label }
    }

    private fun shipOptionForInput(raw: String): ShipOption? {
        return shipOptions.firstOrNull { it.label == raw.trim() }
    }

    private fun emptyText(): TextView {
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER
            text = "暂无链路节点"
            textSize = 13f
            setTextColor(Color.parseColor("#4a6377"))
            setPadding(0, 24.dpToPx(), 0, 24.dpToPx())
        }
    }

    private fun metaText(value: String): TextView {
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() }
            text = value
            textSize = 12f
            setTextColor(Color.parseColor("#7c95a8"))
        }
    }

    private fun roundedBg(fill: String, stroke: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8.dpToPx().toFloat()
            setColor(Color.parseColor(fill))
            setStroke(1.dpToPx(), Color.parseColor(stroke))
        }
    }

    private fun tintDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#07111c")))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#21d4ff"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#7c95a8"))
    }

    private fun parseMoney(raw: String): Int {
        val normalized = raw.trim()
            .removePrefix("$")
            .replace(",", "")
        if (normalized.isBlank()) return 0
        return ((normalized.toDoubleOrNull() ?: return 0) * 100).toInt()
    }

    private fun formatMoneyInput(cents: Int): String {
        return if (cents % 100 == 0) {
            (cents / 100).toString()
        } else {
            "%.2f".format(Locale.US, cents / 100.0)
        }
    }

    private fun money(cents: Int): String {
        if (cents <= 0) return "-"
        val value = cents / 100.0
        return if (cents % 100 == 0) {
            "$${value.toInt()}"
        } else {
            "$${"%.2f".format(Locale.US, value)}"
        }
    }

    private fun saving(cents: Int): String {
        return when {
            cents > 0 -> "+${money(cents)}"
            cents == 0 -> "-"
            else -> "-${money(-cents)}"
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("wbccu_chain", feature)
    }

    companion object {
        private fun emptyChain(): WbccuChain {
            return WbccuChain(
                id = WbccuChainCodec.newId("chain"),
                name = "我的 WBCCU 链路",
                steps = emptyList(),
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private data class ShipOption(
        val label: String,
        val match: WbccuShipMatch
    ) {
        override fun toString(): String = label
    }
}
