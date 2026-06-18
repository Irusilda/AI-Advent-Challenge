import contextstrategy.ContextStrategy
import contextstrategy.impl.BranchingStrategy
import contextstrategy.impl.SlidingWindowStrategy
import contextstrategy.impl.StickyFactsStrategy
import models.CompareResult
import models.DeepSeekResponse
import models.Message
import models.MemoryItem
import models.MemoryType
import models.UserProfile

class ChatAgent(val llm: DeepSeekClient) {

    val memoryManager = MemoryManager()
    private val fullHistory = mutableListOf<Message>()

    var userProfile: UserProfile = UserProfile()

    private var currentStrategy: ContextStrategy = SlidingWindowStrategy(
        windowSize = 4,
        systemMessage = Message("system", "Ты — полезный ассистент. Отвечай на русском языке.")
    )

    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalCost = 0.0

    private var fullPromptTokens = 0
    private var fullCompletionTokens = 0
    private var fullCost = 0.0

    init {
        val system = Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
        fullHistory.add(system)
        currentStrategy.loadHistory(listOf(system))
    }

    private val baseSystemMessage = "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке."

    private fun buildPersonalizedSystemMessage(): String {
        return "$baseSystemMessage ${userProfile.toInstruction()}"
    }

    fun switchStrategy(strategyType: String) {
        val systemMsg = Message("system", buildPersonalizedSystemMessage())
        val currentMessages = currentStrategy.getHistory().filter { it.role != "system" }
        currentStrategy = when (strategyType.lowercase()) {
            "sliding" -> SlidingWindowStrategy(windowSize = 4, systemMessage = systemMsg)
            "sticky" -> StickyFactsStrategy(llm, keepLastMessages = 6, systemMessage = systemMsg)
            "branching" -> BranchingStrategy(systemMessage = systemMsg)
            else -> throw IllegalArgumentException("Unknown strategy: $strategyType")
        }
        currentStrategy.loadHistory(listOf(systemMsg) + currentMessages)
        println("Переключено на стратегию: ${currentStrategy.javaClass.simpleName}")
    }

    fun setStrategy(strategy: ContextStrategy) {
        val currentMessages = currentStrategy.getHistory().filter { it.role != "system" }
        currentStrategy = strategy
        val systemMsg = Message("system", buildPersonalizedSystemMessage())
        strategy.loadHistory(listOf(systemMsg) + currentMessages)
    }

    fun getCurrentStrategyName(): String = currentStrategy.getName()

    fun getBranchingStrategyOrNull(): BranchingStrategy? = currentStrategy as? BranchingStrategy

    fun chat(userMessage: String): DeepSeekResponse {
        val userMsg = Message("user", userMessage)
        memoryManager.processUserMessage(userMsg)
        fullHistory.add(userMsg)
        currentStrategy.addUserMessage(userMsg)

        val context = memoryManager.buildContext(
            systemMessage = Message("system", buildPersonalizedSystemMessage()),
            recentMessages = currentStrategy.buildContext().filter { it.role != "system" }
        )
        val response = llm.ask(context)

        val assistantMsg = Message("assistant", response.content)
        memoryManager.processAssistantResponse(assistantMsg)
        currentStrategy.addAssistantMessage(assistantMsg)
        fullHistory.add(assistantMsg)

        totalPromptTokens += response.promptTokens
        totalCompletionTokens += response.completionTokens
        totalCost += response.costUsd

        val fullContext = fullHistory.toList()
        val fullResponse = llm.ask(fullContext)
        fullPromptTokens += fullResponse.promptTokens
        fullCompletionTokens += fullResponse.completionTokens
        fullCost += fullResponse.costUsd

        return response
    }

