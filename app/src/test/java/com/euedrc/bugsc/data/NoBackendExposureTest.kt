package com.euedrc.bugsc.data

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class NoBackendExposureTest {
    @Test
    fun publicMainSourceDoesNotContainBackendClientImplementations() {
        val roots = publicSourceRoots()
        val forbiddenFileNames = setOf(
            "ScmApi.kt",
            "ScmClient.kt",
            "ScmMarketClient.kt",
            "MarketPublishClient.kt",
            "TransactionClient.kt",
            "ScApiClient.kt",
            "ScApiOnlineDataSource.kt",
        )
        val forbiddenTypeNames = listOf(
            "class ScmApi",
            "object ScmClient",
            "class ScmMarketClient",
            "class MarketPublishClient",
            "class TransactionClient",
            "class ScApiClient",
            "class ScApiOnlineDataSource",
        )
        val files = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        }
        val fileHits = files.filter { it.name in forbiddenFileNames }.map { it.path }
        val typeHits = files.flatMap { file ->
            val text = file.readText()
            forbiddenTypeNames.filter { needle -> text.contains(needle) }.map { needle -> "${file.path}: $needle" }
        }
        assertFalse(
            "Public source contains backend client implementations: ${fileHits + typeHits}",
            (fileHits + typeHits).isNotEmpty(),
        )
    }

    @Test
    fun publicMainSourceDoesNotContainGenericBackendSecurityFlowNames() {
        val roots = publicSourceRoots()
        val forbiddenTerms = listOf(
            "x-scapi-nonce",
            "x-scapi-sequence",
            "ScApiTicket",
            "ScApiSession",
            "flowcld",
            "app-api",
            "/system/captcha",
            "/member/",
            "ws-ticket",
        )
        val hits = roots.asSequence()
            .flatMap { root -> root.walkTopDown() }
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                val text = file.readText()
                forbiddenTerms.filter { term -> text.contains(term) }.map { term -> "${file.path}: $term" }
            }
            .toList()
        assertFalse("Public source exposes protected backend security flow names: $hits", hits.isNotEmpty())
    }

    private fun publicSourceRoots(): List<File> =
        listOf("src/main/java", "src/oss/java", "src/full/java")
            .map(::File)
            .filter(File::exists)
}
