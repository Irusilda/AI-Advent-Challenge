import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Message(val role: String, val content: String)

data class GenerationConfig(
    val temperature: Double = 0.0,
    val topP: Double = 1.0,
    val maxTokens: Int = 4096,
    val stop: List<String>? = null,
    val user: String? = null
)

data class DeepSeekResponse(
    val content: String,
    val totalTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val elapsedMs: Long,
    val costUsd: Double
)

class DeepSeekClient(private val apiKey: String, val contextLimit: Int = 1000000) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()
    private val costPer1kPromptTokens = 0.00014
    private val costPer1kCompletionTokens = 0.00028

    fun ask(messages: List<Message>, config: GenerationConfig = GenerationConfig()): DeepSeekResponse {
        val startTime = System.currentTimeMillis()

        val messagesArray = JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject()
                    .put("role", msg.role)
                    .put("content", msg.content))
            }
        }

        val requestBody = JSONObject().apply {
            put("model", "deepseek-v4-flash")
            put("messages", messagesArray)
            put("temperature", config.temperature)
            put("top_p", config.topP)
            put("max_tokens", config.maxTokens)
        }

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Ошибка API: ${response.code} - ${response.message}" }

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

            return DeepSeekResponse(answerText, totalTokens, promptTokens, completionTokens, elapsedMs, costUsd)
        }
    }

    private fun calculateCost(promptTokens: Int, completionTokens: Int): Double {
        val promptCost = (promptTokens / 1000.0) * costPer1kPromptTokens
        val completionCost = (completionTokens / 1000.0) * costPer1kCompletionTokens
        return promptCost + completionCost
    }
}

class ChatAgent(val llm: DeepSeekClient) {
    private val messages = mutableListOf<Message>()

    init {
        messages.add(Message("system", "Ты — полезный ассистент. Отвечай кратко, по существу, на русском языке. Если видишь литературный текст, не продолжай его, а просто сообщи, что получил текст, и жди дальнейших указаний."))
    }

    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalCost = 0.0

    fun chat(userMessage: String): DeepSeekResponse {
        messages.add(Message("user", userMessage))
        val response = llm.ask(messages)
        messages.add(Message("assistant", response.content))

        totalPromptTokens += response.promptTokens
        totalCompletionTokens += response.completionTokens
        totalCost += response.costUsd

        return response
    }

    fun clearHistory() {
        messages.clear()
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalCost = 0.0
    }

    fun printStats() {
        val totalTokens = totalPromptTokens + totalCompletionTokens
        println("------Статистика диалога-----")
        println("Всего сообщений: ${messages.size}")
        println("Суммарно промпт-токенов: $totalPromptTokens")
        println("Суммарно токенов ответов: $totalCompletionTokens")
        println("Общее количество токенов: $totalTokens")
        println("Общая стоимость: $${"%.6f".format(totalCost)}")
        println("Лимит контекста (из клиента): ${llm.contextLimit} токенов")
        println("-----------------------------")
    }

    fun contextWarning(): String? {
        val approxTokens = messages.sumOf { it.content.length / 4 }
        val limit = llm.contextLimit
        return when {
            approxTokens > limit -> "⚠ КОНТЕКСТ ПЕРЕПОЛНЕН! (≈\$approxTokens / \$limit токенов)"
            approxTokens > limit * 0.95 -> "⚠ КОНТЕКСТ ПОЧТИ ЗАПОЛНЕН! (≈$approxTokens / $limit токенов)"
            approxTokens > limit * 0.8  -> "⚠ Контекст заполнен на 80% (≈$approxTokens токенов)"
            else -> null
        }
    }

    fun saveHistory(filePath: String) {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            jsonArray.put(JSONObject()
                .put("role", msg.role)
                .put("content", msg.content))
        }
        File(filePath).writeText(jsonArray.toString(2))
    }

    fun loadHistory(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            messages.clear()
            val jsonArray = JSONArray(file.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                messages.add(Message(
                    role = obj.getString("role"),
                    content = obj.getString("content")
                ))
            }
        }
    }
}

fun main() {
    val apiKey = requireNotNull(System.getenv("DEEPSEEK_API_KEY")) {
        "DEEPSEEK_API_KEY не задана"
    }
    val agent = ChatAgent(DeepSeekClient(apiKey))
    val historyFile = "chat_history.json"
    agent.loadHistory(historyFile)
    if (File(historyFile).exists()) {
        println("(История загружена из $historyFile)")
    }

    println("Чат-агент запущен (лимит контекста ${agent.llm.contextLimit} токенов).")
    println("Команды: 'exit' — выход, 'clear' — очистить историю, 'stats' — показать статистику.")
    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: return

        when {
            input.equals("exit", ignoreCase = true) -> {
                agent.saveHistory(historyFile)
                return
            }
            input.equals("clear", ignoreCase = true) -> {
                agent.clearHistory()
                File(historyFile).delete()
                println("История очищена.")
            }
            input.equals("stats", ignoreCase = true) -> {
                agent.printStats()
                agent.contextWarning()?.let { println(it) }
            }
            input.startsWith("load ", ignoreCase = true) -> {
                val filename = input.removePrefix("load ").trim()
                val file = File(filename)
                if (!file.exists()) {
                    println("Файл не найден: $filename")
                } else {
                    try {
                        val text = file.readText()
                        println("Загружено ${text.length} символов из $filename. Отправляю агенту...")
                        val response = agent.chat(text)
                        println("Ассистент: ${response.content}")
                        println("... статистика ...")
                        agent.saveHistory(historyFile)
                    } catch (e: Exception) {
                        println("Ошибка при загрузке файла: ${e.message}")
                    }
                }
            }
            else -> {
                try {
                    val response = agent.chat(input)
                    println("Ассистент: ${response.content}")
                    println("┌─────────────────────────────────────────┐")
                    println("│ Токены запроса (вся история): ${response.promptTokens}  │")
                    println("│ Токены ответа: ${response.completionTokens}   │")
                    println("│ Стоимость запроса: $${"%.6f".format(response.costUsd)} │")
                    println("└─────────────────────────────────────────┘")

                    agent.contextWarning()?.let { println(it) }

                    agent.saveHistory(historyFile)
                } catch (e: Exception) {
                    println("Ошибка: ${e.message}")
                    if (e.message?.contains("400") == true || e.message?.contains("context") == true) {
                        println(">>> Контекст превысил лимит модели. Попробуйте очистить историю (команда 'clear').")
                    }
                }
            }
        }
    }
}