    fun compare(question: String): CompareResult {
        val userMsg = Message("user", question)

        val fullContext = fullHistory + userMsg
        val fullResponse = llm.ask(fullContext)

        val compressedContext = memoryManager.buildContext(
            systemMessage = Message("system", buildPersonalizedSystemMessage()),
            recentMessages = currentStrategy.getHistory().filter { it.role != "system" } + userMsg
        )
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
        println("------ СТАТИСТИКА (стратегия: ${currentStrategy.getName()}) ------")
        println()
        println("🟢 ТЕКУЩАЯ СТРАТЕГИЯ:")
        println("   Prompt tokens: $totalPromptTokens")
        println("   Completion tokens: $totalCompletionTokens")
        println("   Cost: ${"%.6f".format(totalCost)}")
        println()
        println("🔵 ПОЛНАЯ ИСТОРИЯ (без сжатия):")
        println("   Prompt tokens: $fullPromptTokens")
        println("   Completion tokens: $fullCompletionTokens")
        println("   Cost: ${"%.6f".format(fullCost)}")

        if (fullPromptTokens > 0) {
            val saved = 100.0 - (totalPromptTokens * 100.0 / fullPromptTokens)
            println()
            println("📉 Экономия токенов (prompt): ${"%.2f".format(saved)}%")
        }
        println()
        println("🧠 ПАМЯТЬ:")
        println(memoryManager.getStats())
        println()
        println("👤 ТЕКУЩИЙ ПРОФИЛЬ:")
        println(userProfile.toInstruction())
        println("------------------------")
    }

    fun clearHistory() {
        fullHistory.clear()
        val system = Message("system", buildPersonalizedSystemMessage())
        fullHistory.add(system)
        currentStrategy.clear()
        currentStrategy.loadHistory(listOf(system))
        memoryManager.clearAll()

        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalCost = 0.0
        fullPromptTokens = 0
        fullCompletionTokens = 0
        fullCost = 0.0
    }

    fun saveHistory(filePath: String) {
        val jsonObject = org.json.JSONObject()
        val messagesArray = org.json.JSONArray()
        currentStrategy.getHistory().forEach {
            messagesArray.put(
                org.json.JSONObject()
                    .put("role", it.role)
                    .put("content", it.content)
            )
        }
        jsonObject.put("strategy_history", messagesArray)

        val memoryArray = org.json.JSONArray()
        listOf(MemoryType.SHORT_TERM, MemoryType.WORKING, MemoryType.LONG_TERM).forEach { type ->
            memoryManager.getMemoryByType(type).forEach { item ->
                memoryArray.put(
                    org.json.JSONObject()
                        .put("type", item.type.name)
                        .put("content", item.content)
                        .put("timestamp", item.timestamp)
                        .put("source", item.source)
                        .put("importance", item.importance)
                )
            }
        }
        jsonObject.put("memory", memoryArray)
        jsonObject.put("profile_name", userProfile.name)
        jsonObject.put("profile_style", userProfile.style)
        jsonObject.put("profile_format", userProfile.format)
        jsonObject.put("profile_constraints", org.json.JSONArray(userProfile.constraints))
        java.io.File(filePath).writeText(jsonObject.toString(2))
    }

    fun loadHistory(filePath: String) {
        val file = java.io.File(filePath)
        if (!file.exists()) return

        val text = file.readText().trim()
        val isOldFormat = text.startsWith("[")

        if (isOldFormat) {
            val messagesArray = org.json.JSONArray(text)
            loadMessagesFromArray(messagesArray)
            memoryManager.clearAll()
        } else {
            val jsonObject = org.json.JSONObject(text)
            jsonObject.optJSONArray("strategy_history")?.let { loadMessagesFromArray(it) }
            memoryManager.clearAll()
            jsonObject.optJSONArray("memory")?.let { loadMemoryFromArray(it) }

            val name = jsonObject.optString("profile_name", "Пользователь")
            val style = jsonObject.optString("profile_style", "нейтральный")
            val format = jsonObject.optString("profile_format", "обычный")
            val constraintsArray = jsonObject.optJSONArray("profile_constraints")
            val constraints = if (constraintsArray != null) {
                (0 until constraintsArray.length()).map { constraintsArray.getString(it) }
            } else emptyList()
            userProfile = UserProfile(name, style, format, constraints)
        }
    }

    private fun loadMessagesFromArray(array: org.json.JSONArray) {
        val loadedMessages = mutableListOf<Message>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            loadedMessages.add(Message(obj.getString("role"), obj.getString("content")))
        }
        if (loadedMessages.isNotEmpty()) {
            currentStrategy.loadHistory(loadedMessages)
            fullHistory.clear()
            fullHistory.addAll(loadedMessages)
        }
    }

    private fun loadMemoryFromArray(array: org.json.JSONArray) {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type = MemoryType.valueOf(obj.getString("type"))
            val item = MemoryItem(
                type = type,
                content = obj.getString("content"),
                timestamp = obj.getLong("timestamp"),
                source = obj.optString("source", ""),
                importance = obj.optDouble("importance", 1.0)
            )
            memoryManager.addToMemory(item)
        }
    }
}