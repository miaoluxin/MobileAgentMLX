package com.mlx.app.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun `isWriteTool covers all write tools including delete_range`() {
        // delete_range 补入（文档 2.4 缺口：计划模式拦截漏网）
        assertTrue(ToolRegistry.isWriteTool("write_file"))
        assertTrue(ToolRegistry.isWriteTool("edit_file"))
        assertTrue(ToolRegistry.isWriteTool("multi_edit"))
        assertTrue(ToolRegistry.isWriteTool("move_file"))
        assertTrue(ToolRegistry.isWriteTool("delete_range"))
        assertFalse(ToolRegistry.isWriteTool("read_file"))
        assertFalse(ToolRegistry.isWriteTool("list_files"))
        assertFalse(ToolRegistry.isWriteTool("web_search"))
        assertFalse(ToolRegistry.isWriteTool("submit_plan"))
    }

    @Test
    fun `planModeBlocksWrite truth table`() {
        // 计划模式 + 未批准 + 写工具 → 拦截
        assertTrue(ToolRegistry.planModeBlocksWrite(true, false, "write_file"))
        assertTrue(ToolRegistry.planModeBlocksWrite(true, false, "delete_range"))
        // 批准后放行
        assertFalse(ToolRegistry.planModeBlocksWrite(true, true, "write_file"))
        // 非计划模式不拦截
        assertFalse(ToolRegistry.planModeBlocksWrite(false, false, "write_file"))
        // 读工具不拦截
        assertFalse(ToolRegistry.planModeBlocksWrite(true, false, "read_file"))
    }
}
