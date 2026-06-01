package com.euedrc.bugsc.data

data class Bug(
    val id: String,
    val title: String,
    val summary: String,
    val description: String = "",
    val typeTags: List<String> = emptyList(),
    val severity: String = "medium",
    val hardware: Hardware = Hardware(),
    val steps: List<String> = emptyList(),
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "user",
    val author: Author = Author(),
    val status: String = "published",
    val votes: Votes = Votes(),
    val commentCount: Int = 0,
    val images: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val hardwareKeywords: List<String> = emptyList()
)

data class Hardware(
    val gpuVendors: List<String> = listOf("Any"),
    val vramMinGB: Int = 0,
    val ramMinGB: Int = 0,
    val cpuVendors: List<String> = listOf("Any")
)

data class Author(
    val uid: String = "local",
    val name: String = "本地用户"
)

data class Votes(
    val helpful: Int = 0,
    val notHelpful: Int = 0
)
