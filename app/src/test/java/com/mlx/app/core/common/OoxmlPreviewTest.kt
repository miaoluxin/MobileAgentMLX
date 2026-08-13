package com.mlx.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OoxmlPreviewTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `docx extracts paragraphs`() {
        val docx = zip(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to
                "<document><body>" +
                "<w:p><w:r><w:t>第一段内容</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>第二段 &amp; 转义</w:t></w:r></w:p>" +
                "</body></document>",
        )
        val r = OoxmlPreview.parse(docx, "docx")
        assertNotNull(r)
        assertEquals("Word 文档", r!!.title)
        assertEquals(2, r.paragraphs.size)
        assertEquals("第一段内容", r.paragraphs[0])
        assertEquals("第二段 & 转义", r.paragraphs[1])
    }

    @Test
    fun `xlsx resolves shared strings and numbers`() {
        val xlsx = zip(
            "xl/workbook.xml" to "<workbook><sheets><sheet name=\"数据表\"/><sheet name=\"汇总\"/></sheets></workbook>",
            "xl/sharedStrings.xml" to
                "<sst><si><t>产品A</t></si><si><t>产品B</t></si></sst>",
            "xl/worksheets/sheet1.xml" to
                "<worksheet><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>100</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>200.5</v></c></row>" +
                "</sheetData></worksheet>",
        )
        val r = OoxmlPreview.parse(xlsx, "xlsx")
        assertNotNull(r)
        assertEquals(listOf("数据表", "汇总"), r!!.sheets)
        assertEquals(2, r.table.size)
        assertEquals(listOf("产品A", "100"), r.table[0])
        assertEquals(listOf("产品B", "200.5"), r.table[1])
    }

    @Test
    fun `xlsx keeps empty cells in position`() {
        val xlsx = zip(
            "xl/workbook.xml" to "<workbook/>",
            "xl/worksheets/sheet1.xml" to
                "<worksheet><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\"><v>5</v></c></row>" +
                "</sheetData></worksheet>",
            "xl/sharedStrings.xml" to "<sst><si><t>开头</t></si></sst>",
        )
        val r = OoxmlPreview.parse(xlsx, "xlsx")
        assertEquals(listOf("开头", "", "5"), r!!.table[0])
    }

    @Test
    fun `pptx extracts slides in order`() {
        val pptx = zip(
            "ppt/slides/slide2.xml" to "<p:sld><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>第二页内容</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>",
            "ppt/slides/slide1.xml" to "<p:sld><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>第一页标题</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>",
        )
        val r = OoxmlPreview.parse(pptx, "pptx")
        assertNotNull(r)
        assertEquals(2, r!!.paragraphs.size)
        assertTrue(r.paragraphs[0].contains("第一页标题"))
        assertTrue(r.paragraphs[1].contains("第二页内容"))
        // 顺序：slide1 在 slide2 前
        assertTrue(r.paragraphs[0].contains("第 1 页"))
    }

    @Test
    fun `corrupt zip returns null`() {
        val r = OoxmlPreview.parse(ByteArray(64) { 0 }, "docx")
        assertEquals(null, r)
    }
}
