package com.euedrc.bugsc

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal class ImageDiskCache(
    private val directory: File,
    private val maxSizeBytes: Long,
    private val maxAgeMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {

    @Synchronized
    fun read(key: String): ByteArray? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) return null
        if (now() - file.lastModified() > maxAgeMillis) {
            file.delete()
            return null
        }
        val bytes = file.readBytes()
        file.setLastModified(now())
        bytes
    }.getOrNull()

    @Synchronized
    fun write(key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            directory.mkdirs()
            val target = fileFor(key)
            val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                temporary.writeBytes(bytes)
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                target.setLastModified(now())
                trimToSize()
            } finally {
                temporary.delete()
            }
        }
    }

    private fun trimToSize() {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy(File::lastModified)
        var totalSize = files.sumOf(File::length)
        for (file in files) {
            if (totalSize <= maxSizeBytes) break
            val fileSize = file.length()
            if (file.delete()) totalSize -= fileSize
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val fileName = digest.joinToString(separator = "") { "%02x".format(it) }
        return File(directory, fileName)
    }
}
