package com.mlx.app.core.common

import java.io.File
import java.util.zip.ZipFile

/**
 * Office 三件套轻量预览（docx / xlsx / pptx）—— 零依赖自研解析。
 *
 * OOXML 本质 = zip 包 + XML 文档，文本节点结构固定，纯字符串扫描即可可靠提取：
 * - docx: word/document.xml —— `<w:p>` 段落、`<w:t>` 文本节点
 * - xlsx: xl/worksheets/sheet*.xml —— 单元格 `<c>` + 文本 `<t>`；`xl/sharedStrings.xml` 共享字符串
 *         `<si>` 列表按索引引用（单元格 `<c t="s"><v>N</v>`）；`xl/workbook.xml` 的 sheet 名
 * - pptx: ppt/slides/slide*.xml —— 每页 `<a:t>` 文本节点
 *
 * 只提取文本内容（预览用途）；样式/嵌入对象忽略。解析失败返回 null（UI 提示损坏）。
 */
object OoxmlPreview {

    /** 预览结果（UI 渲染用） */
    sealed interface Result {
        /** 文本流（docx 段落 / pptx 分页标题+正文） */
        data class Text(
            val title: String,
            val paragraphs: List<String>,   // docx：段落文本；pptx：每页一段（含 [第 N 页] 标记）
            val sheets: List<String> = emptyList(), // xlsx 专用：sheet 名列表
            val table: List<List<String>> = emptyList(), // xlsx 专用：当前 sheet 单元格
            val truncated: Boolean = false,
        ) : Result
    }

    private const val MAX_ROWS = 100
    private const val MAX_COLS = 20

