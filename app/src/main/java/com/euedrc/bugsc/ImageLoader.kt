package com.euedrc.bugsc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {
    private val cache = object : android.util.LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(fragment: Fragment, imageView: ImageView, url: String?, headers: Map<String, String> = emptyMap()) {
        if (url.isNullOrBlank()) return
        imageView.tag = url
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        BitmapFactory.decodeStream(conn.inputStream)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (imageView.tag == url && bitmap != null) {
                cache.put(url, bitmap)
                imageView.setImageBitmap(bitmap)
            }
        }
    }
}
