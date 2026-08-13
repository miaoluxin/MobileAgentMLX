package com.mlx.app.core.tools

import com.mlx.app.data.saf.SafRepo

/** 工具注册表（对应 PC 版 ToolRegistry，MCP 工具后续桥接于此） */
class ToolRegistry(private val saf: SafRepo) {

    private val tools = LinkedHashMap<String, ToolSpec>()

    init {
        val safBackend = SafBackend(saf)
        register(FileTools.ReadFile(safBackend))
        register(FileTools.WriteFile(safBackend))
        register(FileTools.EditFile(safBackend))
        register(FileTools.MultiEdit(safBackend))
        register(FileTools.ListFiles(safBackend))
        register(FileTools.SearchFiles(safBackend))
        register(FileTools.MoveFile(safBackend))
    }

    /** M2 扩展注册（todo/choice/shell/web_search；由容器装配，带配置依赖） */
    fun registerAux(
        todoStore: AuxTools.TodoStore,
        webSearchTool: WebSearchTool,
        shell: AuxTools.Shell,
    ) {
        register(AuxTools.TodoAdd(todoStore))
        register(AuxTools.TodoList(todoStore))
        register(AuxTools.TodoComplete(todoStore))
        register(AuxTools.Choice())
        register(webSearchTool)
        register(shell)
    }

    fun register(spec: ToolSpec) {
        tools[spec.name] = spec
    }

    fun remove(name: String) {
        tools.remove(name)
    }

    fun get(name: String): ToolSpec? = tools[name]

    fun all(): List<ToolSpec> = tools.values.toList()

    companion object {
        /** 写类工具（计划模式只读拦截/权限决策用）；delete_range 补入（文档 2.4 缺口） */
        val WRITE_TOOLS = setOf("write_file", "edit_file", "multi_edit", "move_file", "delete_range")
        fun isWriteTool(name: String): Boolean = name in WRITE_TOOLS
        // 八批：planModeBlocksWrite 仅测试专用 —— 生产走 PlanGate.writeBlocked（状态机版本），误用会导致计划拦截与状态机脱节

        /**
         * 计划模式写拦截判定（纯函数可单测）：计划模式开启 + 本轮尚未批准 + 写工具 → 拦截。
         * 拦截早于审批门控（Review 模式下计划阶段不弹写审批，避免矛盾体验）。
         */
        fun planModeBlocksWrite(planMode: Boolean, planApproved: Boolean, toolName: String): Boolean =
            planMode && !planApproved && isWriteTool(toolName)
    }
}
