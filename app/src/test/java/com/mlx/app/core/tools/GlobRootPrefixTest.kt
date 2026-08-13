package com.mlx.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** 十批：glob 定向遍历起点提取（纯函数） */
class GlobRootPrefixTest {

    @Test
    fun wildcardPatternExtractsStaticPrefix() {
        assertEquals("src", PcTools.globRootPrefix("src/**/*.kt"))
        assertEquals("app/src/main", PcTools.globRootPrefix("app/src/main/**/*.java"))
        assertEquals("src/main", PcTools.globRootPrefix("src/main/*.kt"))
    }

    @Test
    fun leadingWildcardReturnsEmpty() {
        assertEquals("", PcTools.globRootPrefix("**/*.kt"))
        assertEquals("", PcTools.globRootPrefix("*/**/*.md"))
    }

    @Test
    fun noWildcardReturnsWholePattern() {
        assertEquals("src/main", PcTools.globRootPrefix("src/main"))
        assertEquals("docs", PcTools.globRootPrefix("docs/"))
    }

    @Test
    fun middleWildcardTakesPrefixBeforeIt() {
        // "app/**/test/**" → 第一个通配符前是 "app"
        assertEquals("app", PcTools.globRootPrefix("app/**/test/**"))
        // "src/*/test/**" → "src"
        assertEquals("src", PcTools.globRootPrefix("src/*/test/**"))
    }

    @Test
    fun emptyPatternReturnsEmpty() {
        assertEquals("", PcTools.globRootPrefix(""))
        assertEquals("", PcTools.globRootPrefix("/"))
    }
}
