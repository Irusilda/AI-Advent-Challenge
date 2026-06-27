package models

data class DeepSeekResponse(
    val content: String,
    val totalTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val elapsedMs: Long,
    val costUsd: Double,
    val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    val id: String,
    val type: String,
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String,
    val arguments: String
)
