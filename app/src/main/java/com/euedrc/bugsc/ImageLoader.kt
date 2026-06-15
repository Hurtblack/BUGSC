package com.euedrc.bugsc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {
    private const val DISK_CACHE_MAX_SIZE = 50L * 1024 * 1024
    private const val DISK_CACHE_MAX_AGE = 7L * 24 * 60 * 60 * 1000

    private val cache = object : android.util.LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    @Volatile
    private var diskCache: ImageDiskCache? = null

    fun load(fragment: Fragment, imageView: ImageView, url: String?, headers: Map<String, String> = emptyMap()) {
        if (url.isNullOrBlank()) return
        imageView.tag = url
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }
        val cacheDirectory = fragment.requireContext().cacheDir
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val persistentCache = getDiskCache(cacheDirectory)
                persistentCache.read(url)
                    ?.let(::decodeBitmap)
                    ?: loadWithOneRetry { download(url, headers) }
                        ?.also { persistentCache.write(url, it) }
                        ?.let(::decodeBitmap)
            }
            if (imageView.tag == url && bitmap != null) {
                cache.put(url, bitmap)
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun getDiskCache(cacheDirectory: File): ImageDiskCache {
        diskCache?.let { return it }
        return synchronized(this) {
            diskCache ?: ImageDiskCache(
                directory = File(cacheDirectory, "image_cache"),
                maxSizeBytes = DISK_CACHE_MAX_SIZE,
                maxAgeMillis = DISK_CACHE_MAX_AGE,
            ).also { diskCache = it }
        }
    }

    private fun download(url: String, headers: Map<String, String>): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }.takeIf(ByteArray::isNotEmpty)
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
