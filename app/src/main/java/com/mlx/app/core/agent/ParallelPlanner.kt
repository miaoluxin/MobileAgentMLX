package com.mlx.app.core.agent

import com.mlx.app.core.policy.Decision
import com.mlx.app.core.repair.RepairPipeline
import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolRegistry
import com.mlx.app.core.tools.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 同批次工具并行规划（六批：提速 —— 只读工具并行，写工具保持串行）。
 * 纯函数可单测；引擎循环据此把"最大连续可并行段"整组并发执行。
 *
 * 可并行条件（全部满足）：
 * 1. 非交互工具（choice/submit_plan 需冻结等用户，不可并行）
 * 2. 非写工具（用户决策：写工具保持串行，安全优先）
 * 3. 计划模式不拦截（PLANNING 阶段拒绝的写工具不并入并发组）
 * 4. 审批为自动放行（auto/yolo）或策略 ALLOW —— ASK 需弹审批层等用户，保持原串行路径
 */
fun isParallelEligible(
    autoApprove: Boolean,
    decision: Decision,
    name: String,
    planGateBlocked: (String, Map<String, Any?>) -> Boolean,
): Boolean {
    if (name == "choice" || name == "submit_plan") return false
    if (planGateBlocked(name, emptyMap())) return false
    if (ToolRegistry.isWriteTool(name)) return false
    return autoApprove || decision == Decision.ALLOW
}

/**
 * 滑窗取段：从 calls 的索引 [from, end) 找"最大连续可并行段"。
 * 返回段内索引列表（保持原始顺序）；段长 < 2 时返回单元素列表（外层走串行路径）。
 */
fun parallelGroupSpan(
    calls: List<*>,
    from: Int,
    eligible: (Int) -> Boolean,
): List<Int> {
    val span = mutableListOf<Int>()
    var j = from
    while (j < calls.size && eligible(j)) {
        span += j
        j++
    }
    return if (span.size >= 2) span else listOf(from)
}

/**
 * 并发执行器（注入执行 lambda，可单测）：
 * - 按 maxParallel 分块，块内 async 并发、块间串行（控制并发上限，SAF/电量友好）
 * - 结果按输入顺序收集（await 顺序稳定 → 步骤树/消息回填顺序稳定）
 * - 异常隔离：执行 lambda 内自行捕获（引擎 executeTool 不抛，异常转 ToolResult）
 */
class ToolBatchRunner(
    private val maxParallel: Int = 4,
    private val execute: suspend (call: RepairPipeline.AssembledCall, ctx: ToolContext) -> ToolResult,
) {
    /** @param jobs 平行列表：(工具, 其 ToolContext)；返回结果与 jobs 索引对齐 */
    suspend fun run(jobs: List<Pair<RepairPipeline.AssembledCall, ToolContext>>): List<ToolResult> {
        val results = arrayOfNulls<ToolResult>(jobs.size)
        jobs.indices.chunked(maxParallel).forEach { idxChunk ->
            coroutineScope {
                val deferreds = idxChunk.map { idx ->
                    async { execute(jobs[idx].first, jobs[idx].second) }
                }
                deferreds.forEachIndexed { k, d -> results[idxChunk[k]] = d.await() }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as ToolResult }
    }
}
