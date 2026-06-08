import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GenerationConfig(
    val temperature: Double = 1.0,
    val topP: Double = 1.0,
    val maxTokens: Int = 4096,
    val stop: List<String>? = null,
    val user: String? = null,
)

data class DeepSeekResponse(
    val content: String,
    val totalTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val elapsedMs: Long,
    val costUsd: Double
)

class DeepSeekClient(private val apiKey: String) {

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    private val costPer1kPromptTokens = 0.00014
    private val costPer1kCompletionTokens = 0.00028

    fun ask(prompt: String, config: GenerationConfig = GenerationConfig()): DeepSeekResponse {
        val startTime = System.currentTimeMillis()

        val messagesArray = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", prompt)
        )

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
            if (!response.isSuccessful) {
                error("Ошибка API: ${response.code} - ${response.message}")
            }

            val json = JSONObject(response.body!!.string())
            val choices = json.getJSONArray("choices")

            val answerText = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val usage = json.getJSONObject("usage")
            val promptTokens = usage.getInt("prompt_tokens")
            val completionTokens = usage.getInt("completion_tokens")
            val totalTokens = usage.getInt("total_tokens")

            val elapsedMs = System.currentTimeMillis() - startTime

            val costUsd = calculateCost(promptTokens, completionTokens)

            return DeepSeekResponse(answerText, totalTokens, promptTokens, completionTokens, elapsedMs, costUsd)
        }
    }

    private fun calculateCost(promptTokens: Int, completionTokens: Int): Double {
        val promptCost = (promptTokens / 1000.0) * costPer1kPromptTokens
        val completionCost = (completionTokens / 1000.0) * costPer1kCompletionTokens
        return promptCost + completionCost
    }
}

fun main() {
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("DEEPSEEK_API_KEY not set")
    val llm = DeepSeekClient(apiKey)

    val prompt = "Реши пример: (15×3)+(24÷4)−(7×2). Покажи пошаговое решение, а в конце напиши только итоговый ответ\n"

    println("Ответ:\n")

    val response = llm.ask(prompt)

    println(response.content)
    println("\nСтатистика:")
    println("Prompt токенов: ${response.promptTokens}")
    println("Completion токенов: ${response.completionTokens}")
    println("Всего токенов: ${response.totalTokens}")
    println("Время ответа: ${response.elapsedMs} мс")
    println("Стоимость: $${"%.6f".format(response.costUsd)} USD")
}