    /** 识别文件类型：docx/xlsx/pptx（按扩展名） */
    fun typeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "docx" -> "docx"
        "xlsx" -> "xlsx"
        "pptx" -> "pptx"
        else -> null
    }

    /** 解析文档（从真实路径读取 zip） */
    fun parse(file: File, type: String): Result.Text? = runCatching {
        ZipFile(file).use { zip -> parseZip(zip, type) }
    }.getOrNull()

    /** 解析文档（从字节读取，预览流式读取用） */
    fun parse(bytes: ByteArray, type: String): Result.Text? = runCatching {
        java.io.ByteArrayInputStream(bytes).use { ins ->
            java.util.zip.ZipInputStream(ins).use { zis ->
                val entries = LinkedHashMap<String, ByteArray>()
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && (e.name.endsWith(".xml"))) {
                        entries[e.name] = zis.readBytes()
                    }
                    e = zis.nextEntry
                }
                parseEntries(entries, type)
            }
        }
    }.getOrNull()

    private fun parseZip(zip: ZipFile, type: String): Result.Text? {
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".xml") }
            .associate { it.name to zip.getInputStream(it).readBytes() }
        return parseEntries(entries, type)
    }

    private fun parseEntries(entries: Map<String, ByteArray>, type: String): Result.Text? {
        if (entries.isEmpty()) return null // 损坏/非 OOXML zip
        return when (type) {
            "docx" -> parseDocx(entries)
            "xlsx" -> parseXlsx(entries)
            "pptx" -> parsePptx(entries)
            else -> null
        }
    }

    // ---- docx ----

    private fun parseDocx(entries: Map<String, ByteArray>): Result.Text {
        val doc = entries["word/document.xml"]?.toString(Charsets.UTF_8) ?: return Result.Text("无法解析", emptyList())
        val paragraphs = mutableListOf<String>()
        var i = 0
        while (i < doc.length) {
            val pStart = doc.indexOf("<w:p", i)
            if (pStart < 0) break
            val pEnd = doc.indexOf("</w:p>", pStart)
            if (pEnd < 0) break
            val para = extractTags(doc.substring(pStart, pEnd), "<w:t")
            paragraphs += para
            i = pEnd + 6
        }
        val truncated = paragraphs.size > 500
        return Result.Text("Word 文档", if (truncated) paragraphs.take(500) else paragraphs, truncated = truncated)
    }

    // ---- xlsx ----

    private fun parseXlsx(entries: Map<String, ByteArray>): Result.Text {
        // sharedStrings：<si> 列表，索引 = 出现顺序
        val shared = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { ss ->
            var i = 0
            while (true) {
                val s = ss.indexOf("<si>", i)
                if (s < 0) break
                val e = ss.indexOf("</si>", s)
                if (e < 0) break
                shared += extractTags(ss.substring(s, e), "<t")
                i = e + 5
            }
        }
        // workbook.xml：sheet 名（r:id 顺序）
        val sheets = mutableListOf<String>()
        entries["xl/workbook.xml"]?.toString(Charsets.UTF_8)?.let { wb ->
            var i = 0
            while (true) {
                val s = wb.indexOf("<sheet ", i)
                if (s < 0) break
                val e = wb.indexOf("/>", s)
                if (e < 0) break
                val seg = wb.substring(s, e)
                val name = Regex("""name="([^"]*)"""").find(seg)?.groupValues?.get(1) ?: ""
                if (name.isNotBlank()) sheets += name
                i = e + 2
            }
        }
        // sheet1.xml（首个工作表）
        val sheetXml = entries["xl/worksheets/sheet1.xml"]?.toString(Charsets.UTF_8)
            ?: return Result.Text("Excel 文档", emptyList(), sheets = sheets, truncated = false)
        val table = parseSheetCells(sheetXml, shared)
        val truncated = table.size >= MAX_ROWS
        return Result.Text(
            "Excel 文档",
            emptyList(),
            sheets = sheets.ifEmpty { listOf("Sheet1") },
            table = if (truncated) table.take(MAX_ROWS) else table,
            truncated = truncated,
        )
    }

    /** 解析工作表：行 → 单元格（列字母定位，共享字符串按索引取，行号按 r 属性补空） */
    private fun parseSheetCells(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<Pair<Int, List<Pair<Int, String>>>>()
        var i = 0
        while (true) {
            val rStart = xml.indexOf("<row ", i)
            if (rStart < 0) break
            val rEnd = xml.indexOf("</row>", rStart)
            if (rEnd < 0) break
            val seg = xml.substring(rStart, rEnd)
            val rNum = Regex("""r="(\d+)"""").find(seg)?.groupValues?.get(1)?.toIntOrNull() ?: (rows.size + 1)
            val cells = mutableListOf<Pair<Int, String>>()
            var c = 0
            while (true) {
                val cStart = seg.indexOf("<c ", c)
                if (cStart < 0) break
                val cEnd = seg.indexOf("</c>", cStart)
                if (cEnd < 0) break
                val cellSeg = seg.substring(cStart, cEnd)
                val ref = Regex("""r="([A-Z]+)\d+"""").find(cellSeg)?.groupValues?.get(1) ?: ""
                val col = columnIndex(ref)
                val type = Regex("""t="([^"]*)"""").find(cellSeg)?.groupValues?.get(1)
                var value = extractTags(cellSeg, "<t")
                if (value.isEmpty()) {
                    // 数值/共享字符串：取 <v> 内容
                    val v = Regex("""<v>([^<]*)</v>""").find(cellSeg)?.groupValues?.get(1) ?: ""
                    if (type == "s") {
                        value = v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    } else {
                        value = v
                    }
                }
                if (col >= 0) cells += col to value
                c = cEnd + 4
            }
            rows += rNum to cells
            i = rEnd + 6
        }
        rows.sortBy { it.first }
        val maxCol = rows.map { it.second.maxOfOrNull { p -> p.first } ?: -1 }.maxOrNull() ?: -1
        val effectiveCols = (maxCol + 1).coerceAtMost(MAX_COLS)
        return rows.map { (_, cells) ->
            val line = Array(effectiveCols) { "" }
            for ((col, v) in cells) {
                if (col < effectiveCols) line[col] = v
            }
            line.toList()
        }
    }

    /** 列字母 → 索引（A=0） */
    private fun columnIndex(ref: String): Int {
        if (ref.isEmpty()) return -1
        var v = 0
        for (ch in ref) v = v * 26 + (ch - 'A' + 1)
        return v - 1
    }

    // ---- pptx ----

    private fun parsePptx(entries: Map<String, ByteArray>): Result.Text {
        val slides = entries.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { Regex("""slide(\d+)\.xml""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        val paragraphs = mutableListOf<String>()
        for (slide in slides) {
            val xml = entries[slide]?.toString(Charsets.UTF_8) ?: continue
            val text = extractTags(xml, "<a:t")
            if (text.isNotBlank()) paragraphs += "[第 ${paragraphs.size + 1} 页] $text"
        }
        return Result.Text("PowerPoint 文档", paragraphs, truncated = paragraphs.size > 100)
    }

    // ---- 工具 ----

    /** 提取某标签内所有文本节点的文本（标签内嵌套其他标签时取直接子文本，兼容 <w:t/> 空） */
    private fun extractTags(xml: String, tag: String): String {
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val s = xml.indexOf(tag, i)
            if (s < 0) break
            val gt = xml.indexOf('>', s)
            if (gt < 0) break
            // 自闭合 <w:t/> → 跳过
            if (gt > s && xml[gt - 1] == '/') {
                i = gt + 1
                continue
            }
            val e = xml.indexOf("</", gt + 1)
            if (e < 0) break
            val content = xml.substring(gt + 1, e)
            // 跳过嵌套标签（文本节点直接子内容）
            val clean = xmlEntities(content).replace(Regex("<[^>]*>"), "")
            sb.append(clean)
            i = e + 2
        }
        return sb.toString()
    }

    /** XML 实体解码（预览显示用） */
    private fun xmlEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
