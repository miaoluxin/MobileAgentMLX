package com.mlx.app.core.skills

import com.mlx.app.core.agent.SubAgentManager
import com.mlx.app.core.memory.SkillStore
import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolRegistry
import com.mlx.app.core.tools.ToolResult
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.data.saf.SafRepo

/**
 * 技能引擎（架构级 12，对齐 PC 版 boot.go/index.go/tools.go）：
 * - 技能索引注入系统提示（仅 auto 技能、4KB 截断）
 * - run_skill / read_skill / install_skill 三个通用工具（PC 版同构，不注册每技能独立工具）
 * - 任务规划匹配环节：用户点名要求技能而清单没有 → 明确告知并询问（不静默回退）
 * - URL 一键安装（"给个链接自动装好"）：下载 SKILL.md → 解析 → 存用户技能
 */
class SkillEngine(
    private val skillStore: SkillStore,
    private val registry: ToolRegistry? = null, // 可空：测试/纯索引场景不依赖工具注册表
    private val subAgents: SubAgentManager? = null, // 可空：测试/纯索引场景不依赖子代理
) {

    /** 每回合接线（AgentEngine 在 refreshMcpTools 同位置调用）：幂等注册技能工具 */
    fun refreshActiveTools() {
        val reg = registry ?: return
        listOf(
            RunSkillTool(skillStore, subAgents),
            ReadSkillTool(skillStore),
            InstallSkillTool(skillStore),
        ).forEach { spec ->
            if (reg.get(spec.name) == null) reg.register(spec)
        }
        // 二次审查 P2-8：技能顶级工具（explore/research/review，对齐 PC boot.go）
        TopLevelSkillTools(skillStore).registerInto(reg)
    }

    /**
     * 技能索引块（复刻 PC index.go：仅 auto 技能、`- name [🧬 subagent] — description`、4KB 截断）。
     * 审查修正 P2-17：autoUse=off 不进索引（仅显式调用）；索引行标注 autoUse 策略。
     * 十七批：按 autoUse 权重排序（require/prefer 恒在前）—— 技能增多后 4KB 截断只砍 suggest，
     * 高优先级技能永不因截断而消失（且排前更醒目，缓解注意力稀释）。
     */
    fun skillIndexBlock(): String? {
        val auto = skillStore.list().filter { it.indexable }
        if (auto.isEmpty()) return null
        val ordered = auto.sortedByDescending { autoUseWeight(it.autoUse) }
        val block = buildString {
            appendLine("# 可用技能（任务匹配：收到任务先对照清单，存在匹配技能即按其剧本执行；" +
                "用户点名要求技能而清单中没有时必须明确告知并询问是否用通用方式，不得静默回退。" +
                "autoUse=require 的技能匹配任务必须使用；prefer 优先使用；suggest 按需使用。" +
                "【调用哲学】inline 技能加载成本低：即使任务只是可能相关，也应先加载对应剧本再判断是否采用；" +
                "subagent 技能开销较大，确认需要时才委派）")
            for (s in ordered) {
                val tag = if (s.runAs == "subagent") " [🧬 subagent]" else ""
                val use = when (s.autoUse) {
                    "require" -> " [必须]"
                    "prefer" -> " [优先]"
                    else -> ""
                }
                val triggers = if (s.triggers.isNotEmpty()) "（触发词：${s.triggers.joinToString("/")}）" else ""
                appendLine("- ${s.name}$tag$use — ${s.description}$triggers")
            }
        }
        // 审查修复：按码点安全截断（String.take 会切坏多字节字符）
        // 十八批修复（审计）：截断落到最后一个完整行边界 —— 原实现可能把技能行切成半行（模型看到残缺条目）
        return if (block.length <= 4000) block
        else {
            val capped = block.substring(0, block.offsetByCodePoints(0, 4000).coerceAtMost(block.length))
            val cut = capped.lastIndexOf('\n')
            if (cut > 0) capped.substring(0, cut) + "\n…（技能清单已截断）"
            else capped + "\n…（技能清单已截断）"
        }
    }

    /** autoUse 权重（十七批：索引排序依据；require 最高恒在前） */
    private fun autoUseWeight(autoUse: String): Int = when (autoUse) {
        "require" -> 3
        "prefer" -> 2
        else -> 1
    }

    /** require 级技能命中（纯函数可单测，十七批引擎强制注入）：autoUse=require 且触发词命中任务文本 */
    fun requireMatches(text: String): List<Skill> =
        skillStore.list().filter { it.autoUse == "require" && it.matches(text) }

    /** 按名称查技能（优化 4a：@skill: 点名注入用；未找到返回 null） */
    fun findBy(name: String): Skill? = skillStore.findBy(name)

    /**
     * 缺失技能提示（纯函数可单测）：用户点名要求某技能而清单中没有 → 返回被点名的技能名。
     * 匹配形态："front design skill" / "skill xxx" / "xxx技能" / 直接提及已知技能名。
     * 返回 null = 未点名缺失技能（任务正常走通用工具）。
     */
    fun missingSkillHint(userText: String): String? {
        val known = skillStore.list().map { it.name.lowercase() }.toSet()
        // 已知技能名直接命中 → 不缺失（Agent 应使用它）
        if (skillStore.list().any { userText.contains(it.name, ignoreCase = true) || it.matches(userText) }) return null
        // 英文 "xxx skill" / "skill xxx"
        val en = Regex("""([a-z][a-z0-9 _.-]{1,40}?)\s+skill\b|skill\s+([a-z][a-z0-9 _.-]{1,40}?)""", RegexOption.IGNORE_CASE)
        for (m in en.findAll(userText)) {
            val candidate = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (candidate.length >= 2 && candidate.lowercase() !in known) return candidate
        }
        // 中文 "xxx技能"（剔除常见动词前缀：用/请/帮我/把/让/将/给）
        val zh = Regex("""([一-龥]{2,10})技能""")
        val verbPrefixes = listOf("用", "请", "帮我", "把", "让", "将", "给", "对", "为")
        for (m in zh.findAll(userText)) {
            var candidate = m.groupValues[1].trim()
            for (p in verbPrefixes) {
                if (candidate.startsWith(p)) { candidate = candidate.removePrefix(p); break }
            }
            if (candidate.length >= 2 && candidate !in known) return candidate
        }
        return null
    }
}

