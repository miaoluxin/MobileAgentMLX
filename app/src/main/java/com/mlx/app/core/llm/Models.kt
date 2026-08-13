package com.mlx.app.core.llm

/** API 消息（与 DeepSeek/OpenAI 兼容格式对应的纯数据模型） */
data class ApiMessage(
    val role: String,                       // system | user | assistant | tool
    val content: String? = null,
    val toolCallId: String? = null,         // role = tool 时使用
    val toolCalls: List<ApiToolCall>? = null, // role = assistant 时使用
)

data class ApiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,              // 必须是合法 JSON 字符串
)

/** SSE 流式增量 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsFragment: String? = null,
)

data class ChatDelta(
    val reasoning: String? = null,
    val content: String? = null,
    val toolCalls: List<ToolCallDelta>? = null,
    val finishReason: String? = null,
)

/** 用量与缓存命中（DeepSeek 返回 prompt_cache_hit_tokens） */
data class Usage(
    val promptTokens: Long,
    val completionTokens: Long,
    val cacheHitTokens: Long,
    val cacheMissTokens: Long,
) {
    val totalPromptTokens: Long get() = cacheHitTokens + cacheMissTokens
    fun cacheHitRate(): Double {
        val total = totalPromptTokens
        return if (total == 0L) 0.0 else cacheHitTokens.toDouble() / total
    }
}

/** 账户余额（对应 DeepSeek /user/balance 响应） */
data class BalanceInfo(
    val currency: String,
    val total: Double,
    val granted: Double,
    val toppedUp: Double,
    val available: Boolean = true,
    val infos: List<BalanceInfo> = emptyList(),
)

sealed interface StreamEvent {
    data class Delta(val delta: ChatDelta) : StreamEvent
    data class UsageEvent(val usage: Usage) : StreamEvent
    data class Error(val message: String) : StreamEvent
    object Done : StreamEvent
}
