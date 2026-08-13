package com.mlx.app.core.skills

import com.mlx.app.core.memory.SkillStore
import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolResult
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.data.saf.SafRepo

/**
 * 技能顶级工具（二次审查 P2-8，对齐 PC boot.go 的 explore/research/review 专用顶层工具）。
 * 直接调用比 run_skill({name:"explore"}) 更自然；inline 语义返回内置技能剧本 + 任务参数。
 * 归入 PolicyEngine.readOnlyTools（自动放行，不弹审批）。
 */
class TopLevelSkillTools(private val skillStore: SkillStore) {

    /** 注册三个顶级工具（SkillEngine.refreshActiveTools 调用；幂等） */
    fun registerInto(registry: com.mlx.app.core.tools.ToolRegistry) {
        listOf(
            SkillTopTool("explore", "探索项目：快速摸清代码结构与关键模块，输出结构地图", "explore", skillStore),
            SkillTopTool("research", "研究问题：多来源检索、交叉验证、输出研究报告", "research", skillStore),
            SkillTopTool("review", "代码审查：按维度检查改动并输出问题清单", "review", skillStore),
        ).forEach { spec ->
            if (registry.get(spec.name) == null) registry.register(spec)
        }
    }
}

/** 顶级技能工具（inline：返回对应内置技能剧本 + 任务参数，主 Agent 按其方法论执行） */
private class SkillTopTool(
    override val name: String,
    override val description: String,
    private val skillName: String,
    private val skillStore: SkillStore,
) : ToolSpec {
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "task" to mapOf("type" to "string", "description" to "任务描述（要探索/研究/审查的对象与目标）"),
        ),
        "required" to listOf("task"),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val task = (args["task"] as? String)?.trim() ?: ""
        val skill = skillStore.findBy(skillName)
            ?: return ToolResult(false, "内置技能「$skillName」不可用（可能已从技能清单移除）")
        return ToolResult(true, buildString {
            appendLine("【技能 ${skill.name} 剧本（按此执行）】")
            appendLine(skill.content)
            if (task.isNotBlank()) {
                appendLine()
                appendLine("【本次任务】")
                appendLine(task)
            }
        })
    }
}
