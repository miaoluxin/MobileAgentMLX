package com.mlx.app.core.agent

/**
 * 执行中回合状态快照（六批新增，后台保活 + 通知实时意图）。
 * 引擎用 StateFlow 暴露（事件流无 replay，服务晚订阅会丢 —— 必须 StateFlow 快照）。
 * 单写者：AgentEngine.runTurn 协程内更新；多窗口双回合时最后写者胜出（通知显示最新回合，可接受）。
 */
enum class ActivePhase { IDLE, THINKING, STREAMING, TOOL_RUNNING, WAITING_USER }

data class ActiveTurnStatus(
    val sessionId: String = "",
    val userText: String = "",
    val phase: ActivePhase = ActivePhase.IDLE,
    /** 批次意图描述（模型在工具调用前输出的正文，对齐 Claude Code "正在做什么"） */
    val intent: String = "",
    val toolName: String = "",
    val startedAt: Long = 0L,
    val aborted: Boolean = false,
)

/** 通知文案（纯函数可单测；服务与 App 内统一数据源） */
fun notificationText(s: ActiveTurnStatus): String = when (s.phase) {
    ActivePhase.THINKING -> "正在思考…"
    ActivePhase.STREAMING -> "正在生成…"
    ActivePhase.TOOL_RUNNING -> "正在执行：${s.intent.ifBlank { s.toolName.ifBlank { "工具" } }}"
    ActivePhase.WAITING_USER -> "等待你的操作…"
    ActivePhase.IDLE -> if (s.aborted) "已停止" else "执行完成"
}

/**
 * 批次意图提取（纯函数可单测）：取模型在工具调用前已输出的正文作为该批次的意图描述。
 * 空/纯空白 → ""（UI 回退工具名映射）；多行合并为单行；截断 120 字符。
 * 注意：只取 contentText（用户可见正文），绝不取 reasoningText（思考链是模型内心独白）。
 */
fun intentText(content: String): String {
    val c = content.trim()
    if (c.isEmpty()) return ""
    return c.replace(Regex("\\s+"), " ").take(120)
}

/** 耗时格式化（秒；服务完成通知/App 内 snackbar 共用，纯函数可单测） */
fun fmtSecs(secs: Long): String = when {
    secs < 60 -> "${secs}s"
    secs < 3600 -> "${secs / 60}分${secs % 60}s"
    else -> "${secs / 3600}时${(secs % 3600) / 60}分"
}
