import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepSeekClient(private val apiKey: String) {

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)   // главное для DeepSeek
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json".toMediaType()

    fun ask(prompt: String): String {

        val requestBody = JSONObject()
            .put("model", "deepseek-v4-flash")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )
            )

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                error("Ошибка API: ${response.code}")
            }
            val json = JSONObject(response.body?.string())

            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }
}

fun main() {

    val apiKey =
        System.getenv("DEEPSEEK_API_KEY")
            ?: error("API key not found")

    val llm = DeepSeekClient(apiKey)

    val answer = llm.ask(
        "Объясни что такое Kotlin"
    )

    println(answer)
}