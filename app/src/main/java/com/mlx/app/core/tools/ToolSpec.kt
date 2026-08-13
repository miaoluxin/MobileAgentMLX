package com.mlx.app.core.tools

import com.mlx.app.data.saf.SafRepo

/** 工具执行结果 */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val fileChanged: Boolean = false,
    val diffText: String = "",
)

/** 工具执行上下文（会话维度；todo 等工具按会话隔离） */
data class ToolContext(
    val sessionId: String,
    /** 真实路径工作区（M6 完整环境项目）；null = SAF 项目 */
    val workspaceRoot: java.io.File? = null,
    /** 归属工程 id（任务登记/任务页归属展示） */
    val projectId: String = "",
    /** 归属工程名（任务创建时快照，任务页树状展示不依赖注册表解析） */
    val workspaceName: String = "",
    /** 当前工具调用 id（实时输出/状态关联） */
    val callId: String = "",
    /** 实时输出行回调（逐行推送给 UI：执行状态面板/任务页明细） */
    val onOutput: ((String) -> Unit)? = null,
    /** 子代理流式增量回调（content/reasoning 双字段可空；十一批：子代理过程可视化） */
    val onSubAgentDelta: ((content: String?, reasoning: String?) -> Unit)? = null,
)

/**
 * 工具规范（对应 PC 版 ToolSpec 模式）。
 * parameters 为 JSON Schema（以 Map 表达，序列化时转换）；
 * 参数描述已按"Flatten"修复规则保持浅层结构（叶子参数 ≤10 个、深度 ≤2），
 * 深嵌套参数在描述中展开为点号标记。
 */
interface ToolSpec {
    val name: String
    val description: String
    val parameters: Map<String, Any?>

    /** 执行工具。实现应处理所有异常并返回 ToolResult(ok=false, 原因)。 */
    suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult
}
