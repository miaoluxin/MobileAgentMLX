package com.mlx.app.core.llm

import com.mlx.app.core.common.MiniJson
import com.mlx.app.core.tools.ToolContext
import com.mlx.app.core.tools.ToolResult
import com.mlx.app.core.tools.ToolSpec
import com.mlx.app.data.saf.SafRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientRequestTest {

    private fun parse(body: String): Map<String, Any?> = MiniJson.toMap(MiniJson.parse(body))

    private fun spec(name: String, desc: String) = object : ToolSpec {
        override val name = name
        override val description = desc
        override val parameters: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string")),
            "required" to listOf("path"),
        )
        override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult =
            ToolResult(true, "ok")
    }

    @Test
    fun `default http client read timeout is 120s not infinite`() {
        // 修复：readTimeout(0) 无限等待 → OkHttp 阻塞读时 call.cancel() 失效/滞后（停止按钮无反应根因）；
        // 120s 只约束"连续无数据"时长，缩短停止滞后窗口（300s → 120s）
        val client = DeepSeekClient.defaultHttpClient()
        assertEquals(120, client.readTimeoutMillis / 1000)
    }

    @Test
    fun `auto mode enables thinking with 32k output budget`() {
        val body = buildRequest("deepseek-v4-flash", "auto")
        val req = parse(body)
        val thinking = req["thinking"] as? Map<*, *>
        assertEquals("enabled", thinking?.get("type"))
        assertEquals(32768.0, (req["max_tokens"] as? Number)?.toDouble())
    }

    @Test
    fun `off mode disables thinking without output budget`() {
        val body = buildRequest("deepseek-v4-flash", "off")
        val req = parse(body)
        val thinking = req["thinking"] as? Map<*, *>
        assertEquals("disabled", thinking?.get("type"))
        assertNull(req["max_tokens"])
    }

    @Test
    fun `max mode sets reasoning effort`() {
        val body = buildRequest("deepseek-v4-flash", "max")
        val req = parse(body)
        assertEquals("max", req["reasoning_effort"])
        assertEquals("enabled", (req["thinking"] as? Map<*, *>)?.get("type"))
    }

    @Test
    fun `non-v4 models never get thinking params`() {
        val body = buildRequest("deepseek-chat", "auto")
        val req = parse(body)
        assertNull(req["thinking"])
        assertNull(req["max_tokens"])
    }

    @Test
    fun `stream options request usage`() {
        val req = parse(buildRequest("deepseek-v4-flash", "auto"))
        val opts = req["stream_options"] as? Map<*, *>
        assertEquals(true, opts?.get("include_usage"))
    }

    @Test
    fun `temperature omitted by default like pc`() {
        val req = parse(buildRequest("deepseek-v4-flash", "auto"))
        assertNull(req["temperature"])
    }

    @Test
    fun `temperature sent when configured`() {
        val req = parse(DeepSeekClient().buildChatRequest("deepseek-v4-flash", listOf(ApiMessage("user", "hi")), "auto", temperature = 0.7))
        assertEquals(0.7, (req["temperature"] as? Number)?.toDouble())
    }

    @Test
    fun `tools array carries full function schema`() {
        val body = buildRequest("deepseek-v4-flash", "auto", tools = listOf(spec("read_file", "读取文件")))
        val req = parse(body)
        val tools = req["tools"] as? List<*>
        assertEquals(1, tools?.size)
        val tool = tools?.first() as? Map<*, *>
        assertEquals("function", tool?.get("type"))
        val fn = tool?.get("function") as? Map<*, *>
        assertEquals("read_file", fn?.get("name"))
        assertEquals("读取文件", fn?.get("description"))
        val params = fn?.get("parameters") as? Map<*, *>
        assertEquals("object", params?.get("type"))
        assertNotNull(params?.get("properties"))
    }

    @Test
    fun `messages and stream flags intact`() {
        val body = buildRequest("deepseek-v4-pro", "max")
        val req = parse(body)
        assertEquals("deepseek-v4-pro", req["model"])
        assertEquals(true, req["stream"])
        val msgs = req["messages"] as? List<*>
        assertEquals(3, msgs?.size)
    }

    private fun buildRequest(model: String, mode: String, tools: List<ToolSpec> = emptyList()): String {
        val msgs = listOf(
            ApiMessage("user", "你好"),
            ApiMessage("tool", "结果", toolCallId = "c1"),
            ApiMessage("assistant", "内容", toolCalls = listOf(ApiToolCall("c1", "read_file", """{"path":"a"}"""))),
        )
        return DeepSeekClient().buildChatRequest(model, msgs, mode, tools)
    }
}
