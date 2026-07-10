package com.euedrc.bugsc.market.publish

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.market.transaction.AddressBranch
import com.euedrc.bugsc.market.transaction.AddressChoice
import com.euedrc.bugsc.market.transaction.AddressTree
import com.euedrc.bugsc.requireScmLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

class MarketOrderEditFragment : Fragment() {
    private val marketPublish get() = AppServices.marketPublish
    private val transactions get() = AppServices.transactions
    private val itemRows = mutableListOf<ItemRow>()
    private var creatorType = PublishCreatorType.SELL
    private var selectedLocationId: Long? = null
    private var pendingUploadRow: ItemRow? = null

    private lateinit var results: LinearLayout
    private lateinit var itemsContainer: LinearLayout
    private lateinit var locationText: TextView
    private lateinit var expireSpinner: Spinner
    private lateinit var visibleCheck: CheckBox
    private lateinit var submit: Button

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val row = pendingUploadRow ?: return@registerForActivityResult
        pendingUploadRow = null
        if (uri != null) uploadImage(row, uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_market_order_edit, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        requireScmLogin { bind(view) }
    }

    private fun bind(view: View) {
        results = view.findViewById(R.id.container_item_results)
        itemsContainer = view.findViewById(R.id.container_publish_items)
        locationText = view.findViewById(R.id.tv_publish_location)
        expireSpinner = view.findViewById(R.id.spinner_expire)
        visibleCheck = view.findViewById(R.id.check_visible)
        submit = view.findViewById(R.id.btn_submit_order)

        val sell = view.findViewById<Button>(R.id.btn_publish_sell)
        val buy = view.findViewById<Button>(R.id.btn_publish_buy)
        sell.setOnClickListener {
            track("type_sell")
            setCreatorType(PublishCreatorType.SELL, sell, buy)
        }
        buy.setOnClickListener {
            track("type_buy")
            setCreatorType(PublishCreatorType.BUY, sell, buy)
        }
        setCreatorType(PublishCreatorType.SELL, sell, buy)

        val search = view.findViewById<EditText>(R.id.et_item_search)
        val searchButton = view.findViewById<Button>(R.id.btn_item_search)
        searchButton.setOnClickListener { searchItems(search.text.toString()) }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchItems(search.text.toString())
                true
            } else {
                false
            }
        }

        expireSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            PublishExpireTime.entries.map { it.label },
        )
        loadAddressSpinners(view)
        submit.setOnClickListener { submitOrder() }
    }

    private fun setCreatorType(type: PublishCreatorType, sell: Button, buy: Button) {
        creatorType = type
        val accent = resources.getColor(R.color.sc_accent, null)
        val panel = resources.getColor(R.color.sc_panel2, null)
        val ink = resources.getColor(R.color.sc_ink, null)
        val mid = resources.getColor(R.color.sc_text_mid, null)
        sell.backgroundTintList = ColorStateList.valueOf(if (type == PublishCreatorType.SELL) accent else panel)
        buy.backgroundTintList = ColorStateList.valueOf(if (type == PublishCreatorType.BUY) accent else panel)
        sell.setTextColor(if (type == PublishCreatorType.SELL) ink else mid)
        buy.setTextColor(if (type == PublishCreatorType.BUY) ink else mid)
    }

    private fun searchItems(raw: String) {
        track("search_item")
        val keyword = raw.trim()
        if (keyword.isBlank()) {
            toast("请输入物品名称")
            return
        }
        results.removeAllViews()
        addSmallText(results, "搜索中…", R.color.sc_text_dim)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { marketPublish.searchItems(keyword) } }
            results.removeAllViews()
            result.onFailure { addSmallText(results, "搜索失败：${it.message ?: "网络错误"}", R.color.sc_danger) }
                .onSuccess { list ->
                    if (list.isEmpty()) {
                        addSmallText(results, "未找到物品", R.color.sc_text_dim)
                    } else {
                        list.take(10).forEach { item -> results.addView(buildSearchResult(item)) }
                    }
                }
        }
    }

    private fun buildSearchResult(item: ItemSearchResult): View =
        Button(requireContext()).apply {
            text = "添加：${item.itemName}"
            isAllCaps = false
            setTextColor(resources.getColor(R.color.sc_accent, null))
            backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.sc_panel2, null))
            setOnClickListener { addItem(item) }
        }

    private fun addItem(item: ItemSearchResult) {
        track("add_item")
        if (itemRows.any { it.item.id == item.id }) {
            toast("已添加该物品")
            return
        }
        val row = ItemRow(item)
        itemRows += row
        itemsContainer.addView(row.view)
        refreshImageStatus(row)
    }

    private fun refreshImageStatus(row: ItemRow) {
        row.imageStatus.text = "图片状态加载中…"
        row.upload.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { marketPublish.imageStatus(row.item.id) }.getOrNull()
            }
            row.imageStatus.text = when (status?.status) {
                0 -> "图片审核中"
                1 -> "已有审核通过图片，可继续提交更好的图片"
                2 -> "图片曾被拒绝，可重新提交"
                else -> "暂无图片，可上传"
            }
            row.upload.isEnabled = ItemImageStatus.canUpload(status)
        }
    }

    private fun uploadImage(row: ItemRow, uri: Uri) {
        val mime = requireContext().contentResolver.getType(uri).orEmpty()
        if (mime !in marketPublish.allowedMimeTypes) {
            toast("只支持 JPG、PNG、WebP")
            return
        }
        row.upload.isEnabled = false
        row.imageStatus.text = "上传中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = copyToCache(uri, mime)
                    marketPublish.uploadImage(row.item.id, file, mime)
                }
            }
            result.onFailure {
                row.imageStatus.text = it.message ?: "上传失败"
                row.upload.isEnabled = true
            }.onSuccess {
                toast(it.message)
                refreshImageStatus(row)
            }
        }
    }

    private fun copyToCache(uri: Uri, mime: String): File {
        val ext = when (mime) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val name = queryDisplayName(uri)?.substringBeforeLast('.')?.takeIf(String::isNotBlank) ?: "item-image"
        val file = File(requireContext().cacheDir, "${name.take(24)}-${System.currentTimeMillis()}$ext")
        requireContext().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取图片" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun queryDisplayName(uri: Uri): String? =
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun submitOrder() {
        track("submit_order")
        val drafts = itemRows.map {
            PublishItemDraft(
                item = it.item,
                quantityText = it.quantity.text.toString(),
                priceText = it.price.text.toString(),
                qualityText = it.quality.text.toString(),
            )
        }
        val draft = PublishOrderDraft(
            creatorType = creatorType,
            locationId = selectedLocationId,
            status = if (visibleCheck.isChecked) PublishOrderStatus.VISIBLE else PublishOrderStatus.HIDDEN,
            expireTimeType = PublishExpireTime.entries[expireSpinner.selectedItemPosition.coerceIn(0, PublishExpireTime.entries.lastIndex)],
            items = drafts,
        )
        val validation = draft.validate()
        locationText.text = validation.locationError ?: "交易地点已选择"
        locationText.setTextColor(resources.getColor(if (validation.locationError == null) R.color.sc_text else R.color.sc_danger, null))
        itemRows.zip(validation.itemErrors).forEach { (row, error) ->
            row.quantity.error = error.quantityError
            row.price.error = error.priceError
            row.quality.error = error.qualityError
        }
        if (!validation.isValid) {
            toast(validation.itemsError ?: "请检查表单")
            return
        }
        submit.isEnabled = false
        submit.text = "正在创建…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val manifestId = if (validation.items.size > 1) {
                        marketPublish.createItemList(listTitle(validation.items), validation.items)
                    } else {
                        null
                    }
                    marketPublish.createOrder(
                        creatorType = draft.creatorType,
                        locationId = selectedLocationId!!,
                        status = draft.status,
                        expireTime = draft.expireTimeType,
                        items = validation.items,
                        manifestId = manifestId,
                    )
                    marketPublish.ownOrders(1, creatorType = draft.creatorType)
                        .let { MarketPublishJson.findCreatedOrderNumber(it, draft.creatorType, validation.items.first().item.itemName) }
                }
            }
            submit.isEnabled = true
            submit.text = "创建挂单"
            result.onFailure {
                Toast.makeText(requireContext(), it.message ?: "创建失败", Toast.LENGTH_LONG).show()
            }.onSuccess { orderNumber ->
                showCreatedDialog(orderNumber)
            }
        }
    }

    private fun listTitle(items: List<PublishItemValue>): String =
        if (items.size <= 1) items.firstOrNull()?.item?.itemName.orEmpty()
        else "${items.first().item.itemName} 等 ${items.size} 件"

    private fun showCreatedDialog(orderNumber: String) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("创建成功")
            .setMessage(if (orderNumber.isBlank()) "挂单已创建，可在“我的挂单”中查看。" else "订单编号：$orderNumber")
            .setNegativeButton("我的挂单") { _, _ ->
                track("open_my_orders")
                findNavController().navigate(R.id.MyMarketOrdersFragment)
            }
        if (orderNumber.isNotBlank()) {
            builder.setPositiveButton("查看详情") { _, _ ->
                track("open_order_detail")
                findNavController().navigate(R.id.MarketDetailFragment, bundleOf("orderNumber" to orderNumber))
            }
        } else {
            builder.setPositiveButton("返回市场") { _, _ ->
                track("back_to_market")
                findNavController().popBackStack()
            }
        }
        builder.show()
    }

    private fun loadAddressSpinners(view: View) {
        val system = view.findViewById<Spinner>(R.id.spinner_publish_system)
        val planet = view.findViewById<Spinner>(R.id.spinner_publish_planet)
        val station = view.findViewById<Spinner>(R.id.spinner_publish_station)
        selectedLocationId = null
        bindSpinner(planet, "请先选择星系", emptyList()) {}
        bindSpinner(station, "请先选择星体", emptyList()) {}
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AddressTree.build(transactions.addressList()) }
            }
            result.onFailure {
                locationText.text = "地点加载失败，点击重试"
                locationText.setTextColor(resources.getColor(R.color.sc_danger, null))
                locationText.setOnClickListener { loadAddressSpinners(view) }
            }.onSuccess { tree ->
                locationText.setOnClickListener(null)
                bindSpinner(system, "请选择星系", tree.roots) { systemBranch ->
                    selectedLocationId = null
                    bindSpinner(station, "请先选择星体", emptyList()) {}
                    if (systemBranch.children.isEmpty()) selectLocation(systemBranch) else promptLocation("请选择星体")
                    bindSpinner(planet, "请选择星体", systemBranch.children) { planetBranch ->
                        selectedLocationId = null
                        if (planetBranch.children.isEmpty()) selectLocation(planetBranch) else promptLocation("请选择站点")
                        bindSpinner(station, "请选择站点", planetBranch.children) { stationBranch -> selectLocation(stationBranch) }
                    }
                }
            }
        }
    }

    private fun selectLocation(branch: AddressBranch) {
        selectedLocationId = branch.node.id
        locationText.text = branch.node.name
        locationText.setTextColor(resources.getColor(R.color.sc_text, null))
    }

    private fun promptLocation(text: String) {
        locationText.text = text
        locationText.setTextColor(resources.getColor(R.color.sc_text_dim, null))
    }

    private fun bindSpinner(spinner: Spinner, prompt: String, branches: List<AddressBranch>, onSelected: (AddressBranch) -> Unit) {
        val choices = AddressChoice.withPrompt(prompt, branches)
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
        spinner.isEnabled = branches.isNotEmpty()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                choices.getOrNull(position)?.branch?.let(onSelected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun addSmallText(parent: LinearLayout, text: String, colorRes: Int) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(resources.getColor(colorRes, null))
        })
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private inner class ItemRow(val item: ItemSearchResult) {
        val quantity = EditText(requireContext())
        val price = EditText(requireContext())
        val quality = EditText(requireContext())
        val upload = Button(requireContext())
        val imageStatus = TextView(requireContext())
        val view: View = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 10.dp }
            addView(TextView(requireContext()).apply {
                text = item.itemName
                textSize = 15f
                setTextColor(resources.getColor(R.color.sc_text, null))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(fieldRow("数量", quantity, "1", "number"))
            addView(fieldRow("单价 UEC", price, "", "decimal"))
            addView(fieldRow("品质 0-1000（可选）", quality, "", "number"))
            imageStatus.textSize = 12f
            imageStatus.setTextColor(resources.getColor(R.color.sc_text_dim, null))
            addView(imageStatus)
            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                upload.text = "上传图片"
                upload.isAllCaps = false
                upload.setOnClickListener {
                    track("pick_image")
                    pendingUploadRow = this@ItemRow
                    imagePicker.launch("image/*")
                }
                addView(upload, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { marginEnd = 6.dp })
                addView(Button(requireContext()).apply {
                    text = "移除"
                    isAllCaps = false
                    setTextColor(resources.getColor(R.color.sc_danger, null))
                    setOnClickListener {
                        itemRows.remove(this@ItemRow)
                        itemsContainer.removeView(this@ItemRow.view)
                    }
                }, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { marginStart = 6.dp })
            }
            addView(actions)
        }

        private fun fieldRow(label: String, input: EditText, value: String, kind: String): View =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    text = label
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.sc_text_mid, null))
                })
                input.setText(value)
                input.setTextColor(resources.getColor(R.color.sc_text, null))
                input.setHintTextColor(resources.getColor(R.color.sc_text_dim, null))
                input.inputType = if (kind == "decimal") android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else android.text.InputType.TYPE_CLASS_NUMBER
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp))
            }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("market_order_edit", feature)
    }
}
