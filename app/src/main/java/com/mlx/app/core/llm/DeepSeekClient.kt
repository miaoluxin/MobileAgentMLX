package com.mlx.app.core.llm

import com.mlx.app.core.common.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek（OpenAI 兼容）流式客户端。
 * 解析：SSE `data:` 行；三类 delta（reasoning_content / content / tool_calls）+ usage。
 */
class DeepSeekClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {

    companion object {
        /**
         * 默认 HTTP 客户端（internal 供单元测试断言超时配置）。
         * readTimeout=120s（修复链路：原 readTimeout(0) 无限等待 → OkHttp 阻塞读时
         * call.cancel() 失效/明显滞后 —— 停止按钮"点了没反应"的直接根因；
         * 先改 300s 杜绝永久挂死，本批再压到 120s —— readTimeout 只约束"连续无数据"时长，
         * 120s 无数据基本等于连接死亡，缩短停止后最坏滞后窗口；不用 30-60s：
         * max effort 长思考停顿可能 >60s 无字节，误杀会触发重试重复计费）
         */
        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 流式对话（对齐 PC 端请求语义）：
     * - tools 标准参数（结构化工具定义，对齐 openai.go chatTool）
     * - stream_options.include_usage（显式请求 usage 统计）
     * - max_tokens=32768（官方 DeepSeek + 思考非关闭，对齐 provider.go DefaultReasoningOutputTokens）
     * - thinking enabled/disabled 显式、temperature 不传（模型默认采样）
     * - 断线重连：最多 5 次重试（对齐 agent.go maxStreamRecoveries），指数退避
     */
    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ApiMessage>,
        reasoningMode: String = "auto",
        tools: List<com.mlx.app.core.tools.ToolSpec> = emptyList(),
    ): Flow<StreamEvent> = callbackFlow {
        var call: Call? = null
        var currentRequest: Request? = null
        var attempt = 0
        val maxAttempts = 6 // 1 次初始 + 5 次重试（PC maxStreamRecoveries）

        lateinit var enqueueOnce: () -> Unit

        fun retryLater() {
            if (attempt < maxAttempts - 1) {
                attempt++
                val backoff = 1000L * (1L shl (attempt - 1)) // 1s/2s/4s/8s/16s
                // 八批：改用 producerScope（callbackFlow 作用域）—— 流取消/关闭时重试协程随之取消，不再 GlobalScope 泄漏
                launch {
                    delay(backoff)
                    val req = currentRequest
                    if (req == null) return@launch
                    if (call?.isCanceled() == true) {
                        // 流已被取消（用户停止）：显式关闭通道，防 callbackFlow 悬挂
                        close()
                        return@launch
                    }
                    try {
                        enqueueOnce()
                    } catch (ex: Exception) {
                        trySend(StreamEvent.Error(ex.message ?: "重试失败"))
                        close()
                    }
                }
            } else {
                trySend(StreamEvent.Error("网络错误（已重试 ${maxAttempts - 1} 次）"))
                close()
            }
        }

        enqueueOnce = {
            val c = http.newCall(currentRequest!!)
            call = c
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    retryLater()
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(300) ?: ""
                            trySend(StreamEvent.Error("HTTP ${response.code} $err"))
                            close()
                            return
                        }
                        val source = response.body?.source()
                        if (source == null) {
                            close()
                            return
                        }
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val ev = parseChunk(payload)
                            if (ev != null) trySend(ev)
                        }
                        close()
                    } catch (e: Exception) {
                        // 流解析异常 → 同样走重试（若还有机会）
                        retryLater()
                    }
                }
            })
        }

        try {
            val body = buildChatRequest(model, messages, reasoningMode, tools)
            currentRequest = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            enqueueOnce()
        } catch (e: Exception) {
            trySend(StreamEvent.Error(e.message ?: "请求失败"))
            close()
        }
        awaitClose { call?.cancel() }
    }

    /**
     * 账户余额：GET {base_url去掉/v1}/user/balance（对应 PC 端 BalanceURL）。
     * DeepSeek 余额端点在 API 根路径（非 /v1 下）。
     */
    suspend fun balance(apiKey: String, baseUrl: String): Result<BalanceInfo> {
        return try {
            withContext(Dispatchers.IO) {
                val root = baseUrl.trimEnd('/').removeSuffix("/v1")
                val request = Request.Builder()
                    .url("$root/user/balance")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"))
                    }
                    val obj = MiniJson.toMap(MiniJson.parse(resp.body?.string() ?: "{}"))
                    val infos = (obj["balance_infos"] as? List<*>)?.mapNotNull { raw ->
                        val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                        BalanceInfo(
                            currency = (m["currency"] as? String) ?: "",
                            total = (m["total_balance"] as? String)?.toDoubleOrNull() ?: 0.0,
                            granted = (m["granted_balance"] as? String)?.toDoubleOrNull() ?: 0.0,
                            toppedUp = (m["topped_up_balance"] as? String)?.toDoubleOrNull() ?: 0.0,
                        )
                    } ?: emptyList()
                    val available = MiniJson.optBool(obj, "is_available")
                    Result.success(BalanceInfo(currency = "", total = 0.0, granted = 0.0, toppedUp = 0.0, available = available, infos = infos))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 探测可用模型列表：GET {base_url}/models（对应 PC 端 FetchModels；IO 线程执行） */
    suspend fun listModels(apiKey: String, baseUrl: String): Result<List<String>> {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"))
                    }
                    val obj = MiniJson.toMap(MiniJson.parse(resp.body?.string() ?: "{}"))
                    val data = obj["data"] as? List<*> ?: emptyList<Any?>()
                    val ids = data.mapNotNull { (it as? Map<String, Any?>)?.get("id") as? String }
                    Result.success(ids)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 测试连接：GET /models 鉴权探测（与 PC 端 FetchModels 一致）。
     * 只验证密钥有效性，不做最小 chat 请求 —— 避免 V4 对 max_tokens=1 等约束的误报。
     */
    suspend fun testConnection(apiKey: String, baseUrl: String, model: String): Result<String> {
        return listModels(apiKey, baseUrl).fold(
            onSuccess = { ids -> Result.success(ids.size.toString()) },
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * 组装 chat 请求体（internal 便于单元测试；对齐 PC openai.go chatRequest）：
     * - tools：标准结构化工具定义（PC 传 tools 数组；Android 之前仅文本描述 —— 直接根因）
     * - stream_options.include_usage：显式请求 usage（成本/命中率统计）
     * - max_tokens=32768：官方 DeepSeek + 思考非关闭（PC DefaultReasoningOutputTokens=32*1024）；
     *   关闭思考不设（PC openai.go:230 特判一致）
     * - thinking：思考开显式 {type:"enabled"}（PC openai.go:838），关 {type:"disabled"}
     * - reasoning_effort="max"（深度思考）；temperature 不传（模型默认采样）
     */
    internal fun buildChatRequest(
        model: String,
        messages: List<ApiMessage>,
        reasoningMode: String = "auto",
        tools: List<com.mlx.app.core.tools.ToolSpec> = emptyList(),
        temperature: Double? = null, // 默认不传（对齐 PC：0 → omit；模型默认采样）
    ): String {
        val msgs = messages.map { m ->
            val map = LinkedHashMap<String, Any?>()
            map["role"] = m.role
            m.content?.let { map["content"] = it }
            m.toolCallId?.let { map["tool_call_id"] = it }
            m.toolCalls?.let { calls ->
                map["tool_calls"] = calls.map {
                    mapOf(
                        "id" to it.id,
                        "type" to "function",
                        "function" to mapOf("name" to it.name, "arguments" to it.argumentsJson),
                    )
                }
            }
            map
        }
        val req = LinkedHashMap<String, Any?>()
        req["model"] = model
        req["messages"] = msgs
        req["stream"] = true
        // A2：显式请求 usage 统计（DeepSeek 流式默认不带）
        req["stream_options"] = mapOf("include_usage" to true)
        // A1：结构化工具定义（标准 API 参数，对齐 PC chatTool）
        // 十二批修正：按名称排序 —— 与 prefix 内工具描述排序一致（DeepSeek 自动缓存对请求字节匹配，
        // tools 数组顺序漂移会截断缓存命中；MCP 每回合"先卸后注"会改变插入顺序）
        if (tools.isNotEmpty()) {
            req["tools"] = tools.sortedBy { it.name }.map { t ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to t.name,
                        "description" to t.description,
                        "parameters" to (MiniJson.parse(MiniJson.stringify(t.parameters)) ?: emptyMap<String, Any?>()),
                    ),
                )
            }
        }
        // A5：temperature 默认不传（对齐 PC：config 默认 0 → omitempty 不传，用模型默认采样）
        temperature?.let { req["temperature"] = it }
        val isV4 = model.contains("deepseek-v4")
        if (isV4) {
            when (reasoningMode) {
                "off" -> {
                    req["thinking"] = mapOf("type" to "disabled")
                    // PC：关闭思考不设输出预算（openai.go:230 特判）
                }
                else -> {
                    // A4：思考开启显式 enabled
                    req["thinking"] = mapOf("type" to "enabled")
                    // A3：官方 DeepSeek + 思考非关闭 → 32K 输出预算（PC provider.go:236）
                    req["max_tokens"] = 32768
                    if (reasoningMode == "max") req["reasoning_effort"] = "max"
                }
            }
        }
        return MiniJson.stringify(req)
    }

    private fun parseChunk(payload: String): StreamEvent? {
        val obj = MiniJson.parse(payload) as? Map<String, Any?> ?: return null
        // usage 出现在流末尾 chunk
        obj["usage"]?.let { usage ->
            val u = usage as? Map<String, Any?> ?: return@let
            return StreamEvent.UsageEvent(
                Usage(
                    promptTokens = MiniJson.optLong(u, "prompt_tokens"),
                    completionTokens = MiniJson.optLong(u, "completion_tokens"),
                    cacheHitTokens = MiniJson.optLong(u, "prompt_cache_hit_tokens"),
                    cacheMissTokens = MiniJson.optLong(u, "prompt_cache_miss_tokens"),
                )
            )
        }
        val choices = obj["choices"] as? List<*> ?: return null
        val first = choices.firstOrNull() as? Map<String, Any?> ?: return null
        val delta = first["delta"] as? Map<String, Any?> ?: return null
        val reasoning = delta["reasoning_content"] as? String
        val content = delta["content"] as? String
        val finishReason = first["finish_reason"] as? String
        val toolDeltas = (delta["tool_calls"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> }
            ?.map { tc ->
                val fn = tc["function"] as? Map<String, Any?> ?: emptyMap()
                ToolCallDelta(
                    index = MiniJson.optInt(tc, "index"),
                    id = tc["id"] as? String,
                    name = fn["name"] as? String,
                    argumentsFragment = fn["arguments"] as? String,
                )
            }
        return StreamEvent.Delta(ChatDelta(reasoning, content, toolDeltas, finishReason))
    }
}
