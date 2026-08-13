package com.mlx.app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    @Test
    fun `glob star does not cross slash`() {
        assertTrue(Glob.matches("*.kt", "Main.kt"))
        assertFalse(Glob.matches("*.kt", "src/Main.kt"))
    }

    @Test
    fun `glob double star crosses slash`() {
        assertTrue(Glob.matches("src/**", "src/main/Main.kt"))
        assertTrue(Glob.matches("**/Main.kt", "src/main/Main.kt"))
        assertTrue(Glob.matches("**/Main.kt", "Main.kt"))
        assertFalse(Glob.matches("src/**", "other/x"))
    }

    @Test
    fun `glob question mark matches single char`() {
        assertTrue(Glob.matches("file?.kt", "file1.kt"))
        assertFalse(Glob.matches("file?.kt", "file12.kt"))
    }

    @Test
    fun `deny rule wins over allow`() {
        val engine = PolicyEngine()
        engine.addRule("edit_file:/app/**", Decision.ALLOW)
        engine.addRule("edit_file:/app/secret/**", Decision.DENY)
        assertEquals(Decision.DENY, engine.decide("edit_file", "/app/secret/a.kt"))
        assertEquals(Decision.ALLOW, engine.decide("edit_file", "/app/src/a.kt"))
    }

    @Test
    fun `read-only tools default allow, writes default ask`() {
        val engine = PolicyEngine()
        assertEquals(Decision.ALLOW, engine.decide("read_file", "a.kt"))
        assertEquals(Decision.ALLOW, engine.decide("search_files", ""))
        assertEquals(Decision.ASK, engine.decide("write_file", "a.kt"))
        assertEquals(Decision.ASK, engine.decide("edit_file", "a.kt"))
        assertEquals(Decision.ASK, engine.decide("move_file", "a.kt"))
    }

    @Test
    fun `allow rule overrides write default`() {
        val engine = PolicyEngine()
        engine.addRule("write_file:*", Decision.ALLOW)
        assertEquals(Decision.ALLOW, engine.decide("write_file", "anything"))
    }

    @Test
    fun `tool-level rule without path`() {
        val engine = PolicyEngine()
        engine.addRule("delete:*", Decision.DENY)
        assertEquals(Decision.DENY, engine.decide("delete", "x"))
        assertEquals(Decision.ASK, engine.decide("write_file", "x"))
    }

    @Test
    fun `no-path allow rule matches no-path decide`() {
        // 二十二批（审计）：引擎 addRule 修复后无 path 工具存 `tool`（无冒号），
        // decide 无 path 查 `tool` —— 两端格式统一，"总是允许"对 shell/python 生效
        val engine = PolicyEngine()
        engine.addRule("shell", Decision.ALLOW)
        assertEquals(Decision.ALLOW, engine.decide("shell"))
        assertEquals(Decision.ASK, engine.decide("shell", "some/path")) // 带 path 不匹配无冒号规则
    }

    @Test
    fun `legacy colon-star rule does not match no-path decide`() {
        // 旧格式 `shell:*` 对无 path 判定永不命中（Glob ^shell:[^/]*$ 不匹配无冒号 target）
        val engine = PolicyEngine()
        engine.addRule("shell:*", Decision.ALLOW)
        assertEquals(Decision.ASK, engine.decide("shell"))
    }
}
