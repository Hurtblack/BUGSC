package com.euedrc.bugsc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ImageDiskCacheTest {

    @Test
    fun persistsBytesAcrossCacheInstances() {
        val directory = Files.createTempDirectory("image-cache").toFile()
        val bytes = byteArrayOf(1, 2, 3, 4)

        ImageDiskCache(directory, maxSizeBytes = 100, maxAgeMillis = 1_000, now = { 100 })
            .write("https://example.com/image.jpg", bytes)

        val restored = ImageDiskCache(
            directory,
            maxSizeBytes = 100,
            maxAgeMillis = 1_000,
            now = { 200 },
        ).read("https://example.com/image.jpg")

        assertArrayEquals(bytes, restored)
    }

    @Test
    fun deletesExpiredEntry() {
        val directory = Files.createTempDirectory("image-cache").toFile()
        val cache = ImageDiskCache(
            directory,
            maxSizeBytes = 100,
            maxAgeMillis = 1_000,
            now = { 100 },
        )
        cache.write("https://example.com/old.jpg", byteArrayOf(1))
        val cacheFile = directory.listFiles().orEmpty().single()
        cacheFile.setLastModified(100)

        val expired = ImageDiskCache(
            directory,
            maxSizeBytes = 100,
            maxAgeMillis = 1_000,
            now = { 1_101 },
        ).read("https://example.com/old.jpg")

        assertNull(expired)
        assertFalse(cacheFile.exists())
    }

    @Test
    fun trimsLeastRecentlyUsedEntryWhenCapacityIsExceeded() {
        val directory = Files.createTempDirectory("image-cache").toFile()
        var currentTime = 100L
        val cache = ImageDiskCache(
            directory,
            maxSizeBytes = 6,
            maxAgeMillis = 10_000,
            now = { currentTime },
        )

        cache.write("first", byteArrayOf(1, 1, 1))
        currentTime = 200
        cache.write("second", byteArrayOf(2, 2, 2))
        currentTime = 300
        assertArrayEquals(byteArrayOf(1, 1, 1), cache.read("first"))
        currentTime = 400
        cache.write("third", byteArrayOf(3, 3, 3))

        assertTrue(cache.read("first") != null)
        assertNull(cache.read("second"))
        assertTrue(cache.read("third") != null)
    }
}
