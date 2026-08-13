package com.mlx.app.data.store

import com.mlx.app.core.agent.AgentEngine
import com.mlx.app.core.cost.CostRecord

/** 工具调用状态（对应 PC 版工具执行状态行） */
enum class ToolStatus(val label: String) {
    RUNNING("执行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    APPROVAL_REQUIRED("待审批"),
    DENIED("已拒绝"),
}

data class ToolCallRecord(
    var id: String,
    val name: String,
    val argsJson: String,
    var status: ToolStatus,
    var resultText: String = "",
    var diffText: String = "",
    /** 相同命令连续失败被合并到同一行的重试次数（UI 防刷屏展示 ×N） */
    var retryCount: Int = 0,
    /** 工具调用意图描述（批次级：模型在调用前输出的正文，对齐 Claude Code "正在做什么"；空=回退工具名映射） */
    var intent: String = "",
)

data class MessageRecord(
    val id: String,
    val role: String,                 // user | assistant | tool
    var content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val toolCallId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

// ---- 执行轨迹步骤树（架构级 11：回合 → 步骤 → 工具调用，持久化可复盘；文档 2.5） ----

/** 回合状态 */
enum class TurnStatus(val label: String) {
    RUNNING("执行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    ABORTED("已停止"), // 二十二批：文案统一（十九批 snackbar 已改"已停止"，此处遗漏）
}

/** 步骤类型（TOOL = 工具调用；TEXT = 助手正文；预留 TODO 扩展） */
enum class StepKind { TOOL, TEXT }

/** 执行步骤（回合内平级；children 为嵌套预留：子代理/MCP 轨迹） */
data class StepRecord(
    val id: String,                    // 与 ToolCallRecord.id 对齐
    val kind: StepKind,
    val name: String,                  // 工具名或 "assistant_text"
    var status: ToolStatus,
    var startedAt: Long = 0L,
    var finishedAt: Long = 0L,
    var durationMs: Long = 0L,
    var argsJson: String = "",
    var resultText: String = "",
    var diffText: String = "",
    var outputRefs: List<String> = emptyList(), // 产物引用（fileChanged 相对路径，复盘定位）
    var children: MutableList<StepRecord> = mutableListOf(),
    /** 工具调用意图描述（批次级，与 ToolCallRecord.intent 对齐；旧数据空串回退工具名映射） */
    var intent: String = "",
)

/** 回合执行记录（每轮用户消息 → 一个回合；状态/耗时/成本/步骤树） */
data class TurnRecord(
    val id: String,
    val turnNumber: Int,               // 第 N 条用户消息（与检查点 turn 对齐）
    val userText: String,
    val startedAt: Long,
    var finishedAt: Long = 0L,
    var status: TurnStatus = TurnStatus.RUNNING,
    var costUsd: Double = 0.0,         // 回合成本合计（冗余，快速展示）
    var steps: MutableList<StepRecord> = mutableListOf(),
)

data class Session(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    var model: String,
    /** 归属工程（真实路径项目 id；"" = 旧数据/全局） */
    var projectId: String = "",
    /** 归属工程名快照（创建时写入；重启后注册表异常/占位重建时展示兜底，不丢名字） */
    var projectName: String = "",
    val messages: MutableList<MessageRecord> = mutableListOf(),
    val costs: MutableList<CostRecord> = mutableListOf(),
    /** 回合执行轨迹（架构级 11；旧 JSON 无此字段 → 空列表，UI 回退平铺渲染） */
    val turns: MutableList<TurnRecord> = mutableListOf(),
) {
    /** 字符/4 近似 token 估算（供压缩触发） */
    fun estimatedTokens(): Long =
        messages.sumOf { m ->
            m.content.length / 4L +
                m.reasoning.length / 4L +
                m.toolCalls.sumOf { it.argsJson.length / 4L + it.resultText.length / 4L }
        }

    fun totalCostUsd(): Double = costs.sumOf { it.costUsd }

    fun totalHitTokens(): Long = costs.sumOf { it.hitTokens }
    fun totalMissTokens(): Long = costs.sumOf { it.missTokens }
    fun totalCompletionTokens(): Long = costs.sumOf { it.completionTokens }

    /** 会话级缓存命中率（无数据返回 null） */
    fun cacheHitRate(): Double? {
        val total = totalHitTokens() + totalMissTokens()
        return if (total == 0L) null else totalHitTokens().toDouble() / total
    }
}

/**
 * 旧会话回合轨迹回填（一次性轻量迁移）：按用户消息分组合成 TurnRecord。
 * 仅在 fromJson 读到旧 JSON（无 turns 键）时调用；合成的步骤无耗时/产物，状态取记录值。
 * 幂等：turns 非空则跳过。
 */
fun backfillTurns(session: Session) {
    if (session.turns.isNotEmpty()) return
    var turnNo = 0
    var cur: TurnRecord? = null
    for (m in session.messages) {
        val isUser = m.role == "user" &&
            !m.content.startsWith("[长期目标] ") &&
            !m.content.startsWith("[记忆回顾]") && // 二十二批：与引擎 isInjected 同口径（去掉尾随空格差异）
            !m.content.startsWith("[技能注入] ") && // 十八批修复（审计）：与引擎 isInjected 同口径
            !AgentEngine.PLAN_FEEDBACK_PREFIXES.any { p -> m.content.startsWith(p) } // 二十二批：计划反馈排除
        if (isUser) {
            turnNo++
            cur = TurnRecord(
                id = "t_backfill_$turnNo",
                turnNumber = turnNo,
                userText = m.content.take(80),
                startedAt = m.createdAt,
                finishedAt = m.createdAt,
                status = TurnStatus.SUCCESS,
            )
            session.turns += cur
        }
        cur?.let { t ->
            if (m.role == "assistant" && m.content.isNotBlank()) {
                t.steps += StepRecord(
                    id = "step_${t.id}_${t.steps.size}",
                    kind = StepKind.TEXT,
                    name = "assistant_text",
                    status = ToolStatus.SUCCESS,
                    startedAt = m.createdAt,
                    finishedAt = m.createdAt,
                    resultText = m.content.take(200),
                )
            }
            m.toolCalls.forEach { tc ->
                t.steps += StepRecord(
                    id = tc.id.ifBlank { "step_${t.id}_${t.steps.size}" },
                    kind = StepKind.TOOL,
                    name = tc.name,
                    status = tc.status,
                    startedAt = m.createdAt,
                    finishedAt = m.createdAt,
                    argsJson = tc.argsJson,
                    resultText = tc.resultText,
                    diffText = tc.diffText,
                    intent = tc.intent,
                )
            }
        }
    }
}
