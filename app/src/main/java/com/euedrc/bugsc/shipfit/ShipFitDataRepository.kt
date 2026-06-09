package com.euedrc.bugsc.shipfit

import android.content.Context
import org.json.JSONObject

data class ShipCard(
    val id: String,
    val name: String,
    val uexId: Int?,
    val imageUrl: String?,
    val backupImageUrl: String?,
    val size: String?,
    val crew: String?,
    val cargo: String?,
    val officialUrl: String?,
    val enginePortCount: Int,
    val engineSizeScore: Int,
    val slots: List<ShipSlot>,
    val slotPatched: Boolean,
    val zhName: String?,
)

data class ShipSlot(
    val key: String,
    val minSize: Int?,
    val maxSize: Int?,
    val types: List<String>,
)

data class FitComponent(
    val id: String,
    val name: String,
    val type: String,
    val size: Int?,
    val zhName: String?,
    val powerValue: Double? = null,
)

private data class ZhAliasPack(
    val ships: Map<String, String>,
    val components: Map<String, String>,
)

class ShipFitDataRepository(private val context: Context) {

    fun loadShips(): List<ShipCard> {
        val erkul = JSONObject(readAsset("shipfit/erkul_ship_slots_live.json"))
        val uex = JSONObject(readAsset("shipfit/uex_shipfit_dataset.json"))
        val zh = readZhAliasPack()
        val wikiGuns = loadWikiGunSlots()

        val uexBySlug = mutableMapOf<String, JSONObject>()
        val uexByName = mutableMapOf<String, JSONObject>()
        val shipsArr = uex.optJSONArray("ships")
        if (shipsArr != null) {
            for (i in 0 until shipsArr.length()) {
                val s = shipsArr.optJSONObject(i) ?: continue
                s.optString("slug").takeIf { it.isNotBlank() }?.let { uexBySlug[it] = s }
                s.optString("name").takeIf { it.isNotBlank() }?.let { uexByName[it.lowercase()] = s }
            }
        }

        val result = mutableListOf<ShipCard>()
        val erkulShips = erkul.optJSONArray("ships") ?: return emptyList()
        for (i in 0 until erkulShips.length()) {
            val s = erkulShips.optJSONObject(i) ?: continue
            val localName = s.optString("localName")
            val name = s.optString("name")
            val rawSlots = parseSlots(s.optJSONArray("slots"))
            val (fallbackSlots, slotPatched) = applyShipSlotFallback(localName, rawSlots)
            // 用 SC Wiki 的真实枪位替换 erkul 的「枪/武器炮塔」槽：erkul 对焊死炮塔只给
            // 炮塔座、不展开内部武器，导致 F8C 等被算成 2×S2+5×S3（应为 4×S2+4×S3）。
            val slots = replaceGunSlots(fallbackSlots, wikiGuns[localName].orEmpty())
            if (localName.isBlank() || slots.isEmpty()) continue

            val u = uexBySlug[localName] ?: uexByName[name.lowercase()]
            val sizeObj = u?.optJSONObject("size")
            val size = sizeObj?.let {
                val l = it.optInt("length", 0)
                val w = it.optInt("width", 0)
                val h = it.optInt("height", 0)
                if (l > 0 && w > 0 && h > 0) "${l}/${w}/${h}m" else null
            }

            result += ShipCard(
                id = localName,
                name = if (name.isBlank()) localName else name,
                uexId = u?.optInt("id"),
                imageUrl = resolvePrimaryImageUrl(u),
                backupImageUrl = resolveBackupImageUrl(u),
                size = size,
                crew = u?.optString("crew")?.takeIf { it.isNotBlank() && it != "0" },
                cargo = u?.optString("scu")?.takeIf { it.isNotBlank() && it != "0" }?.let { "$it SCU" },
                // TODO: 补齐缺失的官方链接（url_store 为空时建立人工映射/定期校验）。
                officialUrl = u?.optString("url_store")?.takeIf { it.isNotBlank() },
                enginePortCount = s.optInt("enginePortCount", 0),
                engineSizeScore = s.optInt("engineSizeScore", 0),
                slots = slots,
                slotPatched = slotPatched,
                zhName = zh.ships[name]?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
            )
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun loadComponents(): List<FitComponent> {
        val uex = JSONObject(readAsset("shipfit/uex_shipfit_dataset.json"))
        val zh = readZhAliasPack()
        val powerMap = loadComponentPowerMap()
        val arr = uex.optJSONArray("components") ?: return emptyList()
        val list = mutableListOf<FitComponent>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.opt("id")?.toString().orEmpty()
            val name = c.optString("name")
            val type = c.optString("type")
            val size = c.optInt("size").takeIf { it > 0 }
            // 注意：Android 自带 org.json 的 optString 遇到 JSON null 会返回字符串 "null"，
            // 必须用 isNull 判定，否则整船独有件过滤会把 vehicle_name 为 null 的组件全部误杀。
            val vehicleName = if (c.isNull("vehicle_name")) null else c.optString("vehicle_name")
            if (id.isBlank() || name.isBlank() || type.isBlank()) continue
            if (!ShipFitDisplay.isSelectableComponent(vehicleName)) continue
            list += FitComponent(
                id = id,
                name = name,
                type = type,
                size = size,
                zhName = zh.components[name]?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
                powerValue = powerMap[id],
            )
        }
        list += readMiningLaserComponents(zh)
        list += readMiningModuleComponents(zh)
        return list.distinctBy { it.id }
    }

    private fun loadComponentPowerMap(): Map<String, Double> {
        return runCatching {
            val json = JSONObject(readAsset("shipfit/component_power.json"))
            val map = mutableMapOf<String, Double>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = json.optJSONObject(k) ?: continue
                map[k] = obj.optDouble("value", -1.0).takeIf { it >= 0 } ?: continue
            }
            map
        }.getOrDefault(emptyMap())
    }

