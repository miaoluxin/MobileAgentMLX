package com.mlx.app.core.repair

import com.mlx.app.core.repair.RepairPipeline.AssembledCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairPipelineTest {

    @Test
    fun `scavenge finds tool call inside reasoning block`() {
        val reasoning = "我先想想。{\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"src/A.kt\\\"}\"}} 然后继续。"
        val found = RepairPipeline.scavengeToolCalls(reasoning)
        assertEquals(1, found.size)
        assertEquals("read_file", found[0].name)
        assertTrue(found[0].argsJson.contains("src/A.kt"))
    }

    @Test
    fun `scavenge handles object-form arguments`() {
        val reasoning = """分析后: {"name":"write_file","arguments":{"path":"out.txt","content":"hello"}}"""
        val found = RepairPipeline.scavengeToolCalls(reasoning)
        assertEquals(1, found.size)
        assertEquals("write_file", found[0].name)
        assertTrue(found[0].argsJson.contains("out.txt"))
    }

    @Test
    fun `scavenge handles reversed name-arguments order`() {
        val reasoning = """{"arguments":"{\"path\":\"a.kt\"}","name":"read_file"}"""
        val found = RepairPipeline.scavengeToolCalls(reasoning)
        assertEquals(1, found.size)
        assertEquals("read_file", found[0].name)
    }

    @Test
    fun `recoverDetailed reports dropped and repaired stats`() {
        val calls = listOf(
            AssembledCall("1", "read_file", """{"path":"a"}"""),
            AssembledCall("2", "edit_file", """{"path":"b","search":"x"""), // 截断 → 可修复
            AssembledCall("3", "bad", "not json at all {"),                // 不可修复 → 丢弃
        )
        val stats = RepairPipeline.recoverDetailed(calls, reasoningText = "")
        assertEquals(2, stats.calls.size)
        assertEquals(1, stats.dropped)
        assertTrue(stats.repaired >= 1)
    }

    @Test
    fun `recoverDetailed counts scavenged calls`() {
        val reasoning = """思考: {"name":"list_files","arguments":"{\"path\":\"\"}"}"""
        val stats = RepairPipeline.recoverDetailed(
            assembled = listOf(AssembledCall("1", "read_file", """{"path":"a"}""")),
            reasoningText = reasoning,
        )
        assertEquals(2, stats.calls.size)
        assertEquals(1, stats.scavenged)
    }

    @Test
    fun `truncation recovers unbalanced json`() {
        val raw = """{"path": "src/A.kt", "content": "line1\nline2"""
        val recovered = RepairPipeline.truncationRecover(raw)
        assertNotNull(recovered)
        assertTrue(recovered!!.endsWith("\"}"))
        // 闭合后应为合法 JSON（MiniJson 可解析）
        val parsed = com.mlx.app.core.common.MiniJson.parse(recovered)
        assertNotNull(parsed)
    }

    @Test
    fun `truncation returns null for non-json input`() {
        assertNull(RepairPipeline.truncationRecover("hello world"))
    }

    @Test
    fun `truncation accepts already-valid json`() {
        val raw = """{"a": 1, "b": [1, 2]}"""
        assertEquals(raw, RepairPipeline.truncationRecover(raw))
    }

    @Test
    fun `storm dedupe removes consecutive duplicates`() {
        val calls = listOf(
            AssembledCall("1", "read_file", """{"path":"a"}"""),
            AssembledCall("2", "read_file", """{"path":"a"}"""),
            AssembledCall("3", "read_file", """{"path":"a"}"""),
            AssembledCall("4", "read_file", """{"path":"b"}"""),
        )
        val out = RepairPipeline.dedupeStorm(calls)
        assertEquals(2, out.size)
        assertEquals("a", com.mlx.app.core.common.MiniJson.toMap(
            com.mlx.app.core.common.MiniJson.parse(out[0].argsJson)
        )["path"])
    }

    @Test
    fun `recover drops invalid calls and blank names`() {
        val calls = listOf(
            AssembledCall("1", "read_file", """{"path":"a"}"""),
            AssembledCall("2", "", """{"path":"x"}"""),          // 无名字 → 丢弃
            AssembledCall("3", "bad", "not json at all {"),      // 无法修复 → 丢弃
        )
        val out = RepairPipeline.recover(calls, reasoningText = "")
        assertEquals(1, out.size)
        assertEquals("read_file", out[0].name)
    }

    @Test
    fun `recover merges scavenged calls`() {
        val reasoning = """思考: {"name":"list_files","arguments":"{\"path\":\"\"}"}"""
        val out = RepairPipeline.recover(
            assembled = listOf(AssembledCall("1", "read_file", """{"path":"a"}""")),
            reasoningText = reasoning,
        )
        assertEquals(2, out.size)
    }
}
