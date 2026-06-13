package models

data class GenerationConfig(
    val temperature: Double = 0.0,
    val topP: Double = 1.0,
    val maxTokens: Int = 4096,
    val stop: List<String>? = null,
    val user: String? = null
)