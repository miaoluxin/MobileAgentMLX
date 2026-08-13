package com.mlx.app.core.mcp

import com.mlx.app.core.common.MiniJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MCP 客户端（对应 PC 版 MCP 三传输的移动端实现）。
 * - HTTP（streamable HTTP）：Ktor 等价实现 —— 用 OkHttp 做 JSON-RPC 2.0 同步调用
 * - SSE 传输：MCP 规范中 SSE 端点同样以 HTTP POST 返回事件流；MVP 使用 HTTP 端点
 * - stdio 进程桥：Android 平台限制（无任意子进程），标注"平台限制"（见开发文档 10.4）
 */
object McpClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class McpToolInfo(
        val name: String,
        val description: String,
        /** 服务器 tools/list 返回的 inputSchema（JSON Schema）；此前被丢弃导致模型只能盲传通用 arguments 壳 */
        val inputSchema: Map<String, Any?>? = null,
        /** 只读声明（annotations.readOnlyHint / readOnlyHint；二十二批：readOnly 的 mcp 工具自动放行，不再一律弹审批） */
        val readOnly: Boolean = false,
    )

    /** tools/list 原始条目 → McpToolInfo（纯函数可单测：inputSchema 保留） */
    fun mapTool(raw: Map<String, Any?>): McpToolInfo? {
        val name = (raw["name"] as? String) ?: return null
        val annotations = raw["annotations"] as? Map<String, Any?>
        val readOnly = (raw["readOnlyHint"] as? Boolean) == true ||
            (annotations?.get("readOnlyHint") as? Boolean) == true
        return McpToolInfo(
            name = name,
            description = (raw["description"] as? String) ?: "",
            inputSchema = raw["inputSchema"] as? Map<String, Any?>,
            readOnly = readOnly,
        )
    }

    /** MCP 服务器：initialize + tools/list */
    suspend fun listTools(url: String): Result<List<McpToolInfo>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val init = rpc(url, 1, "initialize", mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf<String, Any?>(),
                "clientInfo" to mapOf("name" to "mlx-mobile", "version" to "0.1"),
            ))
            val result = rpc(url, 2, "tools/list", emptyMap<String, Any?>())
            val tools = (result["tools"] as? List<*>)?.mapNotNull { t ->
                val m = t as? Map<String, Any?> ?: return@mapNotNull null
                mapTool(m)
            } ?: emptyList()
            // MCP 端点需要带 session 头（streamable HTTP）
            tools
        }
    }

    /** 调用远程工具：tools/call */
    suspend fun callTool(url: String, toolName: String, args: Map<String, Any?>): Result<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val result = rpc(url, 3, "tools/call", mapOf("name" to toolName, "arguments" to args))
                val content = (result["content"] as? List<*>)?.joinToString("\n") { c ->
                    val m = c as? Map<String, Any?> ?: return@joinToString ""
                    (m["text"] as? String) ?: ""
                } ?: MiniJson.stringify(result).take(2000)
                content
            }
        }

    private fun rpc(url: String, id: Int, method: String, params: Map<String, Any?>): Map<String, Any?> {
        val body = MiniJson.stringify(
            mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        )
        val req = Request.Builder()
            .url(url.trimEnd('/'))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw java.io.IOException("空响应")
            // streamable HTTP 可能返回 SSE 帧（data: {...}）
            val json = text.lines().firstOrNull { it.startsWith("{") }
                ?: text.lines().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
                ?: text
            val parsed = MiniJson.parse(json) as? Map<String, Any?> ?: throw java.io.IOException("响应非 JSON")
            parsed["error"]?.let { e ->
                val em = e as? Map<String, Any?>
                throw java.io.IOException("MCP 错误: ${em?.get("message") ?: e}")
            }
            return parsed["result"] as? Map<String, Any?> ?: emptyMap()
        }
    }
}

/** MCP 服务器注册表（持久化 JSON） */
class McpRegistry(private val dir: File) {

    data class McpServer(val name: String, val url: String, val enabled: Boolean = true)

    private val file = File(dir, "mcp_servers.json").apply { parentFile?.mkdirs() }

    fun list(): List<McpServer> {
        if (!file.exists()) return emptyList()
        val list = MiniJson.parse(file.readText()) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val m = raw as? Map<String, Any?> ?: return@mapNotNull null
            McpServer(
                name = (m["name"] as? String) ?: return@mapNotNull null,
                url = (m["url"] as? String) ?: "",
                enabled = (m["enabled"] as? Boolean) ?: true,
            )
        }
    }

    fun save(server: McpServer) {
        val all = list().filterNot { it.name == server.name } + server
        file.writeText(MiniJson.stringify(all.map { mapOf("name" to it.name, "url" to it.url, "enabled" to it.enabled) }))
    }

    fun delete(name: String) {
        val all = list().filterNot { it.name == name }
        file.writeText(MiniJson.stringify(all.map { mapOf("name" to it.name, "url" to it.url, "enabled" to it.enabled) }))
    }
}
