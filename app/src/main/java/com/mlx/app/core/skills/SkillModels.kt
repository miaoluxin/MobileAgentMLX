package com.mlx.app.core.skills

/**
 * 技能模型（对齐 PC 版 SKILL.md frontmatter 核心字段；架构级 12 + 审查修正 P2-17）。
 * 兼容纪律：新增字段全部带默认值，旧 skills.json（仅 name/description/content）读入不崩。
 */
data class Skill(
    val name: String,
    val description: String,
    val content: String,             // 剧本正文（Markdown）
    val version: String = "1.0",
    val category: String = "通用",
    val scope: String = "user",      // builtin | user
    val runAs: String = "inline",    // inline | subagent（subagent 复用 SubAgentManager）
    val allowedTools: List<String> = emptyList(), // 子代理工具白名单（subagent 模式）
    val invocation: String = "auto", // auto（进技能索引）| manual（不进索引）
    val triggers: List<String> = emptyList(),     // 触发词（Agent 任务匹配环节）
    // ---- 审查修正 P2-17：对齐 PC 版 AutoUse/ReadOnly/Model/Requires ----
    /** 自动使用策略（PC auto-use）：off（不进索引，仅显式调用）/ suggest（进索引+触发词提示）/ prefer（匹配时优先使用）/ require（匹配时强制使用） */
    val autoUse: String = "suggest",
    /** 只读执行（subagent 模式：子代理提示词注入只读约束，禁止写操作） */
    val readOnly: Boolean = false,
    /** 子代理专用模型覆盖（如 deepseek-v4-pro；空 = 默认 flash） */
    val model: String = "",
    /** 能力依赖声明（如 "mcp-server:github"；当前为展示信息，无网关） */
    val requires: List<String> = emptyList(),
) {
    /** 触发词匹配：任务文本命中任一 trigger（忽略大小写） */
    fun matches(text: String): Boolean =
        triggers.any { t -> text.contains(t, ignoreCase = true) }

    /** 是否进入技能索引（invocation=auto 且 autoUse != off） */
    val indexable: Boolean
        get() = invocation == "auto" && autoUse != "off"
}
