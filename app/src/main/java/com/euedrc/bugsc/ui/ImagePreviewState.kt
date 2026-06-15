package com.euedrc.bugsc.ui

class ImagePreviewState private constructor(
    val urls: List<String>,
    val index: Int,
) {
    val currentUrl: String get() = urls[index]
    val indicator: String get() = if (urls.size > 1) "${index + 1}/${urls.size}" else ""

    companion object {
        fun create(urls: List<String>, requestedIndex: Int): ImagePreviewState? {
            val cleaned = urls.map(String::trim).filter(String::isNotBlank)
            if (cleaned.isEmpty()) return null
            return ImagePreviewState(
                urls = cleaned,
                index = requestedIndex.coerceIn(0, cleaned.lastIndex),
            )
        }
    }
}
