package com.mlx.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 十四批：思维链分段器（纯函数） */
class ThinkingTreeTest {

    @Test
    fun arabicNumberedHeadersSplit() {
        val segs = parseReasoningSegments("1. 理解需求\n2. 定位文件\n3. 修改方案")
        assertEquals(3, segs.size)
        assertEquals("1. 理解需求", segs[0].header)
        assertEquals("2. 定位文件", segs[1].header)
        assertEquals("3. 修改方案", segs[2].header)
    }

    @Test
    fun variousSeparatorsSplit() {
        val segs = parseReasoningSegments("1、方案A\n2．方案B\n3)方案C\n4]方案D")
        assertEquals(4, segs.size)
        assertEquals("1、方案A", segs[0].header)
        assertEquals("4]方案D", segs[3].header)
    }

    @Test
    fun chineseOrdinalSplit() {
        val segs = parseReasoningSegments("第一步：分析问题\n第二步：设计方案")
        assertEquals(2, segs.size)
        assertEquals("第一步：分析问题", segs[0].header)
        assertEquals("第二步：设计方案", segs[1].header)
    }

    @Test
    fun markdownHeadersSplit() {
        val segs = parseReasoningSegments("## 分析\n### 方案")
        assertEquals(2, segs.size)
        assertEquals("## 分析", segs[0].header)
        assertEquals("### 方案", segs[1].header)
    }

    @Test
    fun chineseNumeralSplit() {
        val segs = parseReasoningSegments("一、现状分析\n二、方案设计")
        assertEquals(2, segs.size)
        assertEquals("一、现状分析", segs[0].header)
        assertEquals("二、方案设计", segs[1].header)
    }

    @Test
    fun noStructureFallsBackToSingleSegment() {
        val text = "模型先通读了一遍代码，然后决定修改入口文件。\n继续分析依赖关系。"
        val segs = parseReasoningSegments(text)
        assertEquals(1, segs.size)
        assertEquals(FALLBACK_HEADER, segs[0].header)
        assertEquals(text.trim(), segs[0].text)
    }

    @Test
    fun preludeBecomesFirstSegment() {
        val segs = parseReasoningSegments("好的，我来分析。\n1. 理解需求\n2. 定位文件")
        assertEquals(3, segs.size)
        assertEquals(PRELUDE_HEADER, segs[0].header)
        assertEquals("好的，我来分析。", segs[0].text)
        assertEquals("1. 理解需求", segs[1].header)
        assertEquals("2. 定位文件", segs[2].header)
    }

    @Test
    fun streamingPartialHeaderNotMistaken() {
        // 流式只来了 "1."（分隔符后无内容）→ 不构成段头，兜底单段
        assertEquals(1, parseReasoningSegments("模型在分析，刚刚写到 1.").size)
        // 行首数字后无分隔符（"12 个文件"）→ 不误判
        assertEquals(1, parseReasoningSegments("12 个文件需要处理").size)
        // 年份 "2024." 超两位数限制 → 不匹配
        assertEquals(1, parseReasoningSegments("2024. 统计显示").size)
    }

    @Test
    fun inlineListNotMistaken() {
        // 非行首数字列表（^ 锚定）→ 不误判
        assertEquals(1, parseReasoningSegments("过程中提到 1. 和 2. 两种方式").size)
    }

    @Test
    fun liveStatusMarksLastSegmentThinking() {
        val live = parseReasoningSegments("1. 理解需求\n2. 定位文件", live = true)
        assertEquals(2, live.size)
        assertEquals(SegStatus.COMPLETE, live[0].status)
        assertEquals(SegStatus.THINKING, live[1].status)
        val done = parseReasoningSegments("1. 理解需求\n2. 定位文件", live = false)
        assertTrue(done.all { it.status == SegStatus.COMPLETE })
    }

    @Test
    fun segmentCountCappedAndMerged() {
        val text = (1..60).joinToString("\n") { "$it. 步骤$it" }
        val segs = parseReasoningSegments(text)
        assertEquals(MAX_THINKING_SEGMENTS, segs.size)
        // 超出部分合并进第 50 段
        assertTrue(segs[49].text.contains("51. 步骤51"))
        assertTrue(segs[49].text.contains("60. 步骤60"))
    }

    @Test
    fun blankInputReturnsEmpty() {
        assertTrue(parseReasoningSegments("").isEmpty())
        assertTrue(parseReasoningSegments("  \n  ").isEmpty())
    }

    @Test
    fun segmentTextExcludesHeaderLine() {
        val segs = parseReasoningSegments("1. 理解需求\n分析用户意图\n2. 定位文件\n搜索相关类")
        assertEquals(2, segs.size)
        assertEquals("分析用户意图", segs[0].text)
        assertEquals("搜索相关类", segs[1].text)
    }
}
