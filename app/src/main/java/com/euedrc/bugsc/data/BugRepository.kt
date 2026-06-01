package com.euedrc.bugsc.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BugRepository(context: Context) {

    private val prefs = context.getSharedPreferences("bug_storage", Context.MODE_PRIVATE)

    private fun getFavoriteIds(): MutableList<String> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return mutableListOf()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }.toMutableList()
    }

    private fun setFavoriteIds(ids: List<String>) {
        prefs.edit().putString(KEY_FAVORITES, JSONArray(ids).toString()).apply()
    }

    private fun getUserBugs(): MutableList<Bug> {
        val json = prefs.getString(KEY_USER_BUGS, null) ?: return mutableListOf()
        return parseBugList(json).toMutableList()
    }

    private fun setUserBugs(list: List<Bug>) {
        prefs.edit().putString(KEY_USER_BUGS, bugsToJson(list)).apply()
    }

    private fun getAllBugs(): List<Bug> = SAMPLE_BUGS + getUserBugs()

    fun getBugList(
        query: String = "",
        gpuVendor: String = "不限",
        cpuVendor: String = "不限",
        vramCapGB: Int = 0,
        ramCapGB: Int = 0,
        typeTags: List<String> = emptyList(),
        onlyFavorites: Boolean = false,
        sort: String = "updatedAt"
    ): List<Bug> {
        val favoriteIds = getFavoriteIds()
        val q = query.trim().lowercase()

        val filtered = getAllBugs().filter { bug ->
            if (q.isNotEmpty() && !matchQuery(bug, q)) return@filter false
            if (typeTags.isNotEmpty() && !typeTags.any { tag -> bug.typeTags.contains(tag) }) return@filter false
            if (!matchVendor(gpuVendor, bug.hardware.gpuVendors)) return@filter false
            if (!matchVendor(cpuVendor, bug.hardware.cpuVendors)) return@filter false
            if (vramCapGB > 0 && bug.hardware.vramMinGB > vramCapGB) return@filter false
            if (ramCapGB > 0 && bug.hardware.ramMinGB > ramCapGB) return@filter false
            if (onlyFavorites && bug.id !in favoriteIds) return@filter false
            true
        }

        val sorted = if (sort == "severity") {
            filtered.sortedWith(compareByDescending<Bug> { SEVERITY_WEIGHT[it.severity] ?: 0 }
                .thenByDescending { it.updatedAt })
        } else {
            filtered.sortedByDescending { it.updatedAt }
        }

        return sorted.map { bug -> bug.copy() to (bug.id in favoriteIds) }
            .map { (bug, fav) -> bug to fav }
            .map { (bug, fav) -> bug }
    }

    fun getBugById(id: String): Bug? {
        val bug = getAllBugs().find { it.id == id } ?: return null
        return bug
    }

    fun isFavorite(id: String): Boolean = id in getFavoriteIds()

    fun createLocalBug(input: Bug): Bug {
        val now = System.currentTimeMillis()
        val bug = input.copy(
            id = "bug_local_$now",
            source = "user",
            author = Author(),
            createdAt = now,
            updatedAt = now
        )

        val current = getUserBugs()
        setUserBugs(listOf(bug) + current)
        return bug
    }

    fun exportBugShareCode(id: String): String? {
        val bug = getBugById(id) ?: return null
        val payload = JSONObject().apply {
            put("version", 1)
            put("type", "bug-share")
            put("exportedAt", System.currentTimeMillis())
            put("bug", bugToJson(bug))
        }
        val base64 = Base64.encodeToString(payload.toString().toByteArray(), Base64.NO_WRAP)
        return "BUG|$base64"
    }

    fun importBugShareCode(code: String): ImportResult {
        val raw = code.trim()
        if (!raw.startsWith("BUG|")) throw IllegalArgumentException("分享码格式错误")

        val json: JSONObject
        try {
            val decoded = String(Base64.decode(raw.substring(4), Base64.NO_WRAP))
            json = JSONObject(decoded)
        } catch (e: Exception) {
            throw IllegalArgumentException("分享码解析失败")
        }

        if (json.optInt("version") != 1 || json.optString("type") != "bug-share" || !json.has("bug")) {
            throw IllegalArgumentException("分享码内容无效")
        }

        val incoming = parseBug(json.getJSONObject("bug"))
        val builtInConflict = SAMPLE_BUGS.any { it.id == incoming.id }
        val userList = getUserBugs()

        val targetId = if (builtInConflict) "bug_import_${System.currentTimeMillis()}" else incoming.id
        val normalized = incoming.copy(id = targetId, updatedAt = System.currentTimeMillis())

        val userIdx = userList.indexOfFirst { it.id == targetId }
        if (userIdx >= 0) {
            userList[userIdx] = normalized
        } else {
            userList.add(0, normalized)
        }

        setUserBugs(userList)
        val existsInAll = getAllBugs().any { it.id == targetId }
        return ImportResult(id = targetId, created = !existsInAll || builtInConflict)
    }

    fun toggleFavorite(id: String): Boolean {
        val favs = getFavoriteIds()
        val exists = id in favs
        if (exists) favs.remove(id) else favs.add(id)
        setFavoriteIds(favs)
        return !exists
    }

    private fun matchQuery(bug: Bug, q: String): Boolean {
        val pool = listOfNotNull(
            bug.title, bug.summary, bug.description,
            *bug.steps.toTypedArray(), bug.notes,
            *bug.typeTags.toTypedArray(),
            *bug.hardwareKeywords.toTypedArray(),
            *bug.hardware.gpuVendors.toTypedArray(),
            *bug.hardware.cpuVendors.toTypedArray(),
            bug.hardware.vramMinGB.toString(),
            bug.hardware.ramMinGB.toString()
        ).joinToString(" ").lowercase()
        return q in pool
    }

    private fun matchVendor(filter: String, vendors: List<String>): Boolean {
        if (filter == "不限" || filter.isEmpty()) return true
        return filter in vendors || "Any" in vendors
    }

    private fun bugToJson(bug: Bug): JSONObject = JSONObject().apply {
        put("id", bug.id)
        put("title", bug.title)
        put("summary", bug.summary)
        put("description", bug.description)
        put("typeTags", JSONArray(bug.typeTags))
        put("severity", bug.severity)
        put("hardware", JSONObject().apply {
            put("gpuVendors", JSONArray(bug.hardware.gpuVendors))
            put("vramMinGB", bug.hardware.vramMinGB)
            put("ramMinGB", bug.hardware.ramMinGB)
            put("cpuVendors", JSONArray(bug.hardware.cpuVendors))
        })
        put("steps", JSONArray(bug.steps))
        put("notes", bug.notes)
        put("updatedAt", bug.updatedAt)
    }

    private fun bugsToJson(list: List<Bug>): String {
        val arr = JSONArray()
        list.forEach { arr.put(bugToJson(it)) }
        return arr.toString()
    }

    private fun parseBug(json: JSONObject): Bug = Bug(
        id = json.optString("id"),
        title = json.optString("title"),
        summary = json.optString("summary"),
        description = json.optString("description"),
        typeTags = jsonArrayToList(json.optJSONArray("typeTags")),
        severity = json.optString("severity", "medium"),
        hardware = parseHardware(json.optJSONObject("hardware")),
        steps = jsonArrayToList(json.optJSONArray("steps")),
        notes = json.optString("notes"),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun parseBugList(json: String): List<Bug> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { parseBug(arr.getJSONObject(it)) }
    }

    private fun parseHardware(json: JSONObject?): Hardware {
        if (json == null) return Hardware()
        return Hardware(
            gpuVendors = jsonArrayToList(json.optJSONArray("gpuVendors")).ifEmpty { listOf("Any") },
            vramMinGB = json.optInt("vramMinGB"),
            ramMinGB = json.optInt("ramMinGB"),
            cpuVendors = jsonArrayToList(json.optJSONArray("cpuVendors")).ifEmpty { listOf("Any") }
        )
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    data class ImportResult(val id: String, val created: Boolean)

    companion object {
        private const val KEY_FAVORITES = "bug_favorites"
        private const val KEY_USER_BUGS = "bug_user_submissions"
        private val SEVERITY_WEIGHT = mapOf("high" to 3, "medium" to 2, "low" to 1)
    }
}