    /**
     * 从 ship_hardpoints.json（由 tools/gen_ship_hardpoints.py 基于 SC Wiki API 生成）
     * 读取每艘船的真实「枪位」。SC Wiki 递归展开了炮塔内部武器，每个枪位带 min/max 尺寸，
     * 因此能修正 erkul 把焊死炮塔塌缩成单个炮塔座的缺陷。仅取 WeaponGun，导弹/反制仍由 erkul 提供。
     */
    private fun loadWikiGunSlots(): Map<String, List<ShipSlot>> {
        return runCatching {
            val json = JSONObject(readAsset("shipfit/ship_hardpoints.json"))
            val result = mutableMapOf<String, List<ShipSlot>>()
            val shipKeys = json.keys()
            while (shipKeys.hasNext()) {
                val shipId = shipKeys.next()
                val typesObj = json.optJSONObject(shipId) ?: continue
                val slotArr = typesObj.optJSONArray("WeaponGun") ?: continue
                val slots = mutableListOf<ShipSlot>()
                for (i in 0 until slotArr.length()) {
                    val s = slotArr.optJSONObject(i) ?: continue
                    slots += ShipSlot(
                        key = "wiki_weapon_$i",
                        minSize = if (s.isNull("min")) null else s.optInt("min").takeIf { it > 0 },
                        maxSize = if (s.isNull("max")) null else s.optInt("max").takeIf { it > 0 },
                        types = listOf("WeaponGun"),
                    )
                }
                if (slots.isNotEmpty()) result[shipId] = slots
            }
            result
        }.getOrDefault(emptyMap())
    }

    // erkul 中应被 SC Wiki 枪位替换的「枪/武器炮塔」槽：types 仅含 WeaponGun/Turret。
    // 牵引（tractor）、采矿（含 mining 关键字或 UtilityTurret/ToolArm）等非武器炮塔须保留。
    private fun isErkulGunSlot(slot: ShipSlot): Boolean {
        if (slot.types.isEmpty()) return false
        if (slot.types.any { it != "WeaponGun" && it != "Turret" }) return false
        if (slot.key.contains("tractor", ignoreCase = true)) return false
        if (slot.key.contains("mining", ignoreCase = true)) return false
        return true
    }

