package com.mlx.app.core.agent

import kotlinx.coroutines.CompletableDeferred

/**
 * 计划模式状态机（架构级 13 / 文档 2.4：规划 → 审批 → 执行 两阶段，对齐 Claude Plan Mode）。
 *
 * 状态流转：
 * IDLE → startPlanning() → PLANNING → submitPlan() → PENDING_REVIEW
 *      → respond(Approve) → EXECUTING（写拦截解除）→ endTurn() → IDLE
 *      → respond(Revise) → PLANNING（模型修改后重新提交）
 *      → respond(Reject) → 引擎结束回合（ABORTED）→ endTurn() → IDLE
 *
 * 写拦截：PLANNING 阶段写工具被拒绝（writeBlocked）；EXECUTING/IDLE 放行。
 * **Per-session 隔离（审查修复）**：PlanGate 按会话实例化（AgentEngine.planGateFor(sessionId)），
 * 会话 A 的计划审批不会被会话 B 的回合破坏（跨会话污染修复）。
 * 纯 Kotlin 可单测（不依赖 Android/引擎）。
 */
enum class PlanPhase { IDLE, PLANNING, PENDING_REVIEW, EXECUTING }

sealed interface PlanReviewDecision {
    data class Approve(val planText: String) : PlanReviewDecision
    data class Revise(val comment: String) : PlanReviewDecision
    object Reject : PlanReviewDecision
}

class PlanGate(val sessionId: String) {

    var phase: PlanPhase = PlanPhase.IDLE
        private set

    var pendingPlanText: String = ""
        private set

    private var pendingDeferred: CompletableDeferred<PlanReviewDecision>? = null

    /** 回合开始（计划模式开启时调用）：IDLE → PLANNING */
    fun startPlanning() {
        if (phase != PlanPhase.IDLE) return
        phase = PlanPhase.PLANNING
        pendingPlanText = ""
    }

    /**
     * 模型提交方案（submit_plan 工具调用）：PLANNING → PENDING_REVIEW。
     * 返回引擎 await 的 deferred，由 UI 审批后 respond() 完成。
     * **phase 前置检查（审查修复）**：非 PLANNING 阶段（未开启计划模式/已批准执行中）返回 null，
     * 引擎对其记录 FAILED 而不挂起 —— 修复"非计划模式模型误调 submit_plan → 无限挂起"。
     */
    fun submitPlan(text: String): CompletableDeferred<PlanReviewDecision>? {
        if (phase != PlanPhase.PLANNING) return null
        val deferred = CompletableDeferred<PlanReviewDecision>()
        pendingPlanText = text
        pendingDeferred = deferred
        phase = PlanPhase.PENDING_REVIEW
        return deferred
    }

    /** 用户审批结果：Approve → EXECUTING；Revise → PLANNING（重新规划）；Reject → 停留（引擎结束回合） */
    fun respond(decision: PlanReviewDecision) {
        // 审查修正（停止竞态）：回合已结束（endTurn 复位 IDLE）后 UI 迟到点击 → 直接忽略，
        // 防 phase 被无条件置 EXECUTING 泄漏 → 下一回合 startPlanning 因 phase != IDLE 空转（计划模式卡死）
        if (phase != PlanPhase.PENDING_REVIEW) return
        pendingDeferred?.let { d ->
            if (!d.isCompleted) d.complete(decision) // isCompleted 防护：endTurn 已兜底完成的场景不重复 complete
        }
        pendingDeferred = null
        when (decision) {
            is PlanReviewDecision.Approve -> phase = PlanPhase.EXECUTING
            is PlanReviewDecision.Revise -> {
                phase = PlanPhase.PLANNING
                pendingPlanText = ""
            }
            PlanReviewDecision.Reject -> phase = PlanPhase.IDLE
        }
    }

    /**
     * 回合结束（任何路径）：→ IDLE。
     * PENDING_REVIEW 时兜底 Reject（引擎要结束，挂起的审批自然拒绝）；isCompleted 防护防双重 complete。
     */
    fun endTurn() {
        pendingDeferred?.let { d ->
            if (!d.isCompleted) d.complete(PlanReviewDecision.Reject)
        }
        pendingDeferred = null
        phase = PlanPhase.IDLE
        pendingPlanText = ""
    }

    /**
     * 计划模式是否拦截该工具调用（PLANNING 阶段）。
     * 二十二批（审计 CRITICAL）：此前只拦 WRITE_TOOLS 5 个文件工具，shell/python_exec 可
     * 在规划阶段任意写文件 —— 提示词承诺"引擎会直接拒绝"不成立。现按工具/参数细化：
     * - 文件写工具（write_file/edit_file/…）→ 拦截
     * - shell/bash_output → 只读命令白名单（isReadOnlyShell）放行，其余拦截
     * - python_exec → 一律拦截（无法静态判断只读）
     */
    fun writeBlocked(toolName: String, args: Map<String, Any?>): Boolean {
        if (phase != PlanPhase.PLANNING) return false
        if (com.mlx.app.core.tools.ToolRegistry.isWriteTool(toolName)) return true
        return when (toolName) {
            "shell", "bash_output" -> {
                val cmd = (args["command"] as? String).orEmpty()
                !isReadOnlyShell(cmd)
            }
            "python_exec" -> true
            else -> false
        }
    }

    /** 简版（无 args 场景/测试）：按文件写工具判定，shell/python_exec 保守拦截 */
    fun writeBlocked(toolName: String): Boolean =
        writeBlocked(toolName, emptyMap())

    companion object {
        /** 计划模式只读 shell 命令白名单（首 token 匹配，搜索/查看类） */
        private val READONLY_SHELL_CMDS = setOf(
            "grep", "find", "cat", "head", "tail", "ls", "wc", "sort", "uniq",
            "awk", "cut", "tr", "diff", "stat", "file", "sed", "git",
        )
        /** git 只读子命令白名单 */
        private val READONLY_GIT_SUBS = setOf("status", "log", "diff", "show", "branch", "ls-files")

        /**
         * 纯函数：命令是否为计划模式可用的只读命令。
         * 判定规则：首 token ∈ 白名单、无重定向（> / >> 写文件）、sed 不带 -i、git 仅只读子命令。
         * 管道（|）允许 —— grep|head 等只读组合；tee/echo 等不在白名单，保守拒绝。
         */
        fun isReadOnlyShell(command: String): Boolean {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.contains('>')) return false // 重定向写文件（含 > 与 >>）
            val tokens = trimmed.split(Regex("\\s+"))
            val head = tokens.first()
            return when {
                head !in READONLY_SHELL_CMDS -> false
                head == "sed" && tokens.contains("-i") -> false // sed -i 原地写文件
                head == "git" -> tokens.getOrNull(1) in READONLY_GIT_SUBS
                else -> true
            }
        }
    }
}
