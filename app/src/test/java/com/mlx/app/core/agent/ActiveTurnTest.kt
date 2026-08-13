package com.mlx.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** 六批：执行快照 → 通知文案 / 批次意图提取 / 耗时格式化（纯函数） */
class ActiveTurnTest {

    @Test
    fun notificationTextPerPhase() {
        assertEquals("正在思考…", notificationText(ActiveTurnStatus(phase = ActivePhase.THINKING)))
        assertEquals("正在生成…", notificationText(ActiveTurnStatus(phase = ActivePhase.STREAMING)))
        assertEquals("等待你的操作…", notificationText(ActiveTurnStatus(phase = ActivePhase.WAITING_USER)))
        assertEquals("执行完成", notificationText(ActiveTurnStatus(phase = ActivePhase.IDLE)))
        assertEquals("已停止", notificationText(ActiveTurnStatus(phase = ActivePhase.IDLE, aborted = true)))
    }

    @Test
    fun notificationTextIntentFirstFallbackToolName() {
        val withIntent = ActiveTurnStatus(phase = ActivePhase.TOOL_RUNNING, intent = "正在读取配置文件…", toolName = "read_file")
        assertEquals("正在执行：正在读取配置文件…", notificationText(withIntent))
        // 无意图 → 工具名；两者皆空 → "工具"兜底
        assertEquals("正在执行：read_file", notificationText(ActiveTurnStatus(phase = ActivePhase.TOOL_RUNNING, toolName = "read_file")))
        assertEquals("正在执行：工具", notificationText(ActiveTurnStatus(phase = ActivePhase.TOOL_RUNNING)))
    }

    @Test
    fun intentTextBlankReturnsEmpty() {
        assertEquals("", intentText(""))
        assertEquals("", intentText("   \n\t "))
    }

    @Test
    fun intentTextMergesLinesAndTruncates() {
        // 换行/多空格合并为单个空格（英文安全；中文场景语义可接受）
        assertEquals("正在读取 配置文件", intentText("正在读取\n配置文件"))
        assertEquals("a b c", intentText("a   b\nc"))
        val long = "x".repeat(200)
        assertEquals(120, intentText(long).length)
    }

    @Test
    fun intentTextKeepsPunctuation() {
        assertEquals("正在修复金额精度 bug…", intentText("正在修复金额精度 bug…"))
    }

    @Test
    fun fmtSecsFormats() {
        assertEquals("45s", fmtSecs(45))
        assertEquals("5分32s", fmtSecs(332))
        assertEquals("2时3分", fmtSecs(7380))
        assertEquals("0s", fmtSecs(0))
    }
}
