package com.mlx.app.core.context

import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolResult
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.data.saf.SafRepo
import com.mlx.app.data.store.MessageRecord
import com.mlx.app.data.store.Session
import com.mlx.app.data.store.ToolCallRecord
import com.mlx.app.data.store.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {

    private val specA = object : ToolSpec {
        override val name = "read_file"
        override val description = "读取文件"
        override val parameters: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string")),
        )
        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult =
            ToolResult(true, "ok")
    }

    private val specs: List<ToolSpec> = listOf(specA)

    private fun manager() = ContextManager(baseSystemPrompt = "你是 MLX 测试 Agent。")

    @Test
    fun `compact drops orphan tool messages with removed assistant`() {
        // 二十二批（审计）：Phase 2 删除 assistant（携带 tool_calls）时连带清理其 tool 消息，
        // 防孤儿 tool 消息导致 OpenAI 兼容 API 400（删除发生在上下文最紧张时刻）
        val cm = ContextManager(baseSystemPrompt = "你是 MLX 测试 Agent。", maxEstimatedTokens = 10) // 极小窗口强制触发压缩
        val s = Session(
            id = "s", title = "t", createdAt = 0L, updatedAt = 0L, model = "m",
            messages = mutableListOf(
                MessageRecord("u1", "user", "任务"),
                MessageRecord(
                    "a1", "assistant",
                    toolCalls = listOf(ToolCallRecord("c1", "shell", "{}", ToolStatus.SUCCESS)),
                ),
                MessageRecord("t1", "tool", "输出内容", toolCallId = "c1"),
                MessageRecord("u2", "user", "继续"),
                MessageRecord("a2", "assistant", "正文", toolCalls = emptyList()),
                MessageRecord("t2", "tool", "无主结果", toolCallId = "ghost"),
                MessageRecord("u3", "user", "再来"),
            ),
        )
        cm.compact(s, specs)
        // Phase 2 从最旧非 user 开始删：a1 被删 → 其 tool 结果 t1（toolCallId=c1）连带删除
        assertFalse(s.messages.any { it.id == "a1" })
        assertFalse(s.messages.any { it.id == "t1" }) // 孤儿 tool 消息连带删除
        assertTrue(s.messages.any { it.id == "u1" }) // user 保留（标题语义）
        assertTrue(s.messages.any { it.id == "u2" })
        assertTrue(s.messages.any { it.id == "u3" })
    }

    @Test
    fun `prefix is byte-stable across builds`() {
        val cm = manager()
        val p1 = cm.buildPrefix(specs)
        val p2 = cm.buildPrefix(specs)
        assertEquals(p1, p2)
        assertEquals(cm.currentPrefixSha256, ContextManager.sha256(p1))
        assertEquals(1, cm.prefixVersion)
    }

    @Test
    fun `prefix version bumps when plan mode suffix changes`() {
        val cm = manager()
        cm.buildPrefix(specs)
        val v1 = cm.prefixVersion
        cm.buildPrefix(specs, planModeSuffix = "【计划模式】只读")
        assertEquals(v1 + 1, cm.prefixVersion)
    }

    @Test
    fun `buildMessages puts system first then log order`() {
        val cm = manager()
        val session = Session("s1", "t", 0L, 0L, "flash")
        session.messages += MessageRecord("m1", "user", "你好")
        session.messages += MessageRecord("m2", "assistant", "你好！")
        val msgs = cm.buildMessages(session, specs)
        assertEquals("system", msgs[0].role)
        assertTrue(msgs[0].content!!.contains("read_file"))
        assertEquals("user", msgs[1].role)
        assertEquals("你好", msgs[1].content)
        assertEquals("assistant", msgs[2].role)
        assertEquals("你好！", msgs[2].content)
    }

    @Test
    fun `dynamic info never enters prefix`() {
        val cm = manager()
        val p1 = cm.buildPrefix(specs)
        // 模拟动态信息进入日志而非 prefix
        val session = Session("s1", "t", 0L, 0L, "flash")
        session.messages += MessageRecord("m1", "user", "当前时间: 2026-08-06 分屏中")
        val msgs = cm.buildMessages(session, specs)
        val prefix = msgs[0].content!!
        assertTrue(!prefix.contains("2026-08-06"))
        assertTrue(!prefix.contains("分屏"))
        assertEquals(p1, prefix) // prefix 不受动态信息影响
    }

    @Test
    fun `compact shrinks long tool results`() {
        val cm = manager()
        val session = Session("s1", "t", 0L, 0L, "flash")
        session.messages += MessageRecord("m1", "user", "任务")
        session.messages += MessageRecord(
            "m2", "tool", "x".repeat(2000), toolCallId = "c1",
        )
        val before = session.estimatedTokens()
        cm.compact(session, specs)
        val after = session.estimatedTokens()
        assertTrue(after < before)
        assertTrue(session.messages.last().content.startsWith("[已压缩]"))
    }

    @Test
    fun `compactManual folds old region keeps recent turns and user text`() {
        val cm = manager()
        val session = Session("s1", "t", 0L, 0L, "flash")
        session.messages += MessageRecord("u1", "user", "第一轮任务")
        session.messages += MessageRecord("a1", "assistant", "x".repeat(2000), toolCalls = listOf())
        session.messages += MessageRecord("t1", "tool", "y".repeat(2000), toolCallId = "c1")
        session.messages += MessageRecord("u2", "user", "第二轮任务")
        session.messages += MessageRecord("a2", "assistant", "z".repeat(2000))
        val before = session.estimatedTokens()
        val folded = cm.compactManual(session, specs)
        val after = session.estimatedTokens()
        assertTrue(folded >= 3)
        assertTrue(after < before)
        // 用户消息原文保留
        assertEquals("第一轮任务", session.messages[0].content)
        assertEquals("第二轮任务", session.messages[3].content)
        // 旧 assistant 内容被压缩标记
        assertTrue(session.messages[1].content.startsWith("[已压缩]"))
    }

    @Test
    fun `compactManual returns zero when nothing to fold`() {
        val cm = manager()
        val session = Session("s1", "t", 0L, 0L, "flash")
        session.messages += MessageRecord("u1", "user", "你好")
        session.messages += MessageRecord("a1", "assistant", "你好！")
        val folded = cm.compactManual(session, specs)
        assertEquals(0, folded)
    }

    @Test
    fun `ratio grows with messages`() {
        val cm = manager()
        val session = Session("s1", "t", 0L, 0L, "flash")
        val empty = cm.ratioUsed(session, specs)
        session.messages += MessageRecord("m1", "user", "y".repeat(4000))
        val grown = cm.ratioUsed(session, specs)
        assertTrue(grown > empty)
    }

    // ---- 十一批：缓存键指纹（名字+描述+参数） ----

    @Test
    fun `prefix rebuilds when description changes without rename`() {
        val cm = manager()
        cm.buildPrefix(specs)
        val v1 = cm.prefixVersion
        val changed = object : ToolSpec {
            override val name = "read_file" // 同名 —— 旧缓存键只看名字会误命中
            override val description = "读取文件（描述已更新）"
            override val parameters: Map<String, Any?> = mapOf(
                "type" to "object",
                "properties" to mapOf("path" to mapOf("type" to "string")),
            )
            override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult =
                ToolResult(true, "ok")
        }
        val p = cm.buildPrefix(listOf(changed))
        assertEquals(v1 + 1, cm.prefixVersion) // 描述变化 → prefix 重建
        assertTrue(p.contains("描述已更新"))
    }

    @Test
    fun `prefix rebuilds when parameters change without rename`() {
        val cm = manager()
        cm.buildPrefix(specs)
        val v1 = cm.prefixVersion
        val changed = object : ToolSpec {
            override val name = "read_file"
            override val description = "读取文件"
            override val parameters: Map<String, Any?> = mapOf(
                "type" to "object",
                "properties" to mapOf("path" to mapOf("type" to "string"), "offset" to mapOf("type" to "integer")),
            )
            override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult =
                ToolResult(true, "ok")
        }
        cm.buildPrefix(listOf(changed))
        assertEquals(v1 + 1, cm.prefixVersion) // 参数变化 → prefix 重建（MCP schema 运行时更新的前提）
    }
}
