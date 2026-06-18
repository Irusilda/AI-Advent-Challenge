package models

data class DeepSeekResponse(
    val content: String,
    val totalTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val elapsedMs: Long,
    val costUsd: Double
)