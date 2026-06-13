import models.CompareResult
import models.DeepSeekResponse
import models.Message

class ChatAgent(val llm: DeepSeekClient) {

    private val messages = mutableListOf<Message>()
    private val fullHistory = mutableListOf<Message>()

    private val memory = ConversationMemory(llm)

    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalCost = 0.0

    private var fullPromptTokens = 0
    private var fullCompletionTokens = 0
    private var fullCost = 0.0

    init {
        val system = Message(
            "system",
            "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке."
        )
        messages.add(system)
        fullHistory.add(system)
    }

    fun chat(userMessage: String): DeepSeekResponse {
        val userMsg = Message("user", userMessage)

        val fullContextForStats = fullHistory.toList() + userMsg

        messages.add(userMsg)
        fullHistory.add(userMsg)

        memory.compressIfNeeded(messages)
        val context = memory.buildContext(messages)
        val response = llm.ask(context)

        val assistantMsg = Message("assistant", response.content)
        messages.add(assistantMsg)
        fullHistory.add(assistantMsg)

        totalPromptTokens += response.promptTokens
        totalCompletionTokens += response.completionTokens
        totalCost += response.costUsd

        val fullResponse = llm.ask(fullContextForStats)

        fullPromptTokens += fullResponse.promptTokens
        fullCompletionTokens += fullResponse.completionTokens
        fullCost += fullResponse.costUsd

        return response
    }

    fun compare(question: String): CompareResult {
        val userMsg = Message("user", question)

        val fullContext = fullHistory + userMsg
        val fullResponse = llm.ask(fullContext)

        val tempMemory = ConversationMemory(llm)   // изолированная память
        val tempMessages = messages.toMutableList()
        tempMessages.add(userMsg)

        tempMemory.compressIfNeeded(tempMessages)
        val compressedContext = tempMemory.buildContext(tempMessages)
        val compressedResponse = llm.ask(compressedContext)

        fullPromptTokens += fullResponse.promptTokens
        fullCompletionTokens += fullResponse.completionTokens
        fullCost += fullResponse.costUsd

        totalPromptTokens += compressedResponse.promptTokens
        totalCompletionTokens += compressedResponse.completionTokens
        totalCost += compressedResponse.costUsd

        return CompareResult(
            fullResponse = fullResponse,
            compressedResponse = compressedResponse
        )
    }

    fun printStats() {
        println("------ СТАТИСТИКА ------")
        println()
        println("🟢 СЖАТАЯ ИСТОРИЯ (реально используется в чате):")
        println("   Prompt tokens: $totalPromptTokens")
        println("   Completion tokens: $totalCompletionTokens")
        println("   Cost: $${"%.6f".format(totalCost)}")
        println()
        println("🔵 ПОЛНАЯ ИСТОРИЯ (без сжатия, для сравнения):")
        println("   Prompt tokens: $fullPromptTokens")
        println("   Completion tokens: $fullCompletionTokens")
        println("   Cost: $${"%.6f".format(fullCost)}")

        if (fullPromptTokens > 0) {
            val saved = 100.0 - (totalPromptTokens * 100.0 / fullPromptTokens)
            println()
            println("📉 Экономия токенов (prompt): ${"%.2f".format(saved)}%")
        }
        println("------------------------")
    }

    fun clearHistory() {
        messages.clear()
        fullHistory.clear()

        val system = Message(
            "system",
            "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке."
        )
        messages.add(system)
        fullHistory.add(system)

        memory.clear()

        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalCost = 0.0

        fullPromptTokens = 0
        fullCompletionTokens = 0
        fullCost = 0.0
    }

    fun saveHistory(filePath: String) {
        val jsonArray = org.json.JSONArray()
        messages.forEach {
            jsonArray.put(
                org.json.JSONObject()
                    .put("role", it.role)
                    .put("content", it.content)
            )
        }
        java.io.File(filePath).writeText(jsonArray.toString(2))
    }

    fun loadHistory(filePath: String) {
        val file = java.io.File(filePath)
        if (!file.exists()) return

        messages.clear()
        fullHistory.clear()

        val jsonArray = org.json.JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val msg = Message(
                obj.getString("role"),
                obj.getString("content")
            )
            messages.add(msg)
            fullHistory.add(msg)
        }
    }
}