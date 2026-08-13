package com.mlx.app.core.agent

import com.mlx.app.core.mcp.McpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 十一批：MCP 真实参数 schema 注入（mapTool 保留 inputSchema / normalizeMcpSchema 归一化 / 超限回退） */
class McpSchemaTest {

    // ---- McpClient.mapTool：inputSchema 保留 ----

    @Test
    fun mapToolKeepsInputSchema() {
        val raw = mapOf(
            "name" to "search",
            "description" to "搜索文件",
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to mapOf("query" to mapOf("type" to "string")),
                "required" to listOf("query"),
            ),
        )
        val info = McpClient.mapTool(raw)!!
        assertEquals("search", info.name)
        assertEquals("搜索文件", info.description)
        assertNotNull(info.inputSchema)
        assertEquals("query", (info.inputSchema!!["required"] as List<*>)[0])
    }

    @Test
    fun mapToolToleratesMissingSchema() {
        val info = McpClient.mapTool(mapOf("name" to "ping", "description" to "ping"))
        assertNotNull(info)
        assertNull(info!!.inputSchema)
        assertNull(McpClient.mapTool(mapOf("description" to "无名工具")))
    }

    // ---- normalizeMcpSchema：剔除/保留/嵌套 ----

    @Test
    fun normalizeStripsMetaKeysAndKeepsMeaningful() {
        val s = mapOf(
            "\$schema" to "https://json-schema.org/draft-07/schema",
            "\$id" to "urn:x",
            "title" to "搜索参数",
            "additionalProperties" to false,
            "type" to "object",
            "description" to "搜索请求",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "关键字"),
                "limit" to mapOf("type" to "integer", "minimum" to 1, "enum" to listOf(1, 5, 10)),
            ),
            "required" to listOf("query"),
        )
        val out = normalizeMcpSchema(s)!!
        assertNull(out["\$schema"])
        assertNull(out["title"])
        assertEquals("object", out["type"])
        assertEquals("搜索请求", out["description"])
        assertEquals(listOf("query"), out["required"])
        val props = out["properties"] as Map<*, *>
        assertEquals(listOf(1, 5, 10), (props["limit"] as Map<*, *>)["enum"])
    }

    @Test
    fun normalizeHandlesNestedProperties() {
        val s = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filter" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("year" to mapOf("type" to "integer")),
                ),
            ),
        )
        val out = normalizeMcpSchema(s)!!
        val filter = (out["properties"] as Map<*, *>)["filter"] as Map<*, *>
        assertEquals("object", filter["type"])
        assertNotNull((filter["properties"] as Map<*, *>)["year"])
    }

    @Test
    fun normalizeFallsBackWhenOversized() {
        // properties 超过 40 → 回退 null（调用方用通用 arguments 壳）
        val big = mapOf(
            "type" to "object",
            "properties" to (1..41).associate { "p$it" to mapOf("type" to "string") },
        )
        assertNull(normalizeMcpSchema(big))
        // 空/缺失 → null
        assertNull(normalizeMcpSchema(null))
        assertNull(normalizeMcpSchema(emptyMap()))
    }

    // ---- McpToolBridge.parameters：真实 schema 与回退两条路径 ----

    @Test
    fun bridgeUsesRealSchemaWhenAvailable() {
        val bridge = McpToolBridge(
            serverUrl = "http://x",
            remoteName = "search",
            desc = "搜索",
            fullName = "mcp_srv_search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("query" to mapOf("type" to "string")),
                "required" to listOf("query"),
            ),
        )
        val props = bridge.parameters["properties"] as Map<*, *>
        assertTrue(props.containsKey("query")) // 直接铺开真实属性，而非通用 arguments 壳
        assertEquals(listOf("query"), bridge.parameters["required"])
    }

    @Test
    fun bridgeFallsBackToGenericShellWhenSchemaAbsent() {
        val bridge = McpToolBridge("http://x", "ping", "ping", "mcp_srv_ping", null)
        val props = bridge.parameters["properties"] as Map<*, *>
        assertTrue(props.containsKey("arguments")) // 回退通用壳
    }
}
