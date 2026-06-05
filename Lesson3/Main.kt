import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GenerationConfig(
    val temperature: Double = 1.5,
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
    val problem = "Ответь на вопрос кратко. Вопрос: Сколько будет 2 + 2 × 2?\n"

    println("1: Прямой ответ\n")
    println(llm.ask(problem))

    println("2: Решай пошагово\n")
    println(llm.ask("Решай пошагово.\n$problem"))

    println("3: Сгенерировать промпт\n")

    val promptForPrompt = """
        Твоя задача — составить промпт для другой языковой модели. 
        Не решай исходную задачу сама. Напиши только промпт, 
        который заставит другую модель решить задачу.
        Исходная задача:
        $problem
        """.trimIndent()
    val generatedPrompt = llm.ask(promptForPrompt)

    println("Сгенерированный промпт:\n$generatedPrompt\n")
    println("Ответ по сгенерированному промпту:\n${llm.ask(generatedPrompt)}")

    println("4: Группа экспертов\n")
    val expertPrompt = """
        Ты — группа из трёх экспертов: Аналитик, Инженер и Критик. 
        Решите задачу, каждый со своей точки зрения.
        
        Задача:
        $problem
        
        Формат:
        [Аналитик]: <анализ, вывод>
        [Инженер]: <расчёт, вывод>
        [Критик]: <критика допущений, вывод>
    """.trimIndent()

    println(llm.ask(expertPrompt))
}