package com.euedrc.bugsc

internal inline fun <T> loadWithOneRetry(operation: () -> T?): T? {
    repeat(2) {
        val result = runCatching(operation).getOrNull()
        if (result != null) return result
    }
    return null
}
