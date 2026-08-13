package com.mlx.app.core.tools

import com.mlx.app.data.saf.SafRepo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网络搜索（对应 PC 8 后端搜索的移动端实现）：
 * - bing：HTML 直抓（无需 key，结果解析于页面结构）
 * - tavily / baidu：HTTP 直连，需在设置中配置 API Key
 */
object WebSearch {

    enum class Backend(val label: String, val needsKey: Boolean) {
        BING("Bing（无需 Key）", false),
        TAVILY("Tavily（需 Key）", true),
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun search(
        backend: Backend,
        query: String,
        apiKey: String?,
        count: Int = 5,
    ): Result<List<SearchResult>> = withContextIO {
        when (backend) {
            Backend.BING -> bingSearch(query, count)
            Backend.TAVILY -> tavilySearch(query, apiKey, count)
        }
    }

    data class SearchResult(val title: String, val url: String, val snippet: String)

    private suspend fun <T> withContextIO(block: () -> Result<T>): Result<T> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    private fun bingSearch(query: String, count: Int): Result<List<SearchResult>> = runCatching {
        val req = Request.Builder()
            .url("https://www.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&count=$count")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MLX/0.1")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val html = resp.body?.string() ?: throw java.io.IOException("空响应")
            parseBingHtml(html, count)
        }
    }

    private fun parseBingHtml(html: String, count: Int): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        // <li class="b_algo"> 块内提取 <h2><a href="..">title</a></h2> 与 <p>snippet</p>
        val blockRe = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        for (m in blockRe.findAll(html)) {
            if (out.size >= count) break
            val block = m.groupValues[1]
            val href = Regex("<a[^>]+href=\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: continue
            val title = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let { stripHtml(it) } ?: continue
            val snippet = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
            out += SearchResult(title, href, snippet)
        }
        return out
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()

    private fun tavilySearch(query: String, apiKey: String?, count: Int): Result<List<SearchResult>> {
        if (apiKey.isNullOrBlank()) return Result.failure(java.io.IOException("未配置 Tavily API Key（设置 > 网络）"))
        return runCatching {
            val body = com.mlx.app.core.common.MiniJson.stringify(
                mapOf("api_key" to apiKey, "query" to query, "max_results" to count)
            )
            val req = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val obj = com.mlx.app.core.common.MiniJson.toMap(
                    com.mlx.app.core.common.MiniJson.parse(resp.body?.string() ?: "{}")
                )
                (obj["results"] as? List<*>)?.mapNotNull { r ->
                    val m = r as? Map<String, Any?> ?: return@mapNotNull null
                    SearchResult(
                        title = (m["title"] as? String) ?: "",
                        url = (m["url"] as? String) ?: "",
                        snippet = (m["content"] as? String) ?: "",
                    )
                } ?: emptyList()
            }
        }
    }

}

/** web_search 工具：引擎执行时从配置取后端与 Key */
class WebSearchTool(
    private val backendProvider: suspend () -> WebSearch.Backend,
    private val apiKeyProvider: suspend () -> String?,
) : ToolSpec {
    override val name = "web_search"
    override val description =
        "联网搜索（当前后端由设置页配置：Bing 无需 Key / Tavily 需 Key）。query 为搜索词，count 为结果条数（默认 5，最多 10）。"
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf("type" to "string"),
            "count" to mapOf("type" to "integer"),
        ),
        "required" to listOf("query"),
    )

    override suspend fun execute(args: Map<String, Any?>, saf: SafRepo, ctx: ToolContext): ToolResult {
        val query = (args["query"] as? String)?.trim()
            ?: return ToolResult(false, "缺少参数 query")
        val count = ((args["count"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
        val backend = backendProvider()
        return WebSearch.search(backend, query, apiKeyProvider(), count).fold(
            onSuccess = { results ->
                if (results.isEmpty()) ToolResult(true, "（未找到结果）")
                else ToolResult(true, results.take(count).joinToString("\n\n") {
                    "• ${it.title}\n  ${it.url}\n  ${it.snippet.take(200)}"
                })
            },
            onFailure = { ToolResult(false, "搜索失败: ${it.message}") },
        )
    }
}
