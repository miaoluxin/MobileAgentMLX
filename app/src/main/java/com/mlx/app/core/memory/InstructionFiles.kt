package com.mlx.app.core.memory

import com.mlx.app.data.saf.SafRepo

/**
 * 指令文件加载（对应 PC 版 AGENTS.md / REASONIX.md / CLAUDE.md 层级）：
 * 解析顺序 工作区根 AGENTS.md → REASONIX.md → CLAUDE.md（后读覆盖同主题内容时以拼接方式合并），
 * 支持 @import 文件引用（最多 5 层）。
 * 内容为静态规则 → 进入 IMMUTABLE_PREFIX（缓存稳定）。
 */
object InstructionFiles {

    private const val MAX_IMPORT_DEPTH = 5
    private const val MAX_TOTAL = 12_000

    /** 读取工作区指令文件（含 @import 展开）。返回 null 表示项目无指令文件。 */
    suspend fun load(saf: SafRepo): String? {
        val parts = mutableListOf<String>()
        for (name in listOf("AGENTS.md", "REASONIX.md", "CLAUDE.md")) {
            val text = saf.readText(name)?.text ?: continue
            parts += expandImports(text, saf, 0)
        }
        if (parts.isEmpty()) return null
        var total = 0
        return buildString {
            for (p in parts) {
                if (total >= MAX_TOTAL) break
                val take = p.take(MAX_TOTAL - total)
                append(take).append('\n')
                total += take.length
            }
        }
    }

    private suspend fun expandImports(text: String, saf: SafRepo, depth: Int): String {
        if (depth >= MAX_IMPORT_DEPTH) return text
        val sb = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("@import")) {
                val path = trimmed.removePrefix("@import").trim().removeSurrounding("\"").removeSurrounding("'")
                if (path.isNotBlank()) {
                    val imported = saf.readText(path)?.text
                    if (imported != null) {
                        sb.append("<!-- import: $path -->\n")
                        sb.append(expandImports(imported, saf, depth + 1)).append('\n')
                        continue
                    }
                    sb.append("<!-- import 失败: $path -->\n")
                    continue
                }
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }
}
