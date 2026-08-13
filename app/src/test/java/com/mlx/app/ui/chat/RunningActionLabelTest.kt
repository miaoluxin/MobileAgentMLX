package com.mlx.app.ui.chat

import com.mlx.app.data.store.ToolCallRecord
import com.mlx.app.data.store.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 六批：工具名 → 人类可读文案映射 + runningActionLabel 意图优先（纯函数） */
class RunningActionLabelTest {

    @Test
    fun toolNameMappingCoversMainTools() {
        assertEquals("正在读取文件…", toolActionLabel("read_file"))
        assertEquals("正在读取文件…", toolActionLabel("list_files"))
        assertEquals("正在编辑文件…", toolActionLabel("edit_file"))
        assertEquals("正在编辑文件…", toolActionLabel("multi_edit"))
        assertEquals("正在执行命令…", toolActionLabel("shell"))
        assertEquals("正在执行命令…", toolActionLabel("bash_output"))
        assertEquals("正在运行 Python…", toolActionLabel("python_exec"))
        assertEquals("正在搜索文件…", toolActionLabel("grep"))
        assertEquals("正在检索网络…", toolActionLabel("web_fetch"))
    }

    @Test
    fun newMappingsAddedInSixthBatch() {
        assertEquals("正在整理任务清单…", toolActionLabel("todo_add"))
        assertEquals("正在更新任务进度…", toolActionLabel("todo_complete"))
        assertEquals("正在规划方案…", toolActionLabel("submit_plan"))
        assertEquals("正在等待你的选择…", toolActionLabel("choice"))
        assertEquals("正在读取技能说明…", toolActionLabel("read_skill"))
        assertEquals("正在执行技能…", toolActionLabel("run_skill"))
        assertEquals("正在并行调研…", toolActionLabel("subagent"))
        assertEquals("正在等待后台任务…", toolActionLabel("wait_job"))
        assertEquals("正在保存记忆…", toolActionLabel("remember"))
        assertEquals("正在安装技能…", toolActionLabel("install_skill"))
    }

    @Test
    fun unknownToolFallsBackToRawName() {
        assertEquals("正在执行 unknown_tool…", toolActionLabel("unknown_tool"))
    }

    @Test
    fun noRunningToolFallsBackToThinking() {
        assertEquals("正在思考…", runningActionLabel(ChatUiState()))
        assertEquals("正在执行…", runningActionLabel(ChatUiState(todos = listOf(dummyTodo()))))
    }

    @Test
    fun intentTakesPriorityOverMapping() {
        val state = ChatUiState(
            activeTurnTools = listOf(
                ToolCallRecord("c1", "shell", "{}", ToolStatus.RUNNING, intent = "正在运行测试套件"),
            )
        )
        assertEquals("正在运行测试套件", runningActionLabel(state))
    }

    @Test
    fun noIntentFallsBackToMapping() {
        val state = ChatUiState(
            activeTurnTools = listOf(
                ToolCallRecord("c1", "shell", "{}", ToolStatus.RUNNING),
            )
        )
        assertEquals("正在执行命令…", runningActionLabel(state))
    }

    @Test
    fun picksLatestRunningTool() {
        val state = ChatUiState(
            activeTurnTools = listOf(
                ToolCallRecord("c1", "read_file", "{}", ToolStatus.SUCCESS),
                ToolCallRecord("c2", "shell", "{}", ToolStatus.RUNNING, intent = "正在构建"),
            )
        )
        assertEquals("正在构建", runningActionLabel(state))
    }

    // ---- 九批：并行子任务计数 ----

    @Test
    fun parallelSubagentCountCountsRunningOnly() {
        val tools = listOf(
            ToolCallRecord("c1", "subagent", "{}", ToolStatus.RUNNING),
            ToolCallRecord("c2", "subagent", "{}", ToolStatus.RUNNING),
            ToolCallRecord("c3", "planner", "{}", ToolStatus.RUNNING),
            ToolCallRecord("c4", "subagent", "{}", ToolStatus.SUCCESS),
            ToolCallRecord("c5", "read_file", "{}", ToolStatus.RUNNING),
        )
        assertEquals(3, parallelSubagentCount(tools))
        assertEquals(0, parallelSubagentCount(emptyList()))
        assertEquals(0, parallelSubagentCount(listOf(ToolCallRecord("c1", "read_file", "{}", ToolStatus.RUNNING))))
    }

