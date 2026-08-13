package com.mlx.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * @文件引用展开（Bug 2）：extractRefs 解析 + expand 内容内联/截断/降级。
 * 用 RealBackend + 临时目录（对齐 RealBackendTest 模式）。
 */
class FileAttachmentsTest {

    private fun backend(): Pair<RealBackend, File> {
        val root = File(System.getProperty("java.io.tmpdir"), "att_${System.nanoTime()}")
        root.mkdirs()
        return RealBackend(root) to root
    }

    @Test
    fun `extractRefs finds multiple refs deduped and ordered`() {
        val refs = FileAttachments.extractRefs("看下 @docs/a.md 和 @src/Main.kt，还有 @docs/a.md")
        assertEquals(listOf("docs/a.md", "src/Main.kt"), refs)
    }

    @Test
    fun `extractRefs ignores skill markers`() {
        // @skill: 前缀是技能选择器标记，不得当文件路径提取
        assertEquals(emptyList<String>(), FileAttachments.extractRefs("用 @skill:review 审查"))
        assertEquals(listOf("a.md"), FileAttachments.extractRefs("用 @skill:review 看 @a.md"))
    }

    @Test
    fun `extractRefs no refs returns empty`() {
        assertEquals(emptyList<String>(), FileAttachments.extractRefs("普通消息没有引用"))
        assertEquals(emptyList<String>(), FileAttachments.extractRefs("邮箱 test@example.com"))
    }

    @Test
    fun `expand inlines file content with attachment block`() {
        val (b, root) = backend()
        b.writeText("docs/a.md", "# 报告")
        val out = FileAttachments.expand("分析 @docs/a.md", b)
        assertTrue(out.contains("── 附件: docs/a.md ──"))
        assertTrue(out.contains("# 报告"))
        assertTrue(out.startsWith("分析 @docs/a.md")) // 原文本保留
        root.deleteRecursively()
    }

    @Test
    fun `expand marks truncation when file exceeds cap`() {
        val (b, root) = backend()
        b.writeText("big.txt", "x".repeat(FileAttachments.MAX_FILE_CHARS + 5000))
        val out = FileAttachments.expand("读 @big.txt", b)
        assertTrue(out.contains("已截断"))
        assertTrue(out.length < "读 @big.txt".length + FileAttachments.MAX_FILE_CHARS + 200)
        root.deleteRecursively()
    }

    @Test
    fun `expand skips missing file keeping token`() {
        val (b, root) = backend()
        val out = FileAttachments.expand("读 @不存在.md", b)
        assertEquals("读 @不存在.md", out) // 原样保留，不阻塞发送
        root.deleteRecursively()
    }

    @Test
    fun `expand skips empty file content`() {
        val (b, root) = backend()
        b.writeText("empty.txt", "")
        val out = FileAttachments.expand("读 @empty.txt", b)
        assertEquals("读 @empty.txt", out)
        root.deleteRecursively()
    }

    @Test
    fun `expand caps at MAX_FILES`() {
        val (b, root) = backend()
        for (i in 1..7) b.writeText("f$i.txt", "内容$i")
        val out = FileAttachments.expand(
            "看 @f1.txt @f2.txt @f3.txt @f4.txt @f5.txt @f6.txt @f7.txt", b,
        )
        // 展开的附件块恰好 MAX_FILES 个（f6/f7 未注入）
        assertEquals(5, Regex("── 附件: ").findAll(out).count())
        assertFalse(out.contains("── 附件: f6.txt"))
        assertFalse(out.contains("── 附件: f7.txt"))
        root.deleteRecursively()
    }

    @Test
    fun `expand no refs returns original text`() {
        val (b, root) = backend()
        b.writeText("a.md", "x")
        assertEquals("普通消息", FileAttachments.expand("普通消息", b))
        root.deleteRecursively()
    }

    @Test
    fun `extractRefs handles chinese paths and punctuation boundaries`() {
        // 中文路径（字符集含 一-鿿）
        assertEquals(listOf("文档/报告.md"), FileAttachments.extractRefs("看下 @文档/报告.md"))
        // 全角逗号不是路径字符 → 自动截断，不吞后续文本（多引用各自正确提取）
        assertEquals(
            listOf("src/Main.kt", "docs/a.md"),
            FileAttachments.extractRefs("看 @src/Main.kt，还有 @docs/a.md"),
        )
        assertEquals(listOf("src/Main.kt"), FileAttachments.extractRefs("看 @src/Main.kt，分析一下"))
        // 中文紧邻 @ 前（中文非 \w，后顾通过）
        assertEquals(listOf("docs/a.md"), FileAttachments.extractRefs("查看@docs/a.md"))
    }

    @Test
    fun `extractRefs does not extract url at-signs`() {
        // URL 中的 @（user@host / /@user/ 路径）不得误判为文件引用
        assertEquals(emptyList<String>(), FileAttachments.extractRefs("参考 https://example.com/@user/profile"))
        assertEquals(emptyList<String>(), FileAttachments.extractRefs("email test@example.com ok"))
    }

    @Test
    fun `extractRefs handles hyphen dot underscore paths`() {
        assertEquals(
            listOf("my-dir/my_file.v2.kt"),
            FileAttachments.extractRefs("读 @my-dir/my_file.v2.kt 分析"),
        )
    }

    @Test
    fun `expand mixed valid missing and empty refs`() {
        val (b, root) = backend()
        b.writeText("a.txt", "内容A")
        b.writeText("c.txt", "内容C")
        b.writeText("empty.txt", "")
        val out = FileAttachments.expand("看 @a.txt @不存在 @c.txt @empty.txt", b)
        assertTrue(out.contains("── 附件: a.txt ──"))
        assertTrue(out.contains("── 附件: c.txt ──"))
        assertFalse(out.contains("── 附件: empty.txt ──")) // 空文件跳过
        assertEquals(2, Regex("── 附件: ").findAll(out).count())
        root.deleteRecursively()
    }

    @Test
    fun `expand output block is not re-extracted by its own regex`() {
        val (b, root) = backend()
        b.writeText("a.md", "内容")
        val out1 = FileAttachments.expand("看 @a.md", b)
        // 展开块前缀不带 @ → 对同一文本再次 expand 不会重复提取附件块
        val out2 = FileAttachments.expand(out1, b)
        assertEquals(1, Regex("── 附件: ").findAll(out2).count())
        root.deleteRecursively()
    }
}
