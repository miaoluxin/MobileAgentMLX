package com.mlx.app.core.tools

import com.mlx.app.data.saf.SafRepo

/**
 * M2 补全工具组：todo / choice / 受限 shell（移动端等价物）。
 * - todo：会话内任务清单（对应 PC todo 工具）
 * - choice：破坏性操作门控（对应 PC choice 工具，走审批/选择弹层）
 * - shell：Android 无真实 shell，白名单命令映射到 SAF 文件工具执行
 */
object AuxTools {

    /** Todo id 生成（纯函数可单测）：毫秒时间戳 + 纳秒计数，杜绝同毫秒碰撞 —— 同款 bug：事实记忆 id 碰撞（同毫秒添加 LazyColumn key 重复 → 展开任务清单时崩溃） */
    fun newTodoId(now: Long = System.currentTimeMillis(), nano: Long = System.nanoTime()): String =
        "t${now}_$nano"

    /** Todo 存储：以会话 id 为组的 JSON 文件 */
    class TodoStore(private val dir: java.io.File) {
        data class Todo(val id: String, val text: String, val done: Boolean, val createdAt: Long)

        private fun fileFor(sessionId: String) = java.io.File(dir, "todos_$sessionId.json")

        fun list(sessionId: String): List<Todo> {
            val f = fileFor(sessionId)
            if (!f.exists()) return emptyList()
            val root = com.mlx.app.core.common.MiniJson.parse(f.readText()) as? List<*> ?: return emptyList()
            return root.mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                Todo(
                    id = (m["id"] as? String) ?: "",
                    text = (m["text"] as? String) ?: "",
                    done = (m["done"] as? Boolean) ?: false,
                    createdAt = ((m["createdAt"] as? Number)?.toLong()) ?: 0L,
                )
            // 防历史文件已落盘的重复 id（旧版本同毫秒碰撞产物）导致 LazyColumn key 重复崩溃；
            // distinctBy 保留首次出现 → 反转后去重再反转 = 保留最后写入的一条（最新状态胜出）
            }.reversed().distinctBy { it.id }.reversed()
        }

        fun save(sessionId: String, todos: List<Todo>) {
            val json = com.mlx.app.core.common.MiniJson.stringify(
                todos.map { mapOf("id" to it.id, "text" to it.text, "done" to it.done, "createdAt" to it.createdAt) }
            )
            fileFor(sessionId).writeText(json)
        }

        // 十二批修正：read-modify-write 加锁 —— 引擎（IO 线程 todo_add）与 UI（TodoSheet 勾选）
        // 并发操作会互相覆盖（读到旧态 → 各自写回 → 丢一条）；synchronized 串行化全部修改路径
        @Synchronized
        fun add(sessionId: String, text: String): Todo {
            val t = Todo(newTodoId(), text, false, System.currentTimeMillis())
            save(sessionId, list(sessionId) + t)
            return t
        }

