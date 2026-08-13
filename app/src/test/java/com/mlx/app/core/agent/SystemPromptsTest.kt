package com.mlx.app.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二十一批：委派提示词标准与子代理契约的完整性守卫（纯字符串断言）——
 * 防后续精简误伤八要素模板与子代理关键契约条款（报告不直达用户/不臆测/完成即止）。
 */
class SystemPromptsTest {

    private val delegationElements = listOf(
        "【任务】", "【上下文】", "【约束】", "【期望输出】",
        "【质量标准】", "【禁止事项】", "【成功定义】", "【返回形态】",
    )

    @Test
    fun `delegation suffix covers all eight prompt elements`() {
        delegationElements.forEach { tag ->
            assertTrue("DELEGATION_PROMPT_SUFFIX 缺少要素 $tag", SystemPrompts.DELEGATION_PROMPT_SUFFIX.contains(tag))
        }
        assertTrue(SystemPrompts.DELEGATION_PROMPT_SUFFIX.contains("子代理看不到本会话"))
        assertTrue(SystemPrompts.DELEGATION_PROMPT_SUFFIX.contains("一次性给足"))
    }

    @Test
    fun `delegation suffix is a standalone static constant not merged into base`() {
        // 缓存纪律：委派标准必须独立 suffix（并入 BASE 会触发全局 prefix 缓存失效）
        assertFalse(SystemPrompts.BASE.contains("委派提示词标准"))
    }

    @Test
    fun `subagent system contains core contract clauses`() {
        val s = SubAgentManager.SUBAGENT_SYSTEM
        assertTrue(s.contains("只做被派发的任务，不扩展目标"))
        assertTrue(s.contains("报告不直接展示给用户"))
        assertTrue(s.contains("禁止臆测"))
        assertTrue(s.contains("回答完即止"))
        // 二十二批：语言跟随（对齐 BASE）替代强制中文；八要素含【返回形态】
        assertTrue(s.contains("使用与用户消息相同的语言回答"))
        assertTrue(s.contains("【任务】【上下文】【约束】【期望输出】【质量标准】【禁止事项】【成功定义】【返回形态】"))
    }

    @Test
    fun `planner system keeps role output format and gains contract clauses`() {
        val p = SubAgentManager.PLANNER_SYSTEM
        assertTrue(p.contains("问题拆解"))
        assertTrue(p.contains("推荐方案"))
        assertTrue(p.contains("报告不直接展示给用户"))
        assertTrue(p.contains("禁止臆测"))
        assertTrue(p.contains("回答完即止"))
        assertTrue(p.contains("使用与用户消息相同的语言回答"))
        assertTrue(p.contains("【返回形态】"))
    }

    @Test
    fun `subagent contract suffix still covers when and when-not`() {
        val c = SystemPrompts.SUBAGENT_CONTRACT_SUFFIX
        assertTrue(c.contains("何时派"))
        assertTrue(c.contains("何时不派"))
        assertTrue(c.contains("prompt 必须自包含"))
    }
}
