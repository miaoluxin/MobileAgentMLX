package com.mlx.app.core.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoConfigTest {

    @Test
    fun `picks v4 flash and pro from model list`() {
        val ids = listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-r1")
        val (flash, pro) = AutoConfig.pickModels(ids)
        assertEquals("deepseek-v4-flash", flash)
        assertEquals("deepseek-v4-pro", pro)
    }

    @Test
    fun `falls back to legacy aliases when v4 absent`() {
        val ids = listOf("deepseek-chat", "deepseek-reasoner")
        val (flash, pro) = AutoConfig.pickModels(ids)
        assertEquals("deepseek-chat", flash)
        assertEquals("deepseek-reasoner", pro)
    }

    @Test
    fun `empty list falls back to built-in presets`() {
        val (flash, pro) = AutoConfig.pickModels(emptyList())
        assertEquals(AutoConfig.PREFERRED_FLASH_MODEL, flash)
        assertEquals(AutoConfig.PREFERRED_PRO_MODEL, pro)
    }

    @Test
    fun `unknown model names fall back to presets`() {
        val ids = listOf("some-custom-model", "another-model")
        val (flash, pro) = AutoConfig.pickModels(ids)
        assertEquals("some-custom-model", flash)
        assertEquals("some-custom-model", pro)
    }

    @Test
    fun `flash keyword wins over position`() {
        val ids = listOf("my-pro-model", "super-flash-2")
        val (flash, pro) = AutoConfig.pickModels(ids)
        assertEquals("super-flash-2", flash)
        assertEquals("my-pro-model", pro)
    }
}