/** run_skill（对齐 PC run_skill）：inline → 返回剧本全文（含参考合并）；subagent → 子循环执行 */
private class RunSkillTool(
    private val skillStore: SkillStore,
    private val subAgents: SubAgentManager?,
) : ToolSpec {
    override val name = "run_skill"
    override val description = "调用已安装技能：inline 技能返回其剧本（主 Agent 按其方法论执行）；" +
        "subagent 技能委派独立子代理执行（allowed-tools 约束）。技能名用技能清单中的标识。"
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "技能标识（见技能清单）"),
            "arguments" to mapOf("type" to "string", "description" to "传给技能的自由文本参数（可选）"),
        ),
        "required" to listOf("name"),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val name = (args["name"] as? String)?.trim() ?: return ToolResult(false, "缺少技能名")
        val skill = skillStore.findBy(name) ?: return ToolResult(false, "未找到技能「$name」。技能清单：${skillStore.list().joinToString("、") { it.name }}")
        val extra = (args["arguments"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        if (skill.runAs == "subagent") {
            val sub = subAgents ?: return ToolResult(false, "子代理不可用（技能委派环境未就绪）")
            val prompt = buildString {
                // 审查修正 P2-17：readOnly 技能注入只读约束（安全边界）
                if (skill.readOnly) {
                    appendLine("【只读约束】本任务只读：不得执行任何写入/修改/删除操作，只输出分析与方案。")
                }
                appendLine(skill.content)
                if (extra != null) appendLine()
                appendLine("## 本次任务参数")
                appendLine(extra)
            }
            // 审查修正 P2-17：model 覆盖（技能声明专用模型；空 = 默认 flash）
            // 十一批：流式增量透传（subagent 技能同样过程可视化）
            val onDelta = ctx.onSubAgentDelta ?: { _: String?, _: String? -> }
            return if (skill.model.isNotBlank()) {
                ToolResult(true, sub.runSubAgentWithModel(prompt, skill.model, onDelta = onDelta))
            } else {
                ToolResult(true, sub.runSubAgent(prompt, onDelta = onDelta))
            }
        }
        return ToolResult(true, buildString {
            appendLine("【技能 ${skill.name} 剧本（按此执行）】")
            appendLine(skill.content)
            if (extra != null) {
                appendLine()
                appendLine("【本次任务参数】")
                appendLine(extra)
            }
        })
    }
}