        @Synchronized
        fun setDone(sessionId: String, id: String, done: Boolean) {
            save(sessionId, list(sessionId).map { if (it.id == id) it.copy(done = done) else it })
        }
    }

    class TodoAdd(private val todos: TodoStore) : ToolSpec {
        override val name = "todo_add"
        override val description = "向当前会话的任务清单添加一项待办。任务开始时应先一次性添加完整 todo 骨架，执行中逐项 todo_complete 标记完成。text 必须是人类可读的任务描述（动词短语，如\"解析 xlsx 数据\"），不要使用工具名。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "人类可读的任务描述（动词短语），如：解析 xlsx 数据。不要填工具名")),
            "required" to listOf("text"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val text = (args["text"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 text")
            if (text.isBlank()) return ToolResult(false, "text 不能为空")
            val t = todos.add(ctx.sessionId, text)
            return ToolResult(true, "已添加待办 [$t.id]: $text（共 ${todos.list(ctx.sessionId).size} 项）")
        }
    }

    class TodoList(private val todos: TodoStore) : ToolSpec {
        override val name = "todo_list"
        override val description = "列出当前会话的任务清单（含完成状态）。"
        override val parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any?>(), "required" to listOf<String>())

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val list = todos.list(ctx.sessionId)
            if (list.isEmpty()) return ToolResult(true, "（暂无待办）")
            return ToolResult(true, list.joinToString("\n") { "${if (it.done) "✅" else "⬜"} [${it.id}] ${it.text}" })
        }
    }

    class TodoComplete(private val todos: TodoStore) : ToolSpec {
        override val name = "todo_complete"
        override val description = "将某项待办标记为完成（执行完对应步骤后立即调用，让用户看到进度推进）。id 来自 todo_add 的返回。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("id" to mapOf("type" to "string", "description" to "待办 id（todo_add 返回）")),
            "required" to listOf("id"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val id = (args["id"] as? String) ?: return ToolResult(false, "缺少参数 id")
            todos.setDone(ctx.sessionId, id, true)
            return ToolResult(true, "已完成待办 $id")
        }
    }

    /** subagent：委派聚焦任务给独立子代理（flash，无工具，只读分析） */
    class SubAgentTool(private val manager: com.mlx.app.core.agent.SubAgentManager) : ToolSpec {
        override val name = "subagent"
        override val description = "委派一个聚焦任务给独立子代理（flash 模型，只读分析，不执行工具）。适合：需同时调查多个独立主题/模块（每个主题派一个，同一次回复中并行调用多个 subagent）、广撒网搜索研究、跨域汇报的前期分头调研。不适合：单点查询（读单文件/查单定义）、必须亲自执行工具的步骤。prompt 必须自包含（子代理看不到本会话上下文），按【任务】【上下文】【约束】【期望输出】【质量标准】【禁止事项】【成功定义】【返回形态】组织：写清背景、要回答的问题、输出格式（结论式，可直接被采纳）。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("prompt" to mapOf("type" to "string", "description" to "子代理要完成的任务描述")),
            "required" to listOf("prompt"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val prompt = (args["prompt"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 prompt")
            // 十一批：流式增量透传（reasoning/content）→ 引擎事件 → UI 过程可视化
            val out = manager.runSubAgent(prompt, onDelta = ctx.onSubAgentDelta ?: { _, _ -> })
            return ToolResult(true, out)
        }
    }

    /** planner：只读研究/方案设计（pro 模型） */
    class PlannerTool(private val manager: com.mlx.app.core.agent.SubAgentManager) : ToolSpec {
        override val name = "planner"
        override val description = "委派只读研究/方案设计给规划者（pro 模型）：输出问题拆解、关键决策点与推荐方案。适合复杂任务先规划再执行；可与 subagent 同轮并行派发（planner 规划整体，subagent 分头研究局部）。问题描述（question 参数）必须自包含（子代理看不到本会话上下文），按【任务】【上下文】【约束】【期望输出】【质量标准】【禁止事项】【成功定义】【返回形态】组织：写清背景、要规划的任务与期望的方案粒度。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("question" to mapOf("type" to "string")),
            "required" to listOf("question"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            val question = (args["question"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 question")
            return ToolResult(true, manager.runPlanner(question, onDelta = ctx.onSubAgentDelta ?: { _, _ -> }))
        }
    }

    /**
     * choice（二次审查 P2-7：升级为 PC ask 能力）：破坏性操作门控 —— 引擎在执行前弹出选项供用户选择。
     * 支持多题（questions 数组，1-4 题，含 header/multiSelect/recommendedFirst）或单题（question+options，兼容旧调用）。
     */
    class Choice : ToolSpec {
        override val name = "choice"
        override val description = "向用户提出需要决策的问题（如删除文件、覆盖内容、方案选择），引擎弹出选择界面。用于不可逆/重大决策；可逆小决策不必调用。支持 questions 多题（1-4 题，每题 header/question/options/multiSelect/recommendedFirst）或 question+options 单题。"
        override val parameters = mapOf(
            "type" to "object",
            // 二十二批（审计）：补 required —— 此前无 required，模型可发全空调用徒增一轮
            "required" to listOf("questions", "question", "options"),
            "properties" to mapOf(
                "questions" to mapOf(
                    "type" to "array",
                    "description" to "问题列表（1-4 题；与 question+options 二选一）",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "header" to mapOf("type" to "string", "description" to "分组标题（简短，可选）"),
                            "question" to mapOf("type" to "string", "description" to "问题（简明、单句）"),
                            "options" to mapOf("type" to "array", "description" to "候选选项字符串列表（2-4 个）"),
                            "multiSelect" to mapOf("type" to "boolean", "description" to "是否多选（默认 false）"),
                            "recommendedFirst" to mapOf("type" to "boolean", "description" to "推荐选项是否置于首位（UI 标注推荐，默认 false）"),
                        ),
                    ),
                ),
                "question" to mapOf("type" to "string", "description" to "单题模式问题（与 questions 二选一；兼容旧调用）"),
                "options" to mapOf("type" to "array", "description" to "单题模式选项（2-4 个）"),
            ),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            // 实际选择由引擎拦截（UserChoiceRequired 弹层），此处仅兜底
            return ToolResult(false, "choice 需要用户决策，请通过选择弹层完成")
        }
    }

    /**
     * shell：完整 Linux 环境（嵌入式 Termux）中真实执行 bash -c，任意命令可用。
     * 工作目录 = 项目真实路径（目录即工作区 2.0）；输出逐行实时回调（任务页/状态面板）；
     * 经统一执行通道登记 Task 与进程句柄（可终止、可取消）。
     */
    class Shell(
        private val embedded: com.mlx.app.core.embed.EmbeddedEnv,
        private val registry: ProcessRegistry,
        private val taskStore: com.mlx.app.core.tasks.TaskManager.TaskStore? = null,
    ) : ToolSpec {
        override val name = "shell"
        override val description =
            "在完整 Linux 环境（内置 Termux 发行）中执行 shell 命令（bash -lc），stdout/stderr 合并返回，超时护栏 120 秒。" +
                "支持任意命令：git / python3 / 构建 / 包管理 / 数据处理；工作目录为项目真实目录。" +
                "注意：① 每次调用是全新非交互会话，venv 需显式 source 激活或写解释器绝对路径（如 .venv/bin/python script.py），不要假设装过就能直接用；" +
                "② /tmp 可能无写权限，临时文件放项目工作目录（用后清理）或直接管道传输；" +
                "③ 超过 120 秒的长命令用 bash_output 后台执行 + wait_job 轮询；" +
                "④ 多行 Python 用 heredoc（python3 <<'EOF'）而非 -c 单引号串（f-string 反斜杠转义易报错），Python 任务优先用 python_exec 工具；" +
                "⑤ curl 失败先看退出码（23=写文件错误、28=超时），先抓一小段确认数据源再处理完整数据。" +
                "文件读写/列表/搜索请用专用工具而非 shell。环境未安装时返回引导提示。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("command" to mapOf("type" to "string", "description" to "完整 shell 命令行")),
            "required" to listOf("command"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            if (!embedded.installed) {
                return ToolResult(false, "完整环境未安装 —— 请到 设置 > 完整环境 完成解压（本地解压，无需网络）")
            }
            val cmd = (args["command"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 command")
            return ShellTaskRunner.runProcess(
                embedded = embedded,
                command = listOf(embedded.bashPath, "-lc", cmd),
                label = "shell",
                cwd = ctx.workspaceRoot, // 项目真实路径；null（SAF 工程）回退 HOME
                taskStore = taskStore,
                taskName = "shell: ${cmd.take(40)}",
                projectId = ctx.projectId,
                projectName = ctx.workspaceName,
                sessionId = ctx.sessionId,
                taskIdOverride = ctx.callId.ifBlank { null }, // 十二批：进程注册 key = callId（超时精确杀单工具）
                registry = registry,
                onLine = { line -> ctx.onOutput?.invoke(line) },
            )
        }
    }

    /**
     * python_exec：在完整环境中执行 Python 脚本（数据分析：excel/csv/json/md 等）。
     * 工作目录 = 项目真实路径（脚本内直接用相对路径访问项目文件，不再复制进沙盒）。
     */
    class PythonExecTool(
        private val embedded: com.mlx.app.core.embed.EmbeddedEnv,
        private val registry: ProcessRegistry,
        private val taskStore: com.mlx.app.core.tasks.TaskManager.TaskStore? = null,
    ) : ToolSpec {
        override val name = "python_exec"
        override val description =
            "在完整环境（内置 Python 3 + pip）中执行 Python 脚本。script 为 Python 代码；" +
                "工作目录为项目真实根目录，脚本内用相对路径直接访问项目文件（无需也不支持 files 参数）；" +
                "可用于解析 xlsx/csv/json/md 等文件、数据处理与生成报告。依赖缺失时用 pip install 自行安装；" +
                "输出较大时打印摘要/统计结果而非全量数据。"
        override val parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "script" to mapOf("type" to "string", "description" to "Python 3 代码（在项目根目录执行，用相对路径访问文件）"),
            ),
            "required" to listOf("script"),
        )

        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
            if (!embedded.installed) {
                return ToolResult(false, "完整环境未安装 —— 请到 设置 > 完整环境 完成解压（本地解压，无需网络）")
            }
            val script = (args["script"] as? String)?.trim() ?: return ToolResult(false, "缺少参数 script")
            // 目录即工作区 2.0：脚本直接在项目真实目录内以相对路径访问文件，无需复制
            return ShellTaskRunner.runProcess(
                embedded = embedded,
                command = listOf(embedded.pythonPath, "-c", script),
                label = "python",
                cwd = ctx.workspaceRoot, // 项目真实路径；null（SAF 工程）回退 HOME
                taskStore = taskStore,
                taskName = "python: ${script.take(40)}",
                projectId = ctx.projectId,
                projectName = ctx.workspaceName,
                sessionId = ctx.sessionId,
                taskIdOverride = ctx.callId.ifBlank { null }, // 十二批：进程注册 key = callId（超时精确杀单工具）
                registry = registry,
                onLine = { line -> ctx.onOutput?.invoke(line) },
            )
        }
    }
}
