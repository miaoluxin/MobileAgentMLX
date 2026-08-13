package com.mlx.app.core.memory

import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.skills.BuiltinSkills
import com.mlx.app.core.skills.Skill
import java.io.File

/**
 * 技能系统（架构级 12，对齐 PC 版 SKILL.md 生态）：
 * - 技能 = 内置（BuiltinSkills 静态数据，不可删改）+ 用户（skills.json，UI/URL 安装/install_skill 写入）
 * - 用户技能序列化含全部字段；旧 JSON（仅 name/description/content）读入缺省默认值，向后兼容
 * - 内容为静态规则 → 进入 IMMUTABLE_PREFIX（缓存稳定），技能索引注入由 SkillEngine 负责
 */
class SkillStore(private val dir: File) {

    private val file = File(dir, "skills.json").apply { parentFile?.mkdirs() }
    private val disabledFile = File(dir, "disabled-skills.json").apply { parentFile?.mkdirs() }

    /** 全部技能 = 内置 + 用户（内置优先，用户技能与内置重名被忽略，不可覆盖内置；已禁用技能过滤） */
    fun list(): List<Skill> {
        val builtin = BuiltinSkills.all
        val builtinNames = builtin.map { it.name }.toSet()
        val disabled = loadDisabled()
        return builtin + loadUser().filter { it.name !in builtinNames && it.name !in disabled }
    }

    /** 启用/禁用用户技能（P2-18 对齐 PC /skills enable/disable；内置不可禁用） */
    fun setEnabled(name: String, enabled: Boolean) {
        if (BuiltinSkills.all.any { it.name == name }) return
        val d = loadDisabled().toMutableSet()
        if (enabled) d.remove(name) else d.add(name)
        disabledFile.writeText(MiniJson.stringify(d.toList()))
    }

    fun isEnabled(name: String): Boolean =
        if (BuiltinSkills.all.any { it.name == name }) true else name !in loadDisabled()

    private fun loadDisabled(): Set<String> {
        if (!disabledFile.exists()) return emptySet()
        val list = MiniJson.parse(disabledFile.readText()) as? List<*> ?: return emptySet()
        return list.mapNotNull { it as? String }.toSet()
    }

    fun findBy(name: String): Skill? = list().firstOrNull { it.name == name }

    /** 保存用户技能；内置名/空名拒绝。重名覆盖（用户技能之间）。 */
    fun save(skill: Skill): Boolean {
        if (skill.name.isBlank() || skill.scope == "builtin") return false
        if (BuiltinSkills.all.any { it.name == skill.name }) return false
        val all = loadUser().filterNot { it.name == skill.name } + skill
        writeUser(all)
        return true
    }

    /** 删除用户技能；内置返回 false（不可删） */
    fun delete(name: String): Boolean {
        if (BuiltinSkills.all.any { it.name == name }) return false
        val all = loadUser().filterNot { it.name == name }
        writeUser(all)
        return true
    }

    private fun loadUser(): List<Skill> {
        if (!file.exists()) return emptyList()
        val list = MiniJson.parse(file.readText()) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val m = raw as? Map<String, Any?> ?: return@mapNotNull null
            Skill(
                name = (m["name"] as? String) ?: return@mapNotNull null,
                description = (m["description"] as? String) ?: "",
                content = (m["content"] as? String) ?: "",
                // 新字段缺省默认值：旧 skills.json 兼容
                version = (m["version"] as? String) ?: "1.0",
                category = (m["category"] as? String) ?: "通用",
                scope = (m["scope"] as? String) ?: "user",
                runAs = (m["runAs"] as? String) ?: "inline",
                allowedTools = (m["allowedTools"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                invocation = (m["invocation"] as? String) ?: "auto",
                triggers = (m["triggers"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                // 审查修正 P2-17：AutoUse/ReadOnly/Model/Requires（旧 JSON 缺省默认）
                autoUse = (m["autoUse"] as? String) ?: "suggest",
                readOnly = (m["readOnly"] as? Boolean) ?: false,
                model = (m["model"] as? String) ?: "",
                requires = (m["requires"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            )
        }
    }

    private fun writeUser(skills: List<Skill>) {
        file.writeText(
            MiniJson.stringify(
                skills.map {
                    mapOf(
                        "name" to it.name, "description" to it.description, "content" to it.content,
                        "version" to it.version, "category" to it.category, "scope" to it.scope,
                        "runAs" to it.runAs, "allowedTools" to it.allowedTools,
                        "invocation" to it.invocation, "triggers" to it.triggers,
                        "autoUse" to it.autoUse, "readOnly" to it.readOnly,
                        "model" to it.model, "requires" to it.requires,
                    )
                }
            )
        )
    }
}