/** read_skill：只读加载技能内容（不执行） */
private class ReadSkillTool(
    private val skillStore: SkillStore,
) : ToolSpec {
    override val name = "read_skill"
    override val description = "只读查看已安装技能的完整剧本内容"
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf("name" to mapOf("type" to "string", "description" to "技能标识")),
        "required" to listOf("name"),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val name = (args["name"] as? String)?.trim() ?: return ToolResult(false, "缺少技能名")
        val skill = skillStore.findBy(name) ?: return ToolResult(false, "未找到技能「$name」")
        return ToolResult(true, buildString {
            appendLine("技能：${skill.name}（${skill.category} v${skill.version}）")
            appendLine("用途：${skill.description}")
            appendLine("模式：${skill.runAs}${if (skill.allowedTools.isNotEmpty()) " · 工具白名单：${skill.allowedTools.joinToString(", ")}" else ""}")
            appendLine("---")
            appendLine(skill.content)
        })
    }
}

/** install_skill：模型代写/安装技能（对齐 PC install_skill） */
private class InstallSkillTool(
    private val skillStore: SkillStore,
) : ToolSpec {
    override val name = "install_skill"
    override val description = "创建/保存技能：给定名称、一行描述与剧本内容，保存为用户技能（内置不可覆盖）。" +
        "用户点名要求不存在的技能时，可用本工具把方法论固化为技能。"
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "技能标识（小写字母数字连字符）"),
            "description" to mapOf("type" to "string", "description" to "一行描述（进入技能索引）"),
            "content" to mapOf("type" to "string", "description" to "剧本正文（Markdown 方法论/步骤/规范）"),
        ),
        "required" to listOf("name", "description", "content"),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val name = (args["name"] as? String)?.trim() ?: return ToolResult(false, "缺少技能名")
        val description = (args["description"] as? String)?.trim() ?: return ToolResult(false, "缺少技能描述")
        val content = (args["content"] as? String)?.trim() ?: return ToolResult(false, "缺少技能内容")
        if (description.isBlank() || content.isBlank()) return ToolResult(false, "描述与内容不能为空")
        val ok = skillStore.save(
            Skill(name = name, description = description, content = content, category = "用户注册")
        )
        return if (ok) ToolResult(true, "✓ 技能「$name」已安装（下次回合生效）")
        else ToolResult(false, "保存失败：技能名无效或与内置技能重名（内置不可覆盖）")
    }
}

/**
 * URL 一键安装（"给个链接自动装好"，对齐 PC install_source URL 路径）：
 * 下载 SKILL.md → 解析 frontmatter → 校验 → 存入用户技能。
 */
suspend fun installSkillFromUrl(
    url: String,
    skillStore: SkillStore,
    fetchText: suspend (String) -> Result<String>,
): Result<Skill> {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return Result.failure(IllegalArgumentException("链接需以 http:// 或 https:// 开头"))
    }
    val text = fetchText(trimmed).getOrElse { return Result.failure(it) }
    val skill = SkillParser.parse(text)
        ?: return Result.failure(IllegalArgumentException("解析失败：内容不是合法的 SKILL.md（需 --- frontmatter --- 头，含 name/description）"))
    val ok = skillStore.save(skill.copy(scope = "user"))
    if (ok) {
        // 二次审查修复：URL 重装已禁用技能 → 自动重新启用（重装 = 重新启用意图）
        skillStore.setEnabled(skill.name, true)
    }
    return if (ok) Result.success(skill)
    else Result.failure(IllegalArgumentException("安装失败：技能名与内置技能重名（内置不可覆盖）或名称为空"))
}