    @Test
    fun statusBarActionShowsParallelCountWhenTwoPlus() {
        val parallel = ChatUiState(
            activeTurnTools = listOf(
                ToolCallRecord("c1", "subagent", "{}", ToolStatus.RUNNING, intent = "分析模块 A"),
                ToolCallRecord("c2", "subagent", "{}", ToolStatus.RUNNING, intent = "分析模块 B"),
            )
        )
        assertEquals("● 2 个并行子任务", statusBarAction(parallel))
        // 单个子代理 → 回退动作文案
        val single = ChatUiState(
            activeTurnTools = listOf(
                ToolCallRecord("c1", "subagent", "{}", ToolStatus.RUNNING, intent = "分析模块 A"),
            )
        )
        assertEquals("分析模块 A", statusBarAction(single))
        // 无子代理 → 正常动作
        val none = ChatUiState(
            activeTurnTools = listOf(
                ToolCallRecord("c1", "shell", "{}", ToolStatus.RUNNING),
            )
        )
        assertEquals("正在执行命令…", statusBarAction(none))
    }

    // ---- 七批：底部任务区纯函数 ----

    @Test
    fun statusBarStateLineFormats() {
        // 十五批：状态行去掉 ↓tokens（tokens 统一由顶部 ContextBar 展示）
        assertEquals("5分55s · 正在读取配置文件", statusBarStateLine(355, "正在读取配置文件"))
        assertEquals("12s · 正在思考…", statusBarStateLine(12, "正在思考…"))
        // 大数字耗时格式化
        assertEquals("1时2分 · 正在执行命令…", statusBarStateLine(3720, "正在执行命令…"))
    }

    @Test
    fun visibleTodosGroupsAndOverflows() {
        val undone = (0 until 6).map { todo("u$it", "任务$it", false) }
        fun assertGroup(todos: List<com.mlx.app.core.tools.AuxTools.TodoStore.Todo>, expectVis: Int, expectOver: Int) {
            val (vis, over) = visibleTodos(todos)
            assertEquals(expectVis, vis.size)
            assertEquals(expectOver, over)
        }
        // 4 条内不折叠
        assertGroup(undone.take(4), 4, 0)
        assertGroup(undone.take(2), 2, 0)
        // 超出 4 条折叠
        assertGroup(undone, 4, 2)
        // 已完成不占未完成额度
        val mixed = undone.take(3) + listOf(todo("d1", "完成", true), todo("d2", "完成2", true))
        assertGroup(mixed, 3, 0)
        // 空列表
        assertGroup(emptyList(), 0, 0)
    }

    @Test
    fun doneCountCountsCompletedOnly() {
        assertEquals(0, doneCount(listOf(todo("a", "x", false))))
        assertEquals(2, doneCount(listOf(todo("a", "x", true), todo("b", "y", true), todo("c", "z", false))))
        assertEquals(0, doneCount(emptyList()))
    }

    private fun dummyTodo() = todo("t1", "x", false)

    private fun todo(id: String, text: String, done: Boolean) = com.mlx.app.core.tools.AuxTools.TodoStore.Todo(
        id = id, text = text, done = done, createdAt = 0L,
    )

    // ---- 十一批：子代理过程可视化纯函数 ----

    @Test
    fun appendSubAgentDeltaAccumulatesAndCaps() {
        val s0 = SubAgentStreamState()
        val s1 = appendSubAgentDelta(s0, "你好", "先思考", now = 1L)
        assertEquals("你好", s1.content)
        assertEquals("先思考", s1.reasoning)
        assertEquals(1L, s1.updatedAt)
        // 累积
        val s2 = appendSubAgentDelta(s1, "世界", null, now = 2L)
        assertEquals("你好世界", s2.content)
        assertEquals("先思考", s2.reasoning) // null 不覆盖
        // 尾部截断
        val cap = appendSubAgentDelta(s0, "x".repeat(9000), "r".repeat(5000), now = 3L)
        assertEquals(8192, cap.content.length)
        assertEquals(4096, cap.reasoning.length)
        assertTrue(cap.content.startsWith("x")) // takeLast 保留尾部
    }

    @Test
    fun applySubAgentDeltaMapsPerCallId() {
        val base = ChatUiState()
        val s1 = applySubAgentDelta(base, "c1", "A", "r1", now = 1L)
        assertEquals(1, s1.subagentStreams.size)
        assertEquals("A", s1.subagentStreams["c1"]!!.content)
        // 同 callId 累积
        val s2 = applySubAgentDelta(s1, "c1", "B", null, now = 2L)
        assertEquals("AB", s2.subagentStreams["c1"]!!.content)
        // 不同 callId 独立（并行子代理不串流）
        val s3 = applySubAgentDelta(s2, "c2", "X", null, now = 3L)
        assertEquals(2, s3.subagentStreams.size)
        assertEquals("AB", s3.subagentStreams["c1"]!!.content)
        assertEquals("X", s3.subagentStreams["c2"]!!.content)
        // 双 null → 原样返回
        assertEquals(s3, applySubAgentDelta(s3, "c9", null, null))
    }
}
