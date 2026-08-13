package com.mlx.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（P2：零依赖自研，覆盖常见语法）：
 * 标题(#) / 列表(-,1.) / 引用(>) / 行内代码(`) / 代码块(```) / 表格(|) / 分隔线(---) /
 * 粗体(**) 斜体(*) / Mermaid 块样式化。
 * 按行解析，输出 Compose 分段 Column。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, contentColor: Color = Color.Unspecified) {
    val blocks = parseBlocks(markdown)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is Block.Paragraph -> Text(renderInline(block.text, contentColor), style = MaterialTheme.typography.bodyMedium)
                is Block.ListItem -> Text(
                    renderInline(block.text, contentColor),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = (block.indent * 14).dp),
                )
                is Block.Quote -> Text(
                    renderInline(block.text, contentColor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                is Block.CodeBlock -> CodeBlock(block.language, block.code)
                is Block.Table -> {
                    val rows = block.rows
                    if (rows.isNotEmpty()) {
                        Text(
                            rows.joinToString("\n") { it.joinToString(" | ") },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(8.dp),
                        )
                    }
                }
                is Block.Divider -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .width(0.dp)
                )
            }
        }
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class ListItem(val indent: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class CodeBlock(val language: String, val code: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    object Divider : Block
}

private fun parseBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.lines()
    var i = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            out += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过闭合
                out += Block.CodeBlock(lang, code.toString().trimEnd('\n'))
                continue
            }
            trimmed.startsWith("### ") -> { flushParagraph(); out += Block.Heading(3, trimmed.removePrefix("### ")); }
            trimmed.startsWith("## ") -> { flushParagraph(); out += Block.Heading(2, trimmed.removePrefix("## ")); }
            trimmed.startsWith("# ") -> { flushParagraph(); out += Block.Heading(1, trimmed.removePrefix("# ")); }
            trimmed.startsWith("> ") -> { flushParagraph(); out += Block.Quote(trimmed.removePrefix("> ")); }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                out += Block.ListItem(0, trimmed.drop(2))
            }
            trimmed.matches(Regex("\\d+\\.\\s.*")) -> {
                flushParagraph()
                out += Block.ListItem(0, trimmed.replaceFirst(Regex("^\\d+\\.\\s*"), ""))
            }
            trimmed.startsWith("---") || trimmed.startsWith("***") -> { flushParagraph(); out += Block.Divider }
            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                flushParagraph()
                // 收集连续表格行（跳过表头分隔行 |---|）
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (!t.startsWith("|") || !t.endsWith("|")) break
                    if (!t.contains("---")) {
                        rows += t.trim('|').split('|').map { it.trim() }
                    }
                    i++
                }
                if (rows.isNotEmpty()) out += Block.Table(rows)
                continue
            }
            trimmed.isBlank() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    return out
}

/** 行内：粗体/斜体/行内代码 */
private fun renderInline(text: String, contentColor: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    val baseStyle = if (contentColor != Color.Unspecified) SpanStyle(color = contentColor) else SpanStyle()
    var remaining = text
    while (remaining.isNotEmpty()) {
        // 行内代码优先
        val codeStart = remaining.indexOf('`')
        val boldStart = remaining.indexOf("**")
        val italicStart = remaining.indexOf('*')
        val candidates = listOf(
            codeStart to 'c', boldStart to 'b', italicStart to 'i',
        ).filter { it.first >= 0 }
        if (candidates.isEmpty()) {
            withStyle(baseStyle) { append(remaining) }
            break
        }
        val (pos, kind) = candidates.minBy { it.first }
        if (pos > 0) append(remaining.substring(0, pos))
        remaining = remaining.substring(pos)
        when (kind) {
            'c' -> {
                val end = remaining.indexOf('`', 1)
                if (end < 0) { withStyle(baseStyle) { append(remaining) }; break }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))) {
                    append(remaining.substring(1, end))
                }
                remaining = remaining.substring(end + 1)
            }
            'b' -> {
                val end = remaining.indexOf("**", 2)
                if (end < 0) { withStyle(baseStyle) { append(remaining) }; break }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(remaining.substring(2, end))
                }
                remaining = remaining.substring(end + 2)
            }
            'i' -> {
                val end = remaining.indexOf('*', 1)
                if (end < 0) { withStyle(baseStyle) { append(remaining) }; break }
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(remaining.substring(1, end))
                }
                remaining = remaining.substring(end + 1)
            }
        }
    }
}

/** 代码块：等宽 + 深色底 + 语言标签 + 横向滚动；Mermaid 样式化 */
@Composable
private fun CodeBlock(language: String, code: String) {
    val isMermaid = language.equals("mermaid", ignoreCase = true)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .padding(10.dp)
    ) {
        Text(
            if (isMermaid) "📊 Mermaid 图（Android 暂不支持图形渲染，见原文）" else "代码 · ${language.ifBlank { "text" }}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8FA8D8),
        )
        Text(
            code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE4E8F2),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
    }
}
