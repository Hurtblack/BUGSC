package com.euedrc.bugsc.agent

import com.euedrc.bugsc.mining.MiningElement

class AgentMaterialAliasIndex(elements: List<MiningElement>) {
    private val aliasesByBaseName: Map<String, Set<String>> = elements
        .groupBy { baseMaterialName(it.nameEn).lowercase() }
        .mapValues { (_, values) ->
            values.flatMap { element ->
                buildList {
                    add(element.nameEn)
                    add(baseMaterialName(element.nameEn))
                    element.nameCn?.let { cn ->
                        add(cn)
                        add(cleanChineseName(cn))
                    }
                }
            }.map { it.lowercase().trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }

    fun matches(materialName: String, queryText: String): Boolean {
        val normalizedQuery = queryText.lowercase().trim()
        if (normalizedQuery.isBlank()) return false
        val material = materialName.lowercase()
        if (material.contains(normalizedQuery) || normalizedQuery.contains(material)) return true
        return aliasesByBaseName[baseMaterialName(materialName).lowercase()].orEmpty().any { alias ->
            alias.contains(normalizedQuery) || normalizedQuery.contains(alias)
        }
    }

    companion object {
        private val PAREN_SUFFIX = Regex("""\s*[\(（].*?[\)）].*""")

        fun baseMaterialName(value: String): String =
            value.replace(PAREN_SUFFIX, "").trim()

        fun cleanChineseName(value: String): String =
            value.replace(PAREN_SUFFIX, "").trim()
    }
}
