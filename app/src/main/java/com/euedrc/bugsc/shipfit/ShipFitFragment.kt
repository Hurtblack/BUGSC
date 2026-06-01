package com.euedrc.bugsc.shipfit

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.databinding.FragmentShipFitBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ShipFitFragment : Fragment() {
    companion object {
        private const val TAG = "ShipFitFragment"
    }

    private var _binding: FragmentShipFitBinding? = null
    private val binding get() = _binding!!
    private var ships: List<ShipCard> = emptyList()
    private var currentShip: ShipCard? = null
    private var shipDisplayMap: Map<String, ShipCard> = emptyMap()

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
            val bundle = Bundle().apply { putString("shipId", ship.id) }
            findNavController().navigate(R.id.action_ShipFitFragment_to_ShipLoadoutFragment, bundle)
        }
        binding.btnOfficialUrl.setOnClickListener {
            val url = currentShip?.officialUrl ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun loadData() {
        val repo = ShipFitDataRepository(requireContext())
        ships = repo.loadShips()
        val names = ships.map { shipDisplayLabel(it) }
        shipDisplayMap = ships.associateBy { shipDisplayLabel(it) }
        binding.etShipId.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        )
        binding.etShipId.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position)?.toString().orEmpty()
            val ship = shipDisplayMap[selected] ?: ships.firstOrNull {
                selected.contains(it.id, ignoreCase = true) || selected.contains(it.name, ignoreCase = true)
            }
            if (ship == null) {
                toast("未找到对应船只：$selected")
                return@setOnItemClickListener
            }
            currentShip = ship
            renderShipCard(ship)
            binding.btnLoadout.visibility = View.VISIBLE
            binding.btnOfficialUrl.visibility = if (ship.officialUrl != null) View.VISIBLE else View.GONE
        }
    }

    private fun renderShipCard(ship: ShipCard) {
        val displaySlots = mergeDisplaySlots(ship.slots, ship.wikiSlots)
        val typeCounts = summarizeShipTypes(displaySlots)
        val sizeSummary = summarizeTypeSizes(displaySlots)
        val text = buildString {
            append("【基本信息】\n")
            append("舰船：${ship.zhName?.let { "$it (${ship.name})" } ?: ship.name}\n")
            ship.size?.let { append("尺寸：$it\n") }
            ship.crew?.let { append("乘员：$it\n") }
            ship.cargo?.let { append("货舱：$it\n") }
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

    private fun shipDisplayLabel(ship: ShipCard): String {
        val zh = ship.zhName
        return if (!zh.isNullOrBlank()) "$zh (${ship.name}) (${ship.id})" else "${ship.name} (${ship.id})"
    }

    private fun mergeDisplaySlots(erkul: List<ShipSlot>, wiki: List<ShipSlot>): List<ShipSlot> {
        if (wiki.isEmpty()) return erkul
        val erkulByType = erkul.groupBy { it.types.firstOrNull() ?: "" }.filterKeys { it.isNotBlank() }
        val wikiByType = wiki.groupBy { it.types.firstOrNull() ?: "" }.filterKeys { it.isNotBlank() }
        val allTypes = (erkulByType.keys + wikiByType.keys)
        return allTypes.flatMap { type ->
            val e = erkulByType[type].orEmpty()
            val w = wikiByType[type].orEmpty()
            if (e.size >= w.size) e else w
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
