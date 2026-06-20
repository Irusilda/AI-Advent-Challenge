package models

data class CompareResult(
    val fullResponse: DeepSeekResponse,
    val compressedResponse: DeepSeekResponse
)
