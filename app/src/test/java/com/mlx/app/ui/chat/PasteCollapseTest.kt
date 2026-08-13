package com.mlx.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 九批：粘贴长文折叠检测（纯函数） */
class PasteCollapseTest {

    @Test
    fun shortPasteDoesNotCollapse() {
        assertNull(pasteCollapseInfo("", "你好"))
        assertNull(pasteCollapseInfo("", "a".repeat(499)))
        // 15 行内不折叠
        assertNull(pasteCollapseInfo("", (1..14).joinToString("\n")))
    }

    @Test
    fun longPasteCollapses() {
        val long = "a".repeat(600)
        val info = pasteCollapseInfo("", long)
        assertNotNull(info)
        assertEquals("📋 已粘贴文字 +1行（600 字符）", info)
    }

    @Test
    fun manyLinesPasteCollapses() {
        val text = (1..20).joinToString("\n") { "第${it}行" }
        val info = pasteCollapseInfo("", text)
        assertNotNull(info)
        assertEquals("📋 已粘贴文字 +20行（${text.length} 字符）", info)
    }

    @Test
    fun chineseTextCountsPerChar() {
        // 500 个中文字符（每字符一个 Char）→ 超过 500 字符阈值判定为粘贴
        val cn = "字".repeat(501)
        assertNotNull(pasteCollapseInfo("", cn))
        val info = pasteCollapseInfo("", cn)!!
        assertEquals("📋 已粘贴文字 +1行（501 字符）", info)
    }

    @Test
    fun deletionDoesNotCollapse() {
        assertNull(pasteCollapseInfo("hello world", "hello"))
        assertNull(pasteCollapseInfo("a".repeat(1000), ""))
    }

    @Test
    fun incrementalTypingDoesNotCollapse() {
        // 逐字输入：每次增量 1 字符，累计再多也不触发（单次变化小）
        var prev = ""
        var cur = ""
        for (i in 1..600) {
            cur = "x".repeat(i)
            assertNull("第 $i 次输入不应触发折叠", pasteCollapseInfo(prev, cur))
            prev = cur
        }
    }

    @Test
    fun pasteAppendedToExistingTextCollapses() {
        // 已有内容 + 粘贴长文（增量超阈值）
        val info = pasteCollapseInfo("已有内容", "已有内容" + "y".repeat(600))
        assertNotNull(info)
        assertEquals("📋 已粘贴文字 +1行（604 字符）", info)
    }
}
