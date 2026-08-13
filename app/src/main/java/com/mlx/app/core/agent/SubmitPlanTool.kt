package com.mlx.app.core.agent

import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolResult
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.data.saf.SafRepo

/**
 * 计划模式方案提交通具（架构级 13，对齐 Claude Plan 按钮的模型侧信号）。
 * 规划完成时模型调用 submit_plan(plan) → 引擎暂停等待用户审批 →
 * 批准后写拦截解除继续执行；驳回带意见回到规划；拒绝取消执行。
 */
class SubmitPlanTool : ToolSpec {
    override val name = "submit_plan"
    override val description = "【计划模式专用】规划完成时提交执行方案：传入完整方案文本，" +
        "等待用户审批。批准后引擎解除写工具拦截，继续执行方案；驳回（带修改意见）时修改后重新提交。" +
        "仅在计划模式下使用。"
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "plan" to mapOf("type" to "string", "description" to "完整执行方案（步骤/涉及文件/预期改动）"),
        ),
        "required" to listOf("plan"),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val plan = (args["plan"] as? String)?.trim() ?: return ToolResult(false, "缺少方案文本")
        if (plan.isBlank()) return ToolResult(false, "方案不能为空")
        // 引擎对 submit_plan 特判（PlanReady 事件 + 审批等待），此处兜底不应被直接执行
        return ToolResult(false, "submit_plan 需由引擎审批流程处理")
    }
}
