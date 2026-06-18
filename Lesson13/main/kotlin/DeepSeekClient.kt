import models.DeepSeekResponse
import models.GenerationConfig
import models.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepSeekClient(private val apiKey: String, val contextLimit: Int = 1000000) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    val costPer1kPromptTokens = 0.00014
    private val costPer1kCompletionTokens = 0.00028

    fun ask(messages: List<Message>, config: GenerationConfig = GenerationConfig()): DeepSeekResponse {
        val startTime = System.currentTimeMillis()

        val messagesArray = JSONArray().apply {
            messages.forEach {
                put(JSONObject()
                    .put("role", it.role)
                    .put("content", it.content))
            }
        }

        val requestBody = JSONObject()
            .put("model", "deepseek-v4-flash")
            .put("messages", messagesArray)
            .put("temperature", config.temperature)
            .put("top_p", config.topP)
            .put("max_tokens", config.maxTokens)

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Ошибка API: ${response.code} - ${response.message}"
            }

            val json = JSONObject(response.body!!.string())

            val answerText = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val usage = json.getJSONObject("usage")
            val promptTokens = usage.getInt("prompt_tokens")
            val completionTokens = usage.getInt("completion_tokens")
            val totalTokens = usage.getInt("total_tokens")

            val elapsedMs = System.currentTimeMillis() - startTime
            val costUsd = calculateCost(promptTokens, completionTokens)

            return DeepSeekResponse(
                answerText,
                totalTokens,
                promptTokens,
                completionTokens,
                elapsedMs,
                costUsd
            )
        }
    }

    private fun calculateCost(promptTokens: Int, completionTokens: Int): Double {
        return (promptTokens / 1000.0) * costPer1kPromptTokens +
                (completionTokens / 1000.0) * costPer1kCompletionTokens
    }
}