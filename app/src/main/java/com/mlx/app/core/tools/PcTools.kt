package com.mlx.app.core.tools

import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.policy.Glob
import com.mlx.app.data.saf.SafRepo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * P13：PC 端工具集补全 —— grep / glob / web_fetch / update_goal / delete_range /
 * code_index / complete_step / bash_output / kill_shell / wait_job。
 * 对照 PC internal/tool/builtin/ 注册表逐项补齐，消除安卓版工具集割裂。
 */
object PcTools {

    /** grep：文件内容搜索（P13） */
    class GrepTool : ToolSpec {
        override val name = "grep"
        override val description =
            "在项目文件中搜索文本内容（非文件名），返回文件路径与匹配上下文片段。定位代码/关键词位置用此工具而非 shell grep；结果最多 50 条。query 为搜索词，path 为搜索起点目录。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "要搜索的文本内容"),
                "path" to mapOf("type" to "string", "description" to "搜索起点目录，缺省为根"),
            ),
            "required" to listOf("query"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val query = (args["query"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 query")
            val root = (args["path"] as? String) ?: ""
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: SafBackend(saf)
            val results = b.grep(query, root, 50)
            if (results.isEmpty()) return ToolResult(true, "未找到包含 \"$query\" 的文件内容")
            return ToolResult(
                true,
                "找到 ${results.size} 处匹配:\n" + results.joinToString("\n") { "${it.first}: …${it.second}…" },
            )
        }
    }

    /** glob：glob 模式匹配文件列表（P13） */
    class GlobTool : ToolSpec {
        override val name = "glob"
        override val description = "按 glob 模式列出项目文件（如 **/*.kt 或 src/**），最多 100 条。pattern 支持 * ? **；先确认文件是否存在再读取。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("pattern" to mapOf("type" to "string", "description" to "glob 模式，如 **/*.kt")),
            "required" to listOf("pattern"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val pattern = (args["pattern"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 pattern")
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: SafBackend(saf)
            // 十批：定向遍历 —— 提取 pattern 静态前缀作为起点（"src/**/*.kt" → "src"），避免全树扫描（SAF 下省 50-80%）
            val root = globRootPrefix(pattern)
            val all = (if (root.isBlank()) b.listTree("", 4) else b.listTree(root, 4)).filter { !it.isDir }
            val hits = all.filter { Glob.matches(pattern, it.relPath) }.take(100)
            if (hits.isEmpty()) return ToolResult(true, "未匹配 $pattern")
            return ToolResult(true, "匹配 ${hits.size} 个文件:\n" + hits.joinToString("\n") { it.relPath })
        }
    }

    /**
     * 十批：glob 定向遍历起点（纯函数可单测）—— 提取 pattern 中第一个通配符之前的静态路径前缀。
     * 例：src 下的任意层级 kt（"src/任意·星/任意·星.kt"）→ "src"；星号开头 → ""（全树）；"src/main" → "src/main"。
     */
    fun globRootPrefix(pattern: String): String {
        val normalized = pattern.trim('/')
        val wildcardIdx = normalized.indexOfFirst { it == '*' || it == '?' || it == '[' }
        if (wildcardIdx < 0) return normalized
        val slashBefore = normalized.lastIndexOf('/', wildcardIdx)
        return if (slashBefore < 0) "" else normalized.substring(0, slashBefore)
    }