    /** wiki 有枪位数据时，剔除 erkul 的枪/武器炮塔槽并替换为真实展开的枪位；否则原样返回。 */
    private fun replaceGunSlots(erkul: List<ShipSlot>, wikiGuns: List<ShipSlot>): List<ShipSlot> {
        if (wikiGuns.isEmpty()) return erkul
        return erkul.filterNot { isErkulGunSlot(it) } + wikiGuns
    }

    private fun parseSlots(arr: org.json.JSONArray?): List<ShipSlot> {
        if (arr == null) return emptyList()
        val list = mutableListOf<ShipSlot>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val key = s.optString("port")
            if (key.isBlank()) continue
            val types = mutableListOf<String>()
            val tArr = s.optJSONArray("types")
            if (tArr != null) {
                for (j in 0 until tArr.length()) {
                    tArr.optString(j).takeIf { it.isNotBlank() }?.let { types += it }
                }
            }
            if (types.isEmpty()) continue
            list += ShipSlot(
                key = key,
                minSize = s.optInt("minSize").takeIf { s.has("minSize") && it > 0 },
                maxSize = s.optInt("maxSize").takeIf { s.has("maxSize") && it > 0 },
                types = types.distinct(),
            )
        }
        return list
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun readZhAliasPack(): ZhAliasPack {
        val json = JSONObject(readAsset("shipfit/zh_aliases.json"))
        fun parseMap(key: String): Map<String, String> {
            val obj = json.optJSONObject(key) ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.optString(k)
                if (k.isNotBlank() && v.isNotBlank()) map[k] = v
            }
            return map
        }
        val blueprintItems = readBlueprintItemTranslations()
        return ZhAliasPack(
            ships = parseMap("ships"),
            components = parseMap("components") + blueprintItems,
        )
    }

    private fun readBlueprintItemTranslations(): Map<String, String> = runCatching {
        val root = JSONObject(readAsset("blueprint/scm_translations.json"))
        val obj = root.optJSONObject("items_by_en") ?: return@runCatching emptyMap()
        val map = LinkedHashMap<String, String>(obj.length())
        for (key in obj.keys()) {
            val value = obj.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) map[key] = value
        }
        map
    }.getOrDefault(emptyMap())

    private fun readMiningLaserComponents(zh: ZhAliasPack): List<FitComponent> = runCatching {
        val root = JSONObject(readAsset("blueprint/sccraft_blueprints.json"))
        val obj = root.optJSONObject("blueprints") ?: return@runCatching emptyList()
        val out = ArrayList<FitComponent>()
        for (name in obj.keys()) {
            val bp = obj.optJSONObject(name) ?: continue
            val category = bp.optString("category")
            if (!category.equals("Vehiclegear / Mininglaser", ignoreCase = true)) continue
            out += FitComponent(
                id = "blueprint:$name",
                name = name,
                type = "mining_laser",
                size = parseMiningLaserSize(name, bp.optString("blueprintId")),
                zhName = zh.components[name]?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
            )
        }
        out
    }.getOrDefault(emptyList())

    private fun readMiningModuleComponents(zh: ZhAliasPack): List<FitComponent> = runCatching {
        val moduleNames = setOf(
            "Brandt Module",
            "FLTR Module",
            "FLTR-L Module",
            "FLTR-XL Module",
            "Focus Module",
            "Focus II Module",
            "Focus III Module",
            "Forel Module",
            "Lifeline Module",
            "Optimum Module",
            "Rieger Module",
            "Rieger-C2 Module",
            "Rieger-C3 Module",
            "Rime Module",
            "ROC Module",
            "Stampede Module",
            "Surge Module",
            "Torpid Module",
            "Torrent Module",
            "Torrent II Module",
            "Torrent III Module",
            "Vaux Module",
            "Vaux-C2 Module",
            "Vaux-C3 Module",
            "XTR Module",
            "XTR-L Module",
            "XTR-XL Module",
        )
        moduleNames.map { name ->
            FitComponent(
                id = "blueprint:$name",
                name = name,
                type = "mining_module",
                size = null,
                zhName = zh.components[name]?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
            )
        }
    }.getOrDefault(emptyList())

    private fun parseMiningLaserSize(name: String, blueprintId: String): Int? {
        val haystack = "$name $blueprintId"
        Regex("""(?i)(?:^|[_\-\s])s([0-9])(?:$|[_\-\s])""")
            .find(haystack)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        return when {
            name.contains("MHV", ignoreCase = true) -> 0
            name.contains("MH1", ignoreCase = true) || name.contains(" I ", ignoreCase = true) -> 1
            name.contains("MH2", ignoreCase = true) || name.contains(" II ", ignoreCase = true) -> 2
            else -> null
        }
    }

    private fun resolvePrimaryImageUrl(u: JSONObject?): String? {
        if (u == null) return null
        val raw = u.optString("url_photos").takeIf { it.isNotBlank() } ?: return null
        val firstGallery = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                .firstOrNull()
        }.getOrNull()
        return firstGallery ?: u.optString("url_photo").takeIf { it.isNotBlank() }
    }

    private fun resolveBackupImageUrl(u: JSONObject?): String? {
        if (u == null) return null
        val direct = u.optString("url_photo").takeIf { it.isNotBlank() }
        val raw = u.optString("url_photos").takeIf { it.isNotBlank() } ?: return direct
        val secondGallery = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                .drop(1)
                .firstOrNull()
        }.getOrNull()
        return secondGallery ?: direct
    }

    private fun applyShipSlotFallback(shipId: String, slots: List<ShipSlot>): Pair<List<ShipSlot>, Boolean> {
        val normalizedSlots = normalizeKnownShipSlots(shipId, normalizeMiningSlots(shipId, slots))
        var patched = normalizedSlots != slots
        fun hasType(type: String): Boolean = normalizedSlots.any { it.types.contains(type) }
        fun hasMiningSlot(): Boolean = normalizedSlots.any { slot ->
            slot.types.any { it == "UtilityTurret" || it == "ToolArm" } &&
                slot.key.contains("mining", ignoreCase = true)
        }
        val needsCorePatch = !hasType("PowerPlant") || !hasType("Cooler") || !hasType("Shield")

        val fallback = buildList {
            if (!hasMiningSlot()) {
                when (shipId) {
                    "misc_prospector" -> add(ShipSlot("fallback_mining_laser", 1, 1, listOf("UtilityTurret")))
                    "drak_golem" -> add(ShipSlot("fallback_mining_laser", 1, 1, listOf("UtilityTurret")))
                }
            }
            if (!needsCorePatch) return@buildList
            when (shipId) {
                "rsi_zeus_es" -> addAll(listOf(
                ShipSlot("fallback_power_plant_1", 2, 2, listOf("PowerPlant")),
                ShipSlot("fallback_power_plant_2", 2, 2, listOf("PowerPlant")),
                ShipSlot("fallback_cooler_1", 2, 2, listOf("Cooler")),
                ShipSlot("fallback_cooler_2", 2, 2, listOf("Cooler")),
                ShipSlot("fallback_shield_1", 2, 2, listOf("Shield")),
                ShipSlot("fallback_shield_2", 2, 2, listOf("Shield")),
                ShipSlot("fallback_shield_3", 2, 2, listOf("Shield")),
                ShipSlot("fallback_shield_4", 2, 2, listOf("Shield")),
                ShipSlot("fallback_quantum", 2, 2, listOf("QuantumDrive")),
                ShipSlot("fallback_radar", 3, 3, listOf("Radar")),
                ShipSlot("fallback_weapon_left", 4, 4, listOf("WeaponGun")),
                ShipSlot("fallback_weapon_right", 4, 4, listOf("WeaponGun")),
                ShipSlot("fallback_missile_1", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_2", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_3", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_4", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_5", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_6", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_7", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_missile_8", 2, 2, listOf("MissileLauncher")),
                ShipSlot("fallback_turret", 3, 3, listOf("Turret")),
                ))
                "rsi_polaris" -> addAll(listOf(
                ShipSlot("fallback_power_plant", 4, 4, listOf("PowerPlant")),
                ShipSlot("fallback_cooler", 4, 4, listOf("Cooler")),
                ShipSlot("fallback_shield", 4, 4, listOf("Shield")),
                ShipSlot("fallback_quantum", 3, 3, listOf("QuantumDrive")),
                ShipSlot("fallback_radar", 4, 4, listOf("Radar")),
                ))
            }
        }
        if (fallback.isEmpty()) return normalizedSlots to patched

        val merged = normalizedSlots.toMutableList()
        val existedTypes = normalizedSlots.flatMap { it.types }.toSet()
        val existedKeys = normalizedSlots.map { it.key }.toSet()
        fallback.forEach { slot ->
            val shouldAppend = slot.types.any { it !in existedTypes }
            if (slot.key !in existedKeys && (shouldAppend ||
                    slot.key.startsWith("fallback_mining_") ||
                    slot.key.startsWith("fallback_missile_") ||
                    slot.key.startsWith("fallback_weapon_")
                )) {
                merged += slot
                patched = true
            }
        }
        return merged to patched
    }

    private fun normalizeMiningSlots(shipId: String, slots: List<ShipSlot>): List<ShipSlot> {
        val miningSize = when (shipId) {
            "argo_mole" -> 2
            "grin_roc", "grin_roc_ds" -> 0
            else -> return slots
        }
        return slots.map { slot ->
            val isMiningSlot = slot.key.contains("mining", ignoreCase = true) &&
                slot.types.any { it == "UtilityTurret" || it == "ToolArm" }
            if (isMiningSlot) slot.copy(minSize = miningSize, maxSize = miningSize) else slot
        }
    }

    private fun normalizeKnownShipSlots(shipId: String, slots: List<ShipSlot>): List<ShipSlot> {
        if (shipId != "orig_m80") return slots
        val filtered = slots.filterNot { slot ->
            slot.key == "hardpoint_power_plant" ||
                slot.key == "hardpoint_shield_generator_left" ||
                slot.key == "hardpoint_shield_generator_right" ||
                slot.key == "hardpoint_quantum_drive" ||
                slot.key == "hardpoint_weapon_frontleft" ||
                slot.key == "hardpoint_weapon_frontright" ||
                slot.key == "hardpoint_weapon_rearleft" ||
                slot.key == "hardpoint_weapon_rearright"
        }
        val fixedCoreSlots = listOf(
            ShipSlot("hardpoint_powerplant_left", 2, 2, listOf("PowerPlant")),
            ShipSlot("hardpoint_powerplant_right", 2, 2, listOf("PowerPlant")),
            ShipSlot("hardpoint_shield_generator_left", 2, 2, listOf("Shield")),
            ShipSlot("hardpoint_shield_generator_right", 2, 2, listOf("Shield")),
            ShipSlot("hardpoint_quantum_drive", 2, 2, listOf("QuantumDrive")),
            ShipSlot("hardpoint_weapon_frontleft", 5, 5, listOf("WeaponGun")),
            ShipSlot("hardpoint_weapon_frontright", 5, 5, listOf("WeaponGun")),
            ShipSlot("hardpoint_weapon_rearleft", 4, 4, listOf("WeaponGun")),
            ShipSlot("hardpoint_weapon_rearright", 4, 4, listOf("WeaponGun")),
        )
        val existingKeys = filtered.map { it.key }.toSet()
        return filtered + fixedCoreSlots.filter { it.key !in existingKeys }
    }
}
