package com.mlx.app.core.tools

import com.mlx.app.data.saf.SafRepo

/** 内建文件工具（对应 PC 版 filesystem 工具组，SAF 实现） */
object FileTools {

    private fun str(map: Map<String, Any?>, key: String): String = (map[key] as? String) ?: ""

    class ReadFile(private val safBackend: SafBackend) : ToolSpec {
        override val name = "read_file"
        override val description = "读取项目内文本文件内容（UTF-8，超过 1MB 截断并标注）。文件不存在/二进制/无权限时返回明确错误信息，可据此换路径或换工具（search_files/grep）交叉验证。path 为项目根目录起的相对路径。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "相对路径，如 src/Main.kt"),
            ),
            "required" to listOf("path"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val path = str(args, "path")
            if (path.isBlank()) return ToolResult(false, "缺少参数 path")
            val out = b.readText(path, 1_000_000) ?: return ToolResult(false, "文件不存在或不可读: $path")
            val head = if (out.truncated) "\n（文件超过 1MB，已截断前 1MB）\n" else ""
            return ToolResult(true, "--- $path (${out.text.length} 字符) ---\n${out.text}$head")
        }
    }

    class WriteFile(private val safBackend: SafBackend) : ToolSpec {
        override val name = "write_file"
        override val description = "写入/覆盖项目内文本文件（覆盖现有内容）。内容较大时建议分块；修改现有文件优先用 edit_file 精确替换而非整体重写。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "相对路径，如 src/Main.kt"),
                "content" to mapOf("type" to "string", "description" to "要写入的完整文件内容"),
            ),
            "required" to listOf("path", "content"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val path = str(args, "path")
            val content = str(args, "content")
            if (path.isBlank()) return ToolResult(false, "缺少参数 path")
            val ok = b.writeText(path, content)
            return if (ok) ToolResult(true, "已写入 $path（${content.length} 字符）", fileChanged = true)
            else ToolResult(false, "写入失败（路径不可写或父目录不存在）: $path")
        }
    }

    class EditFile(private val safBackend: SafBackend) : ToolSpec {
        override val name = "edit_file"
        override val description = "精确替换文件中的首个匹配片段（search 必须逐字符匹配文件内容，含换行与缩进；多处出现时加入上下文消歧；replace 为空字符串即删除该片段）。返回 diff 预览。修改现有文件优先用它而非 write_file 整体重写。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "相对路径，如 src/Main.kt"),
                "search" to mapOf("type" to "string", "description" to "被替换的原文片段（逐字符匹配，多处出现时需加上下文使其唯一）"),
                "replace" to mapOf("type" to "string", "description" to "替换后的新文本（空串 = 删除该片段）"),
            ),
            "required" to listOf("path", "search", "replace"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val path = str(args, "path")
            val search = str(args, "search")
            val replace = str(args, "replace")
            if (path.isBlank()) return ToolResult(false, "缺少参数 path")
            if (search.isBlank()) return ToolResult(false, "缺少参数 search")
            val out = b.editText(path, search, replace)
                ?: return ToolResult(false, "编辑失败：文件不存在或未找到匹配片段: $path")
            return ToolResult(
                true,
                "已修改 $path\n--- Diff ---\n${out.diffText}",
                fileChanged = true,
                diffText = out.diffText,
            )
        }
    }

    class MultiEdit(private val safBackend: SafBackend) : ToolSpec {
        override val name = "multi_edit"
        override val description = "对多个文件/多个片段批量精确替换。edits 为 [{path, search, replace}] 列表。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "edits" to mapOf(
                    "type" to "array",
                    "description" to "编辑项列表，每项含 path/search/replace",
                ),
            ),
            "required" to listOf("edits"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val edits = args["edits"] as? List<*> ?: return ToolResult(false, "缺少参数 edits")
            val sb = StringBuilder()
            var okCount = 0
            var failCount = 0
            for (raw in edits) {
                val e = raw as? Map<String, Any?> ?: continue
                val path = str(e, "path")
                val search = str(e, "search")
                val replace = str(e, "replace")
                val out = b.editText(path, search, replace)
                if (out != null) {
                    okCount++
                    sb.append("✓ $path 已修改\n")
                } else {
                    failCount++
                    sb.append("✗ $path 未找到匹配片段\n")
                }
            }
            return ToolResult(
                ok = failCount == 0,
                text = "多文件编辑完成：成功 $okCount，失败 $failCount\n$sb",
                fileChanged = okCount > 0,
            )
        }
    }

    class ListFiles(private val safBackend: SafBackend) : ToolSpec {
        override val name = "list_files"
        override val description = "列出项目目录树（含文件大小）。path 缺省为根目录，depth 默认 2 最大 4。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "相对目录路径，缺省为根"),
                "depth" to mapOf("type" to "integer", "description" to "递归深度，1-4"),
            ),
            "required" to listOf<String>(),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val path = str(args, "path")
            val depth = ((args["depth"] as? Number)?.toInt() ?: 2).coerceIn(1, 4)
            val entries = b.listTree(path, depth)
            // 明确区分"无权限"：避免 LLM 误判为"需要授权某个文件夹"（用户选目录时已整树授权）
            if (entries.isEmpty()) {
                return ToolResult(
                    true,
                    "（目录为空，或当前无权限读取：若该目录确实有文件，请到 设置 > 文件访问权限 授予“所有文件访问”后重试）",
                )
            }
            val sb = StringBuilder()
            for (e in entries) {
                val indent = e.relPath.count { it == '/' } * 2
                val size = if (e.isDir) "" else "  ${formatSize(e.size)}"
                sb.append(" ".repeat(indent)).append(if (e.isDir) "📁 " else "📄 ").append(e.relPath).append(size).append('\n')
            }
            return ToolResult(true, "共 ${entries.size} 项（depth=$depth）:\n$sb")
        }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1e6)
            bytes >= 1000 -> "%.1fKB".format(bytes / 1e3)
            else -> "$bytes B"
        }
    }

    class SearchFiles(private val safBackend: SafBackend) : ToolSpec {
        override val name = "search_files"
        override val description = "按文件名关键字搜索项目文件（不搜索文件内容）。最多返回 50 条。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string"),
                "path" to mapOf("type" to "string", "description" to "搜索起点目录，缺省为根"),
            ),
            "required" to listOf("query"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val query = str(args, "query")
            if (query.isBlank()) return ToolResult(false, "缺少参数 query")
            val path = str(args, "path")
            val results = b.search(query, path, 50)
            if (results.isEmpty()) return ToolResult(true, "未找到名称包含 \"$query\" 的文件")
            return ToolResult(true, "找到 ${results.size} 个文件:\n" + results.joinToString("\n") { it.relPath })
        }
    }

    class MoveFile(private val safBackend: SafBackend) : ToolSpec {
        override val name = "move_file"
        override val description = "移动/重命名项目内文件（不可逆操作，移动前先确认目标路径无同名文件）。from 与 to 均为相对路径。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "from" to mapOf("type" to "string", "description" to "源相对路径，如 docs/a.md"),
                "to" to mapOf("type" to "string", "description" to "目标相对路径（可含新文件名实现重命名），如 docs/b.md"),
            ),
            "required" to listOf("from", "to"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: safBackend
            val from = str(args, "from")
            val to = str(args, "to")
            if (from.isBlank() || to.isBlank()) return ToolResult(false, "缺少参数 from/to")
            return if (b.move(from, to)) ToolResult(true, "已移动 $from → $to", fileChanged = true)
            else ToolResult(false, "移动失败: $from → $to")
        }
    }
}
