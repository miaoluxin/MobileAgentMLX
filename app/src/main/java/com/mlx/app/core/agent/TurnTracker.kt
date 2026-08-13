package com.mlx.app.core.agent

import com.mlx.app.data.store.StepKind
import com.mlx.app.data.store.StepRecord
import com.mlx.app.data.store.ToolStatus
import com.mlx.app.data.store.TurnRecord
import com.mlx.app.data.store.TurnStatus

/**
 * 回合轨迹跟踪（纯 Kotlin 可单测；AgentEngine.runTurn 的步骤树写入辅助，架构级 11）。
 * 生命周期：startTurn → addTextStep/beginTool/finishTool（可多次）→ endTurn；reset 清理。
 */
class TurnTracker {

    var current: TurnRecord? = null
        private set

    /** 开启新回合（引擎每轮用户消息调用一次） */
    fun startTurn(turnNumber: Int, userText: String, startedAt: Long): TurnRecord {
        val t = TurnRecord(
            id = "t_${startedAt}_$turnNumber",
            turnNumber = turnNumber,
            userText = userText.take(80),
            startedAt = startedAt,
        )
        current = t
        return t
    }

    /** 记录助手正文步骤（流式正文在回合结束后落一步） */
    fun addTextStep(content: String, at: Long): StepRecord? {
        val t = current ?: return null
        if (content.isBlank()) return null
        val step = StepRecord(
            id = "step_${t.id}_${t.steps.size}",
            kind = StepKind.TEXT,
            name = "assistant_text",
            status = ToolStatus.SUCCESS,
            startedAt = at,
            finishedAt = at,
            resultText = content.take(200),
        )
        t.steps += step
        return step
    }

    /** 工具开始执行（RUNNING 状态入树；callId 与 ToolCallRecord.id 对齐；intent 为批次意图描述） */
    fun beginTool(callId: String, name: String, argsJson: String, at: Long, intent: String = ""): StepRecord? {
        val t = current ?: return null
        if (t.steps.any { it.id == callId }) return null // 防重复入树
        val step = StepRecord(
            id = callId,
            kind = StepKind.TOOL,
            name = name,
            status = ToolStatus.RUNNING,
            startedAt = at,
            argsJson = argsJson,
            intent = intent,
        )
        t.steps += step
        return step
    }

    /** 工具结束：更新状态/耗时/结果/产物引用（拦截拒绝路径同样可收尾） */
    fun finishTool(
        callId: String,
        status: ToolStatus,
        resultText: String,
        at: Long,
        outputRefs: List<String> = emptyList(),
        diffText: String = "",
    ) {
        val t = current ?: return
        val step = t.steps.firstOrNull { it.id == callId } ?: return
        step.status = status
        step.finishedAt = at
        step.durationMs = (at - step.startedAt).coerceAtLeast(0L)
        if (resultText.isNotBlank()) step.resultText = resultText
        if (diffText.isNotBlank()) step.diffText = diffText
        if (outputRefs.isNotEmpty()) step.outputRefs = outputRefs
    }

    /** 回合结束（SUCCESS/FAILED/ABORTED；costUsd = 本回合新增成本合计） */
    fun endTurn(status: TurnStatus, at: Long, costUsd: Double) {
        val t = current ?: return
        t.status = status
        t.finishedAt = at
        t.costUsd = costUsd
    }

    /**
     * 中止收尾：未完成步骤标记失败 + 回合标记 ABORTED（防复盘树永久"执行中"）。
     * 取消传播（doExecute rethrow）后工具不再走 finishTool 收尾，必须在此兜底。
     */
    fun abort(at: Long) {
        val t = current ?: return
        for (step in t.steps) {
            if (step.status == ToolStatus.RUNNING) {
                step.status = ToolStatus.FAILED
                step.finishedAt = at
                step.durationMs = (at - step.startedAt).coerceAtLeast(0L)
                step.resultText = "已停止（用户停止）"
            }
        }
        endTurn(TurnStatus.ABORTED, at, 0.0)
    }

    fun reset() {
        current = null
    }
}
