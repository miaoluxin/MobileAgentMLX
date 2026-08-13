package com.mlx.app.core.skills

/**
 * SKILL.md 解析器（对齐 PC 版格式：YAML frontmatter + Markdown 正文）。
 * 支持从 URL 下载/粘贴文本导入 PC 版导出的 SKILL.md（双向互通）。
 * frontmatter 覆盖：name/description/version/category/runAs/allowed-tools/invocation/triggers。
 * 缺 description 的技能拒绝安装（PC 版同规则：无 description 不进索引）。
 */
object SkillParser {

    /** 解析 SKILL.md 文本 → Skill；格式不合法返回 null */
    fun parse(text: String): Skill? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("---")) return null
        val end = trimmed.indexOf("\n---", 3)
        if (end < 0) return null
        val front = trimmed.substring(3, end)
        val body = trimmed.substring(end + 4).trim()
        val fields = mutableMapOf<String, String>()
        front.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key.isNotBlank()) fields[key] = value
            }
        }
        val name = fields["name"]?.trim() ?: return null
        val description = fields["description"]?.trim() ?: return null
        if (name.isBlank() || description.isBlank()) return null
        return Skill(
            name = name,
            description = description,
            content = body,
            version = fields["version"]?.trim() ?: "1.0",
            category = fields["category"]?.trim() ?: "通用",
            runAs = fields["runas"]?.trim() ?: "inline",
            allowedTools = fields["allowed-tools"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            invocation = fields["invocation"]?.trim() ?: "auto",
            triggers = fields["triggers"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            // 审查修正 P2-17：AutoUse/ReadOnly/Model/Requires（PC 版 frontmatter 兼容）
            autoUse = fields["auto-use"]?.trim()?.takeIf { it in setOf("off", "suggest", "prefer", "require") } ?: "suggest",
            readOnly = fields["read-only"]?.trim()?.equals("true", ignoreCase = true) ?: false,
            model = fields["model"]?.trim() ?: "",
            requires = fields["requires"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    /** 导出为 SKILL.md 文本（PC 版可直接使用） */
    fun toSkillMd(s: Skill): String = buildString {
        appendLine("---")
        appendLine("name: ${s.name}")
        appendLine("description: ${s.description}")
        if (s.version != "1.0") appendLine("version: ${s.version}")
        if (s.category != "通用") appendLine("category: ${s.category}")
        if (s.runAs != "inline") appendLine("runAs: ${s.runAs}")
        if (s.allowedTools.isNotEmpty()) appendLine("allowed-tools: ${s.allowedTools.joinToString(", ")}")
        if (s.invocation != "auto") appendLine("invocation: ${s.invocation}")
        if (s.triggers.isNotEmpty()) appendLine("triggers: ${s.triggers.joinToString(", ")}")
        if (s.autoUse != "suggest") appendLine("auto-use: ${s.autoUse}")
        if (s.readOnly) appendLine("read-only: true")
        if (s.model.isNotBlank()) appendLine("model: ${s.model}")
        if (s.requires.isNotEmpty()) appendLine("requires: ${s.requires.joinToString(", ")}")
        appendLine("---")
        appendLine()
        appendLine(s.content.trim())
    }
}
