import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GenerationConfig(
    val temperature: Double = 0.0,
    val topP: Double = 1.0,
    val maxTokens: Int = 20000,
    val stop: List<String> = emptyList()
)

class DeepSeekClient(private val apiKey: String) {

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json".toMediaType()

    fun ask(
        prompt: String,
        config: GenerationConfig = GenerationConfig()
    ): String {

        val requestBody = JSONObject()
            .put("model", "deepseek-chat")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )
            )
            .put("temperature", config.temperature)
            .put("top_p", config.topP)
            .put("max_tokens", config.maxTokens)
            .put("stop", JSONArray(config.stop))

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                error("Ошибка API: ${response.code}")
            }

            val json = JSONObject(response.body!!.string())

            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }
}

fun main() {
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("API key not found")

    val llm = DeepSeekClient(apiKey)
    val prompt = "Напиши поздравление с днем рождения коллеге\n"

    println(llm.ask(prompt))
}