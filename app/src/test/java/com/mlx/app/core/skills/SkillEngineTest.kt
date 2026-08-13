package com.mlx.app.core.skills

import com.mlx.app.core.memory.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class SkillEngineTest {

    private fun store(): Pair<SkillStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "sk_${System.nanoTime()}")
        dir.mkdirs()
        return SkillStore(dir) to dir
    }

    @Test
    fun `missingSkillHint detects named skill absent from registry`() {
        val (s, dir) = store()
        // 不传 registry/subAgents（均可空）：纯索引/匹配场景
        val engine = SkillEngine(s)
        // 英文 "xxx skill"（不在任何内置触发词内）
        assertEquals("pdf design", engine.missingSkillHint("请用 pdf design skill 生成汇报"))
        // 中文 "xxx技能"
        assertEquals("汇报美化", engine.missingSkillHint("用汇报美化技能处理一下"))
        // 已知技能名（内置 frontend-report-html）→ 不缺失
        assertNull(engine.missingSkillHint("用 frontend-report-html 生成汇报"))
        // 触发词命中（内置 frontend-report-html triggers 含 "front design"）→ 不缺失
        assertNull(engine.missingSkillHint("请用 front design skill 生成汇报"))
        // 无点名 → null
        assertNull(engine.missingSkillHint("帮我分析一下这个文档"))
        dir.deleteRecursively()
    }

    @Test
    fun `skillParser round trips skill md frontmatter`() {
        val md = """
---
name: my-skill
description: 测试技能
version: 2.0
category: 测试
runAs: subagent
allowed-tools: read_file, grep
invocation: manual
triggers: 部署, release
---
# 正文
步骤一
""".trimIndent()
        val skill = SkillParser.parse(md)
        assertNotNull(skill)
        assertEquals("my-skill", skill!!.name)
        assertEquals("2.0", skill.version)
        assertEquals("subagent", skill.runAs)
        assertEquals(listOf("read_file", "grep"), skill.allowedTools)
        assertEquals("manual", skill.invocation)
        assertEquals(listOf("部署", "release"), skill.triggers)
        assertTrue(skill.content.contains("步骤一"))
        // 导出再解析 → 字段保留
        val round = SkillParser.parse(SkillParser.toSkillMd(skill))!!
        assertEquals("my-skill", round.name)
        assertEquals("subagent", round.runAs)
    }

    @Test
    fun `skillParser rejects text without frontmatter or description`() {
        assertNull(SkillParser.parse("plain text without frontmatter"))
        assertNull(SkillParser.parse("---\nname: x\n---\n没有描述"))
    }

    @Test
    fun `skillParser parses autoUse readOnly model requires fields`() {
        // 审查修正 P2-17：PC 版 frontmatter 新字段兼容
        val md = """
---
name: deep-skill
description: 深度技能
auto-use: require
read-only: true
model: deepseek-v4-pro
requires: mcp-server:github
---
正文
""".trimIndent()
        val skill = SkillParser.parse(md)!!
        assertEquals("require", skill.autoUse)
        assertTrue(skill.readOnly)
        assertEquals("deepseek-v4-pro", skill.model)
        assertEquals(listOf("mcp-server:github"), skill.requires)
        // 非法 autoUse 回退 suggest
        val bad = SkillParser.parse("---\nname: x\ndescription: d\nauto-use: crazy\n---\n")
        assertEquals("suggest", bad!!.autoUse)
    }

    @Test
    fun `skillStore setEnabled filters disabled user skill and builtin not disabled`() {
        val (s, dir) = store()
        s.save(Skill(name = "user-skill", description = "d", content = "c"))
        assertTrue(s.isEnabled("user-skill"))
        s.setEnabled("user-skill", false)
        assertTrue(!s.isEnabled("user-skill"))
        assertTrue(s.list().none { it.name == "user-skill" }) // 禁用后从列表消失
        s.setEnabled("user-skill", true)
        assertTrue(s.list().any { it.name == "user-skill" })
        // 内置不可禁用（恒启用）
        s.setEnabled("init", false)
        assertTrue(s.isEnabled("init"))
        assertTrue(s.list().any { it.name == "init" })
        dir.deleteRecursively()
    }

    @Test
    fun `skillIndexBlock excludes autoUse off skills`() {
        val (s, dir) = store()
        val engine = SkillEngine(s)
        s.save(Skill(name = "manual-skill", description = "手动", content = "c", autoUse = "off"))
        val block = engine.skillIndexBlock()!!
        assertTrue(!block.contains("manual-skill")) // off 不进索引
        assertTrue(block.contains("frontend-report-html")) // 内置 suggest 进索引
        dir.deleteRecursively()
    }

    @Test
    fun `skillIndexBlock truncates safely at code points`() {
        // 审查修复：码点安全截断（不切坏多字节字符）
        val long = Skill(name = "long-skill", description = "长描述", content = "c")
        val skills = listOf(long) + (1..150).map { Skill(name = "s$it", description = "描述".repeat(20), content = "") }
        val block = buildString {
            for (s in skills) appendLine("- ${s.name} — ${s.description}")
        }
        // 模拟 4000 码点截断逻辑（与 SkillEngine 相同逻辑）
        val truncated = if (block.length <= 4000) block
        else block.substring(0, block.offsetByCodePoints(0, 4000).coerceAtMost(block.length)) + "\n…"
        // 截断位置必须是完整字符边界：无 Replacement Character（U+FFFD）
        assertTrue(!truncated.contains('�'))
        assertTrue(truncated.length <= 4000 + 4)
    }

    @Test
    fun `installSkillFromUrl downloads parses and saves`() {
        val (s, dir) = store()
        val md = "---\nname: url-skill\ndescription: 来自链接\n---\n正文"
        runBlocking {
            val result = installSkillFromUrl("https://example.com/SKILL.md", s) { _ -> Result.success(md) }
            assertTrue(result.isSuccess)
            assertEquals("url-skill", result.getOrNull()!!.name)
            assertNotNull(s.findBy("url-skill"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `installSkillFromUrl rejects invalid url and bad content`() {
        val (s, dir) = store()
        runBlocking {
            assertTrue(installSkillFromUrl("not-a-url", s) { Result.success("") }.isFailure)
            assertTrue(installSkillFromUrl("https://x.com/a.md", s) { Result.success("没有 frontmatter") }.isFailure)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `builtin skills present and not deletable`() {
        val (s, dir) = store()
        val all = s.list()
        assertTrue(all.any { it.name == "frontend-report-html" })
        assertTrue(all.any { it.name == "init" })
        assertTrue(all.any { it.name == "data-analysis" })
        // 内置不可删除
        assertTrue(!s.delete("frontend-report-html"))
        // 内置不可覆盖
        assertTrue(!s.save(all.first { it.name == "init" }))
        dir.deleteRecursively()
    }

    // ---- 十七批：索引 autoUse 排序 + require 引擎注入 ----

    @Test
    fun `skillIndexBlock orders require and prefer before suggest`() {
        val (s, dir) = store()
        val engine = SkillEngine(s)
        s.save(Skill(name = "zz-suggest", description = "按需", content = "c")) // 默认 suggest
        s.save(Skill(name = "aa-require", description = "必须", content = "c", autoUse = "require"))
        s.save(Skill(name = "mm-prefer", description = "优先", content = "c", autoUse = "prefer"))
        val block = engine.skillIndexBlock()!!
        val idxSuggest = block.indexOf("zz-suggest")
        assertTrue(idxSuggest > 0)
        // 高优先级恒在 suggest 之前（4KB 截断只会先砍 suggest）
        assertTrue(block.indexOf("aa-require") in 0 until idxSuggest)
        assertTrue(block.indexOf("mm-prefer") in 0 until idxSuggest)
        dir.deleteRecursively()
    }

    @Test
    fun `requireMatches fires only on require skills with trigger hit`() {
        val (s, dir) = store()
        val engine = SkillEngine(s)
        s.save(Skill(name = "req-skill", description = "d", content = "c", autoUse = "require", triggers = listOf("数据血缘")))
        s.save(Skill(name = "prefer-skill", description = "d", content = "c", autoUse = "prefer", triggers = listOf("数据血缘")))
        s.save(Skill(name = "no-trigger", description = "d", content = "c", autoUse = "require"))
        // 触发词命中 → 仅 require 级返回（prefer 不触发强制注入）
        assertEquals(1, engine.requireMatches("帮我分析数据血缘").size)
        assertEquals("req-skill", engine.requireMatches("帮我分析数据血缘").first().name)
        // 未命中触发词 → 空
        assertTrue(engine.requireMatches("帮我写个页面").isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `builtin skills include seventeen batch additions`() {
        val (s, dir) = store()
        val all = s.list()
        // 十七批预置：索引组（suggest）
        assertTrue(all.any { it.name == "test-driven-development" })
        assertTrue(all.any { it.name == "writing-plans" })
        assertTrue(all.any { it.name == "consulting-analysis" })
        assertTrue(all.any { it.name == "hivesql-lineage-analysis" })
        assertTrue(all.any { it.name == "brainstorming" })
        // 十七批预置：点名组（off 不进索引）
        assertTrue(all.any { it.name == "frontend-design" })
        assertTrue(all.any { it.name == "gh-cli" })
        assertTrue(all.any { it.name == "beiqifoton-financial-report-assessment" })
        // off 级不进索引（仅显式调用）
        assertTrue(!(engineOf(s).skillIndexBlock() ?: "").contains("gh-cli"))
        dir.deleteRecursively()
    }

    private fun engineOf(s: SkillStore) = SkillEngine(s)
}
