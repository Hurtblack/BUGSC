package com.euedrc.bugsc.news

import android.content.Context

class NewsRepository(
    context: Context,
    private val client: NewsClient = NewsClient(),
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCachedFirstPage(): NewsCacheCodec.CachedPage? {
        val raw = prefs.getString(KEY_FIRST_PAGE_CACHE, null) ?: return null
        return NewsCacheCodec.decode(raw)
    }

    fun fetchPage(pageNumber: Int, searchText: String = ""): List<NewsClient.NewsItem> =
        client.fetchNewsPage(pageNumber = pageNumber, searchText = searchText)

    fun fetchPageInfo(searchText: String = ""): NewsClient.PageInfo =
        client.fetchPageInfo(searchText = searchText)

    fun fetchRemoteFirstPage(): List<NewsClient.NewsItem> = fetchPage(pageNumber = 1)

    fun fetchRemoteFirstPage(searchText: String): List<NewsClient.NewsItem> =
        fetchPage(pageNumber = 1, searchText = searchText)

    fun saveFirstPage(items: List<NewsClient.NewsItem>, cachedAt: Long = System.currentTimeMillis()): Unit {
        prefs.edit()
            .putString(KEY_FIRST_PAGE_CACHE, NewsCacheCodec.encode(items, cachedAt))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "news_cache"
        private const val KEY_FIRST_PAGE_CACHE = "first_page_cache"
    }
}
