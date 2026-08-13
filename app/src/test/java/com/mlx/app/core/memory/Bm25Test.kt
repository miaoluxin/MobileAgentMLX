package com.mlx.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25Test {

    @Test
    fun `english tokens split on whitespace`() {
        assertEquals(listOf("mlx", "agent"), BM25.tokenize("MLX Agent"))
    }

    @Test
    fun `chinese tokens produce bigrams`() {
        val tokens = BM25.tokenize("缓存命中率")
        assertTrue(tokens.contains("缓存"))
        assertTrue(tokens.contains("存命"))
        assertTrue(tokens.contains("命中"))
    }

    @Test
    fun `matching doc scores higher than non-matching`() {
        val docs = listOf("项目使用 Kotlin 和 Compose 开发", "今天天气不错适合散步")
        val query = "Kotlin"
        val s1 = BM25.score(query, docs[0], docs.size, docs.count { it.contains(query) })
        val s2 = BM25.score(query, docs[1], docs.size, docs.count { it.contains(query) })
        assertTrue(s1 > s2)
    }

    @Test
    fun `empty query scores zero`() {
        assertEquals(0.0, BM25.score("", "任意内容", corpusSize = 1, df = 0), 1e-9)
    }
}
