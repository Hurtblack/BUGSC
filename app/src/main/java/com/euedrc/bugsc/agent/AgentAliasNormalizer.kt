package com.euedrc.bugsc.agent

object AgentAliasNormalizer {
    private val punctuation = Regex("""[，。？！?！、,.;；:：()\[\]{}"'“”‘’/\\_-]""")
    private val latinCode = Regex("""\b[a-z]+\d+[a-z]*\b""", RegexOption.IGNORE_CASE)
    private val parenthesized = Regex("""\s*[\(（].*?[\)）].*""")

    fun expandAliases(vararg values: String?): List<String> =
        values.filterNotNull().flatMap(::expandAlias).distinct()

    fun expandAlias(value: String): List<String> {
        val raw = value.trim()
        if (raw.isBlank()) return emptyList()
        val normalized = normalize(raw)
        val compact = compact(raw)
        val withoutParen = raw.replace(parenthesized, "").trim()
        val firstToken = normalized.split(' ').firstOrNull().orEmpty()
        val codes = latinCode.findAll(raw).map { it.value.lowercase() }.toList()
        return buildList {
            add(raw)
            add(normalized)
            add(compact)
            add(withoutParen)
            add(normalize(withoutParen))
            add(compact(withoutParen))
            if (firstToken.length >= 2) add(firstToken)
            addAll(codes)
        }.map { it.lowercase().trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun normalize(value: String): String =
        value.lowercase()
            .replace(punctuation, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun compact(value: String): String =
        normalize(value).replace(" ", "")
}
