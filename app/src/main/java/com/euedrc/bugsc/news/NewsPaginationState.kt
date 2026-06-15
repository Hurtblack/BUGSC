package com.euedrc.bugsc.news

class NewsPaginationState {
    var currentPage: Int = 0
        private set
    var totalPages: Int = 1
        private set
    var isLoading: Boolean = false
        private set

    val hasMorePages: Boolean
        get() = currentPage < totalPages

    val canAutoLoad: Boolean
        get() = !isLoading && failedPage == null && hasMorePages

    private var failedPage: Int? = null
    private val loadedPostIds = linkedSetOf<String>()

    fun reset(totalPages: Int) {
        currentPage = 0
        this.totalPages = totalPages.coerceAtLeast(1)
        isLoading = false
        failedPage = null
        loadedPostIds.clear()
    }

    fun updateTotalPages(totalPages: Int) {
        this.totalPages = totalPages.coerceAtLeast(1)
    }

    fun seedFirstPage(items: List<NewsClient.NewsItem>, totalPages: Int) {
        reset(totalPages)
        currentPage = 1
        items.forEach { loadedPostIds += it.postId }
    }

    fun beginNextPage(): Int? {
        if (isLoading) return null
        val page = failedPage ?: currentPage + 1
        if (page > totalPages) return null
        isLoading = true
        return page
    }

    fun complete(page: Int, items: List<NewsClient.NewsItem>): List<NewsClient.NewsItem> {
        val uniqueItems = items.filter { loadedPostIds.add(it.postId) }
        currentPage = maxOf(currentPage, page)
        isLoading = false
        failedPage = null
        return uniqueItems
    }

    fun fail(page: Int) {
        isLoading = false
        failedPage = page
    }
}
