package com.mlx.app.core.agent

import com.mlx.app.data.store.MessageRecord
import com.mlx.app.data.store.Session
import com.mlx.app.data.store.ToolCallRecord
import com.mlx.app.data.store.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AgentEngine.markAbortedTools 兜底清理：取消/异常收尾时把残留 RUNNING 工具标为失败，
 * 防历史消息工具卡永久"执行中"（取消传播 rethrow 后工具不走 finishTool 回填）。
 */
class MarkAbortedToolsTest {

    private fun session(vararg toolRecords: ToolCallRecord): Session = Session(
        id = "s1", title = "t", createdAt = 0L, updatedAt = 0L, model = "m",
        messages = mutableListOf(MessageRecord("a1", "assistant", toolCalls = toolRecords.toList())),
    )

    @Test
    fun `running tools marked failed with abort text`() {
        val s = session(
            ToolCallRecord("c1", "read_file", "{}", ToolStatus.RUNNING),
            ToolCallRecord("c2", "shell", "{}", ToolStatus.SUCCESS),
        )
        AgentEngine.markAbortedTools(s)
        assertEquals(ToolStatus.FAILED, s.messages[0].toolCalls[0].status)
        assertEquals("已停止（用户停止）", s.messages[0].toolCalls[0].resultText)
        assertEquals(ToolStatus.SUCCESS, s.messages[0].toolCalls[1].status) // 已完成不动
    }

    @Test
    fun `completed statuses are left untouched`() {
        val s = session(
            ToolCallRecord("c1", "read_file", "{}", ToolStatus.SUCCESS),
            ToolCallRecord("c2", "write_file", "{}", ToolStatus.DENIED),
        )
        AgentEngine.markAbortedTools(s)
        assertEquals(ToolStatus.SUCCESS, s.messages[0].toolCalls[0].status)
        assertEquals(ToolStatus.DENIED, s.messages[0].toolCalls[1].status)
    }

    @Test
    fun `pending approval tools marked failed on abort`() {
        // 回合中止时挂起的审批不再有机会决策 → 标失败（防历史残留"待审批"误导）
        val s = session(
            ToolCallRecord("c1", "write_file", "{}", ToolStatus.APPROVAL_REQUIRED),
        )
        AgentEngine.markAbortedTools(s)
        assertEquals(ToolStatus.FAILED, s.messages[0].toolCalls[0].status)
        assertEquals("回合已中止，审批未完成", s.messages[0].toolCalls[0].resultText)
    }

    @Test
    fun `no tool calls is no-op`() {
        val s = session()
        AgentEngine.markAbortedTools(s) // 不崩
    }
}
