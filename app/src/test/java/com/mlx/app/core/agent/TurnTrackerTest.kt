package com.mlx.app.core.agent

import com.mlx.app.data.store.ToolStatus
import com.mlx.app.data.store.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnTrackerTest {

    @Test
    fun `full turn lifecycle records steps with duration and status`() {
        val tracker = TurnTracker()
        val turn = tracker.startTurn(turnNumber = 3, userText = "生成汇报 HTML", startedAt = 1000L)
        assertEquals(3, turn.turnNumber)
        assertEquals(TurnStatus.RUNNING, turn.status)

        // 正文步骤
        tracker.addTextStep("好的，我先分析文档", at = 1500L)
        // 工具：读取
        tracker.beginTool("c1", "read_file", "{\"path\":\"a.md\"}", at = 2000L)
        tracker.finishTool("c1", ToolStatus.SUCCESS, "文件内容…", at = 3000L)
        // 工具：写入（产物引用）
        tracker.beginTool("c2", "write_file", "{\"path\":\"report.html\"}", at = 3500L)
        tracker.finishTool("c2", ToolStatus.SUCCESS, "已写入", at = 5000L, outputRefs = listOf("report.html"), diffText = "+新增")

        assertEquals(3, turn.steps.size)
        val read = turn.steps[1]
        assertEquals(1000L, read.durationMs)
        assertEquals(ToolStatus.SUCCESS, read.status)
        val write = turn.steps[2]
        assertEquals(listOf("report.html"), write.outputRefs)
        assertEquals("+新增", write.diffText)

        tracker.endTurn(TurnStatus.SUCCESS, at = 6000L, costUsd = 0.1234)
        assertEquals(TurnStatus.SUCCESS, turn.status)
        assertEquals(6000L, turn.finishedAt)
        assertEquals(0.1234, turn.costUsd, 1e-9)
    }

    @Test
    fun `denied tool recorded and endTurn with aborted`() {
        val tracker = TurnTracker()
        tracker.startTurn(1, "只读任务", 0L)
        tracker.beginTool("c1", "write_file", "{}", at = 100L)
        tracker.finishTool("c1", ToolStatus.DENIED, "计划模式拒绝", at = 200L)
        tracker.endTurn(TurnStatus.ABORTED, at = 300L, costUsd = 0.0)
        assertEquals(ToolStatus.DENIED, tracker.current!!.steps[0].status)
        assertEquals(TurnStatus.ABORTED, tracker.current!!.status)
    }

    @Test
    fun `beginTool carries intent description`() {
        val tracker = TurnTracker()
        tracker.startTurn(1, "任务", 0L)
        tracker.beginTool("c1", "read_file", "{}", at = 100L, intent = "正在读取配置文件")
        assertEquals("正在读取配置文件", tracker.current!!.steps[0].intent)
        // 默认空串（旧调用点兼容）
        tracker.beginTool("c2", "shell", "{}", at = 200L)
        assertEquals("", tracker.current!!.steps[1].intent)
    }

    @Test
    fun `no current turn operations are no-ops`() {
        val tracker = TurnTracker()
        assertNull(tracker.addTextStep("text", 0L))
        assertNull(tracker.beginTool("c1", "x", "{}", 0L))
        tracker.finishTool("c1", ToolStatus.SUCCESS, "r", 0L) // 不崩
        tracker.endTurn(TurnStatus.SUCCESS, 0L, 0.0)          // 不崩
        tracker.reset()
        assertNull(tracker.current)
    }

    @Test
    fun `beginTool dedupes same call id`() {
        val tracker = TurnTracker()
        tracker.startTurn(1, "t", 0L)
        tracker.beginTool("c1", "read_file", "{}", 100L)
        tracker.beginTool("c1", "read_file", "{}", 200L) // 重复 → 不入树
        assertEquals(1, tracker.current!!.steps.size)
    }

    @Test
    fun `abort marks running steps failed and turn aborted`() {
        val tracker = TurnTracker()
        tracker.startTurn(1, "任务", 0L)
        tracker.beginTool("c1", "read_file", "{}", at = 100L)
        tracker.beginTool("c2", "shell", "{}", at = 150L)
        tracker.finishTool("c1", ToolStatus.SUCCESS, "完成", at = 200L) // 已完成的不动
        tracker.abort(at = 300L)

        val steps = tracker.current!!.steps
        assertEquals(ToolStatus.SUCCESS, steps[0].status)
        assertEquals(ToolStatus.FAILED, steps[1].status)          // RUNNING → FAILED
        assertEquals("已停止（用户停止）", steps[1].resultText)
        assertEquals(150L, steps[1].durationMs)                    // 300-150
        assertEquals(300L, steps[1].finishedAt)
        assertEquals(TurnStatus.ABORTED, tracker.current!!.status)
    }

    @Test
    fun `abort with no current turn is no-op`() {
        val tracker = TurnTracker()
        tracker.abort(at = 100L) // 不崩
    }
}
