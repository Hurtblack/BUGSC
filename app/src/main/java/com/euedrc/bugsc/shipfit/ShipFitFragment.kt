package com.euedrc.bugsc.shipfit

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.data.OnlineShipDetail
import com.euedrc.bugsc.databinding.FragmentShipFitBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ShipFitFragment : Fragment() {
    companion object {
        private const val TAG = "ShipFitFragment"
    }

    private var _binding: FragmentShipFitBinding? = null
    private val binding get() = _binding!!
    private var ships: List<ShipCard> = emptyList()
    private var currentShip: ShipCard? = null
    private var shipDisplayMap: Map<String, ShipCard> = emptyMap()
    private var remoteSearchToken = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentShipFitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadData()
        binding.btnLoadout.setOnClickListener {
            val ship = currentShip ?: return@setOnClickListener
            track("open_loadout")
            val bundle = Bundle().apply { putString("shipId", ship.id) }
            findNavController().navigate(R.id.action_ShipFitFragment_to_ShipLoadoutFragment, bundle)
        }
        binding.btnOfficialUrl.setOnClickListener {
            val url = currentShip?.officialUrl ?: return@setOnClickListener
            track("open_official_url")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun loadData() {
        val repo = ShipFitDataRepository(requireContext())
        ships = repo.loadShips()
        val names = ships.map { ShipSearchResolver.displayLabel(it) }
        shipDisplayMap = ships.associateBy { ShipSearchResolver.displayLabel(it) }
        binding.etShipId.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        )
        binding.etShipId.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position)?.toString().orEmpty()
            selectShipFromInput(selected, showMissingToast = true)
        }
        binding.etShipId.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            val isEnterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (isSearchAction || isEnterUp) {
                selectShipFromInput(binding.etShipId.text?.toString().orEmpty(), showMissingToast = true)
                true
            } else {
                false
            }
        }
        binding.etShipId.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                selectShipFromInput(binding.etShipId.text?.toString().orEmpty(), showMissingToast = false)
            }
        }
    }

    private fun selectShipFromInput(input: String, showMissingToast: Boolean) {
        val selected = input.trim()
        if (selected.isBlank()) return
        val ship = shipDisplayMap[selected] ?: ShipSearchResolver.resolve(selected, ships)
        if (ship == null) {
            searchOnlineShip(selected, showMissingToast)
            return
        }
        track("select_ship")
        currentShip = ship
        binding.etShipId.setText(ShipSearchResolver.displayLabel(ship), false)
        binding.etShipId.dismissDropDown()
        renderShipCard(ship)
        binding.btnLoadout.visibility = View.VISIBLE
        binding.btnOfficialUrl.visibility = if (ship.officialUrl != null) View.VISIBLE else View.GONE
    }

    /** 本地资产没有命中时，按用户当前输入查询 SCAPI；不会预取或批量同步。 */
    private fun searchOnlineShip(input: String, showMissingToast: Boolean) {
        val token = ++remoteSearchToken
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val hit = AppServices.shipOnline.searchShips(input, 5).firstOrNull()
                        ?: return@runCatching null
                    AppServices.shipOnline.getShipDetail(hit.id)
                }.getOrNull()
            }
            if (token != remoteSearchToken || !isAdded) return@launch
            if (result == null) {
                if (showMissingToast) toast("未找到对应船只：$input")
                return@launch
            }
            track("select_online_ship")
            binding.etShipId.setText(result.nameCn ?: result.nameEn ?: result.id, false)
            binding.etShipId.dismissDropDown()
            renderOnlineShip(result)
            binding.btnLoadout.visibility = View.GONE
            binding.btnOfficialUrl.visibility = View.GONE
        }
    }

    private fun renderOnlineShip(detail: OnlineShipDetail) {
        val root = runCatching { JSONObject(detail.rawJson) }.getOrNull()
        if (root == null) {
            binding.tvShipCard.text = "线上详情格式异常：${detail.id}"
            return
        }
        val name = detail.nameCn ?: detail.nameEn ?: detail.title
        val dimensions = root.optJSONObject("dimensions")?.let { dimensions ->
            listOf("length", "beam", "height").mapNotNull { key ->
                dimensions.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { "$key=$it" }
            }.joinToString(" / ").takeIf { it.isNotBlank() }
        }
        val hardpoints = root.optJSONObject("hardpoints")
        val hardpointGroups = hardpoints?.keys()?.asSequence()?.mapNotNull { key ->
            val count = hardpoints.optJSONObject(key)?.optJSONArray("items")?.length() ?: 0
            if (count > 0) "$key=$count" else null
        }?.toList().orEmpty()
        binding.tvShipCard.text = buildString {
            append("【线上资料】\n")
            append("舰船：$name")
            detail.nameEn?.takeIf { it != name }?.let { append(" / $it") }
            append("\nID：${detail.id}\n")
            root.optString("manufacturer_cn").takeIf { it.isNotBlank() }?.let { append("制造商：$it\n") }
            root.optString("role").takeIf { it.isNotBlank() }?.let { append("定位：$it\n") }
            root.opt("cargo")?.takeUnless { it == JSONObject.NULL }?.let { append("货舱：$it\n") }
            dimensions?.let { append("尺寸：$it\n") }
            if (hardpointGroups.isNotEmpty()) append("挂点：${hardpointGroups.joinToString("；")}\n")
            append("\n数据来自 SCAPI 线上详情，可在 AI 中继续查询挂点和组件。")
        }.trimEnd()
        binding.ivShip.visibility = View.GONE
        binding.ivShip.setImageDrawable(null)
        binding.ivShip.tag = null
    }

    private fun renderShipCard(ship: ShipCard) {
        val displaySlots = ship.slots
        val typeCounts = summarizeShipTypes(displaySlots)
        val sizeSummary = summarizeTypeSizes(displaySlots)
        val text = buildString {
            append("【基本信息】\n")
            append("舰船：${ship.zhName?.let { "$it (${ship.name})" } ?: ship.name}\n")
            ship.size?.let { append("尺寸：$it\n") }
            ship.crew?.let { append("乘员：$it\n") }
            ship.cargo?.let { append("货舱：$it\n") }
            ship.salePriceCents?.let { append("售卖价：${formatUsdPrice(it)}\n") }
            append("\n【组件信息】\n")
            append("电源：${typeLabel(typeCounts, sizeSummary, "power_plant")}  冷却：${typeLabel(typeCounts, sizeSummary, "cooler")}  护盾：${typeLabel(typeCounts, sizeSummary, "shield_generator")}\n")
            append("量子：${typeLabel(typeCounts, sizeSummary, "quantum_drive")}  雷达：${typeLabel(typeCounts, sizeSummary, "radar")}\n")
            append("\n【武器信息】\n")
            append("武器：${typeLabel(typeCounts, sizeSummary, "weapon_gun")}  导弹架：${typeLabel(typeCounts, sizeSummary, "missile_rack")}  炮塔：${typeLabel(typeCounts, sizeSummary, "turret")}\n")
            if (ship.enginePortCount > 0) append("推进端口：${ship.enginePortCount}\n")
        }
        binding.tvShipCard.text = text.trimEnd()
        binding.ivShip.visibility = View.GONE
        binding.ivShip.setImageDrawable(null)
        binding.ivShip.tag = null
        loadShipImage(ship.imageUrl, ship.backupImageUrl)
    }

    private fun formatUsdPrice(cents: Int): String {
        val dollars = cents / 100
        val remainder = cents % 100
        val base = "$" + String.format(Locale.US, "%,d", dollars)
        return if (remainder == 0) base else "$base.${remainder.toString().padStart(2, '0')}"
    }

    private fun loadShipImage(url: String?, backupUrl: String?) {
        if (url.isNullOrBlank()) return
        val candidates = listOfNotNull(url, backupUrl).distinct()
        binding.ivShip.tag = candidates.firstOrNull()
        binding.ivShip.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            var loaded = false
            for (candidate in candidates) {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val conn = URL(candidate).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.setRequestProperty("Referer", "https://robertsspaceindustries.com/")
                        val code = conn.responseCode
                        if (code !in 200..299) return@runCatching null
                        try {
                            BitmapFactory.decodeStream(conn.inputStream)
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrNull()
                }
                if (binding.ivShip.tag == candidates.firstOrNull() && bitmap != null) {
                    binding.ivShip.setImageBitmap(bitmap)
                    loaded = true
                    break
                }
            }
            if (!loaded && binding.ivShip.tag == candidates.firstOrNull()) {
                binding.ivShip.visibility = View.GONE
            }
        }
    }

    private fun summarizeShipTypes(slots: List<ShipSlot>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        slots.forEach { s ->
            s.types.mapNotNull { mapErkulTypeToUexType(it) }.distinct().forEach { t ->
                out[t] = (out[t] ?: 0) + 1
            }
        }
        return out
    }

    private fun summarizeTypeSizes(slots: List<ShipSlot>): Map<String, Map<Int, Int>> {
        val out = mutableMapOf<String, MutableMap<Int, Int>>()
        slots.forEach { s ->
            val size = s.maxSize ?: s.minSize ?: return@forEach
            s.types.mapNotNull { mapErkulTypeToUexType(it) }.distinct().forEach { t ->
                val sizes = out.getOrPut(t) { mutableMapOf() }
                sizes[size] = (sizes[size] ?: 0) + 1
            }
        }
        return out
    }

    private fun typeLabel(
        counts: Map<String, Int>,
        sizeSummary: Map<String, Map<Int, Int>>,
        type: String,
    ): String {
        val count = counts[type] ?: 0
        if (count <= 0) return "0"
        val sizes = sizeSummary[type].orEmpty()
        if (sizes.isEmpty()) return count.toString()
        val sizeText = sizes.entries.sortedBy { it.key }.joinToString("/") { "S${it.key}x${it.value}" }
        return "$count（$sizeText）"
    }

    private fun mapErkulTypeToUexType(t: String): String? = when (t) {
        "PowerPlant" -> "power_plant"
        "Cooler" -> "cooler"
        "Shield" -> "shield_generator"
        "QuantumDrive" -> "quantum_drive"
        "Radar" -> "radar"
        "WeaponGun" -> "weapon_gun"
        "MissileRack" -> "missile_rack"
        "MissileLauncher" -> "missile_rack"
        "Missile" -> "missile"
        "Turret" -> "turret"
        "UtilityTurret" -> "mining_laser"
        "ToolArm" -> "mining_laser"
        "MiningModule" -> "mining_module"
        "WeaponDefensive" -> "missile_rack"
        else -> null
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("ship_fit", feature)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