    /** web_fetch：抓取 URL 内容（P12/P13；PC webFetch 对齐） */
    class WebFetchTool : ToolSpec {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        override val name = "web_fetch"
        override val description =
            "抓取指定 URL 的网页内容并转为纯文本（前 32KB）。用户给出链接/网址时使用；也可用于研究类任务收集信息。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("url" to mapOf("type" to "string")),
            "required" to listOf("url"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val url = (args["url"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ToolResult(false, "URL 必须以 http:// 或 https:// 开头")
            }
            return try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) MLX/0.1")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return ToolResult(false, "HTTP ${resp.code}")
                    val body = resp.body?.string() ?: return ToolResult(false, "空响应")
                    val text = htmlToText(body).take(32 * 1024)
                    if (text.isBlank()) return ToolResult(true, "页面无可见文本（可能为 JS 渲染或二进制内容）")
                    ToolResult(true, "--- $url ---\n$text")
                }
            } catch (e: Exception) {
                ToolResult(false, "抓取失败: ${e.message}")
            }
        }

        private fun htmlToText(html: String): String {
            var t = html
            // 移除 script/style/头部
            t = t.replace(Regex("<(script|style|noscript)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
            // 移除标签
            t = t.replace(Regex("<[^>]+>"), " ")
            // 解码实体
            t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
            // 压缩空白
            return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
        }
    }

    /** update_goal：Agent 设置/更新长期目标（P13，对应 PC updateGoal） */
    class UpdateGoalTool(private val setGoal: (String) -> Unit) : ToolSpec {
        override val name = "update_goal"
        override val description = "设置或更新当前长期目标（Agent 跨回合持续遵循，每轮注入）。用户表达长期诉求时设置；目标完成或用户变更时更新。goal 为目标描述；传空字符串清除。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("goal" to mapOf("type" to "string", "description" to "长期目标描述（空串清除）")),
            "required" to listOf("goal"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val goal = (args["goal"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 goal")
            setGoal(goal)
            return ToolResult(true, if (goal.isEmpty()) "已清除长期目标" else "已设置长期目标: $goal")
        }
    }

    /** delete_range：按片段删除（P13，对应 PC deleteRange；复用编辑空替换） */
    class DeleteRangeTool : ToolSpec {
        override val name = "delete_range"
        override val description = "从文件中删除第一个匹配的文本片段（不可逆操作，search 必须逐字符匹配）。用于删除整段代码/文本。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string"),
                "search" to mapOf("type" to "string", "description" to "要删除的原文片段"),
            ),
            "required" to listOf("path", "search"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val path = (args["path"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 path")
            val search = (args["search"] as? String) ?: return ToolResult(false, "缺少参数 search")
            if (search.isBlank()) return ToolResult(false, "search 不能为空")
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: SafBackend(saf)
            val out = b.editText(path, search, "")
                ?: return ToolResult(false, "删除失败：文件不存在或未找到匹配片段: $path")
            return ToolResult(true, "已删除片段（$path）\n--- Diff ---\n${out.diffText}", fileChanged = true, diffText = out.diffText)
        }
    }

    /** code_index：项目文件索引（P13，对应 PC codeIndex 简版） */
    class CodeIndexTool : ToolSpec {
        override val name = "code_index"
        override val description = "扫描项目文件并一次性返回索引摘要（路径/大小/首行内容，JSON，≤24KB）。适合快速了解项目结构；不建立持久索引，不加速后续检索。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("depth" to mapOf("type" to "integer", "description" to "扫描深度 1-3")),
            "required" to listOf<String>(),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val depth = ((args["depth"] as? Number)?.toInt() ?: 2).coerceIn(1, 3)
            val b: FileBackend = ctx.workspaceRoot?.let { RealBackend(it) } ?: SafBackend(saf)
            val entries = b.listTree("", depth).filter { !it.isDir }.take(200)
            val json = entries.map { e ->
                val firstLine = b.readText(e.relPath, 2048)?.text?.lineSequence()?.firstOrNull()?.take(80) ?: ""
                mapOf("path" to e.relPath, "size" to e.size, "firstLine" to firstLine)
            }
            return ToolResult(true, MiniJson.stringify(json).take(24 * 1024))
        }
    }

    /** bash_output：在后台启动 bash 任务（P13，对应 PC bash_output/bgjobs） */
    class BashOutputTool(
        private val startBash: (String, String) -> String, // (name, command) -> taskId
    ) : ToolSpec {
        override val name = "bash_output"
        override val description =
            "在后台启动一个 bash 任务（完整环境执行），立即返回任务 id。适合长命令（构建/批量处理/服务器）；" +
                "用 wait_job 查结果、kill_shell 终止。command 为完整 shell 命令行。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("command" to mapOf("type" to "string")),
            "required" to listOf("command"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val cmd = (args["command"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 command")
            val id = startBash("后台任务", cmd)
            return ToolResult(true, "已启动后台任务 $id\n用 wait_job 查询结果，kill_shell 终止。")
        }
    }

    /** kill_shell：终止后台任务（P13，对应 PC killShell） */
    class KillShellTool(private val killTask: (String) -> Unit) : ToolSpec {
        override val name = "kill_shell"
        override val description = "终止指定的后台任务。task_id 来自 bash_output 的返回。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("task_id" to mapOf("type" to "string")),
            "required" to listOf("task_id"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val id = (args["task_id"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 task_id")
            killTask(id)
            return ToolResult(true, "已请求终止任务 $id")
        }
    }

    /** wait_job：查询后台任务状态与日志（P13，对应 PC waitJob） */
    class WaitJobTool(private val jobStatus: (String) -> String?) : ToolSpec {
        override val name = "wait_job"
        override val description =
            "查询后台任务状态与日志尾部。task_id 来自 bash_output；返回 运行中/成功/失败/已终止 + 最新日志。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("task_id" to mapOf("type" to "string")),
            "required" to listOf("task_id"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val id = (args["task_id"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 task_id")
            val status = jobStatus(id) ?: return ToolResult(false, "任务不存在: $id")
            return ToolResult(true, status)
        }
    }

    /**
     * remember：Agent 自主保存背景事实（对应 PC internal/memory/remember.go）。
     * 保存策略对齐 PC remember_policy.go：有界（≤500 字）、非敏感（拒绝密钥类内容）、
     * 类型限制（project/reference 自动写；user 类型也允许但管理页可删）。
     * 复用同内容 id 即更新（PC "reuse name" 语义）。
     */
    class RememberTool(
        private val memory: com.mlx.app.core.memory.FactMemory,
    ) : ToolSpec {
        override val name = "remember"
        override val description =
            "将值得长期记住的背景事实保存到记忆库（每回合自动召回辅助后续对话）。" +
                "content 为事实内容（≤500 字）；type 可选 project/reference/user，缺省 project。" +
                "敏感信息（密钥/密码/token）禁止保存。同内容重复保存会更新原条目而非新增（复用 id）。" +
                "环境/命令踩坑经验（如 /tmp 不可写）值得主动沉淀。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "content" to mapOf("type" to "string", "description" to "要记住的事实内容"),
                "type" to mapOf("type" to "string", "description" to "project/reference/user，缺省 project"),
            ),
            "required" to listOf("content"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val content = (args["content"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 content")
            // 策略：有界 + 非敏感（对齐 PC remember_policy.go）
            if (content.length > 500) return ToolResult(false, "内容超过 500 字上限，请精简后保存")
            val sensitive = Regex(
                "(?i)(api[_-]?key|secret|password|passwd|token|bearer|sk-[a-z0-9]{8,})"
            ).containsMatchIn(content)
            if (sensitive) return ToolResult(false, "检测到疑似敏感信息（密钥/token），禁止保存到记忆")
            val type = (args["type"] as? String)?.takeIf { it in setOf("project", "reference", "user") } ?: "project"
            // 复用同内容 id 即更新
            val existing = memory.list().firstOrNull { it.content == content }
            if (existing != null) {
                memory.update(existing.id, content)
                return ToolResult(true, "已更新记忆 [${existing.id}]（${type}）")
            }
            val fact = memory.add(type, content)
            return ToolResult(true, "已记住 [${fact.id}]（${type}）：${content.take(40)}")
        }
    }

    /** forget：Agent 归档事实（对应 PC internal/memory/forget.go） */
    class ForgetTool(
        private val memory: com.mlx.app.core.memory.FactMemory,
    ) : ToolSpec {
        override val name = "forget"
        override val description =
            "删除/归档一条背景事实。id 来自 remember 的返回或记忆管理页。" +
                "优先用 remember 更新（复用同内容），forget 仅用于不再需要的事实。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("id" to mapOf("type" to "string")),
            "required" to listOf("id"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val id = (args["id"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 id")
            val existed = memory.list().any { it.id == id }
            if (!existed) return ToolResult(false, "记忆不存在: $id")
            memory.delete(id)
            return ToolResult(true, "已删除记忆 $id")
        }
    }

    /** complete_step：回合步骤完成标记（P13，对应 PC completeStep） */
    class CompleteStepTool : ToolSpec {
        override val name = "complete_step"
        override val description = "标记当前任务步骤已完成并返回简短说明（纯汇报，不修改任务清单）。多步任务的节奏控制用；任务清单进度请用 todo_complete。调用时在 summary 中附上完成证据（验证输出/测试结果/检查结论），让进度可见可信。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("summary" to mapOf("type" to "string", "description" to "本步骤完成说明")),
            "required" to listOf<String>(),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val summary = (args["summary"] as? String)?.takeIf { it.isNotBlank() } ?: "步骤完成"
            return ToolResult(true, "[步骤完成] $summary —— 可继续下一步。")
        }
    }
}
