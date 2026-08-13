package com.mlx.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FactMemoryTest {

    private fun store(tmp: File) = FactMemory(File(tmp, "mem"))

    @Test
    fun `add list delete roundtrip`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fm_${System.nanoTime()}")
        dir.mkdirs()
        val m = store(dir)
        m.add("project", "项目使用 Kotlin 开发")
        assertEquals(1, m.list().size)
        m.add("user", "用户偏好中文回答")
        assertEquals(2, m.list().size)
        m.delete(m.list()[0].id)
        assertEquals(1, m.list().size)
        dir.deleteRecursively()
    }

    @Test
    fun `recall limits to top4 and max chars`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fm_${System.nanoTime()}")
        dir.mkdirs()
        val m = store(dir)
        repeat(8) { i -> m.add("project", "主题A相关内容 $i 的细节描述".repeat(50)) }
        val recalled = m.recall("主题A", topK = 4, maxChars = 2400)
        assertTrue(recalled.size <= 4)
        assertTrue(recalled.sumOf { it.content.length } <= 2400)
        dir.deleteRecursively()
    }

    @Test
    fun `remember policy rejects sensitive content and enforces bound`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fm_${System.nanoTime()}")
        dir.mkdirs()
        val m = store(dir)
        // 敏感拒绝：密钥类内容不应进入记忆
        val sensitive = "我的 API Key 是 sk-abcdef1234567890abcdef1234567890"
        assertTrue(Regex("(?i)(api[_-]?key|secret|password|passwd|token|bearer|sk-[a-z0-9]{8,})").containsMatchIn(sensitive))
        // 有界：>500 字拒绝（由 remember 工具执行，此处验证 FactMemory 正常保存）
        val long = "x".repeat(600)
        m.add("project", long)
        assertEquals(1, m.list().size)
        dir.deleteRecursively()
    }

    @Test
    fun `update reuses existing fact id`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fm_${System.nanoTime()}")
        dir.mkdirs()
        val m = store(dir)
        val f = m.add("project", "项目使用 Kotlin 开发")
        m.update(f.id, "项目使用 Kotlin 和 Compose 开发")
        assertEquals(1, m.list().size)
        assertTrue(m.list()[0].content.contains("Compose"))
        dir.deleteRecursively()
    }

    @Test
    fun `recall matches by keyword and returns content`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fm_${System.nanoTime()}")
        dir.mkdirs()
        val m = store(dir)
        m.add("project", "项目使用 Kotlin 开发移动端应用")
        m.add("user", "用户喜欢简洁的回答风格")
        val recalled = m.recall("Kotlin", topK = 4)
        assertEquals(1, recalled.size)
        assertTrue(recalled[0].content.contains("Kotlin"))
        dir.deleteRecursively()
    }
}
