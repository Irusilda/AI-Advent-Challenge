import contextstrategy.ContextStrategy
import contextstrategy.impl.BranchingStrategy
import contextstrategy.impl.SlidingWindowStrategy
import contextstrategy.impl.StickyFactsStrategy
import models.CompareResult
import models.DeepSeekResponse
import models.Message

class ChatAgent(val llm: DeepSeekClient) {

    private val fullHistory = mutableListOf<Message>()   // полная история для статистики/сравнения

    private var currentStrategy: ContextStrategy = SlidingWindowStrategy(
        windowSize = 4,
        systemMessage = Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
    )

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
        fullHistory.add(system)
        currentStrategy.loadHistory(listOf(system))
    }

    fun setStrategy(strategy: ContextStrategy) {
        val currentMessages = currentStrategy.getHistory().filter { it.role != "system" }
        currentStrategy = strategy
        val systemMsg = Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
        strategy.loadHistory(listOf(systemMsg) + currentMessages)
    }

    fun getCurrentStrategyName(): String = currentStrategy.getName()

    fun getBranchingStrategyOrNull(): BranchingStrategy? = currentStrategy as? BranchingStrategy

    fun chat(userMessage: String): DeepSeekResponse {
        val userMsg = Message("user", userMessage)

        fullHistory.add(userMsg)

        currentStrategy.addUserMessage(userMsg)

        val context = currentStrategy.buildContext()
        val response = llm.ask(context)

        val assistantMsg = Message("assistant", response.content)
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

        val tempStrategy = when (currentStrategy) {
            is SlidingWindowStrategy -> {
                val sys = currentStrategy.getHistory().firstOrNull() ?: Message("system", "")
                SlidingWindowStrategy(windowSize = (currentStrategy as SlidingWindowStrategy).let { 10 }, systemMessage = sys)
            }
            is StickyFactsStrategy -> {
                StickyFactsStrategy(llm, keepLastMessages = 6, systemMessage = Message("system", ""))
            }
            is BranchingStrategy -> {
                BranchingStrategy(Message("system", ""))
            }
            else -> error("Unknown strategy")
        }

        tempStrategy.loadHistory(currentStrategy.getHistory())
        tempStrategy.addUserMessage(userMsg)
        val compressedContext = tempStrategy.buildContext()
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
        println("   Cost: $${"%.6f".format(totalCost)}")
        println()
        println("🔵 ПОЛНАЯ ИСТОРИЯ (без сжатия):")
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
        fullHistory.clear()
        val system = Message(
            "system",
            "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке."
        )
        fullHistory.add(system)
        currentStrategy.clear()
        currentStrategy.loadHistory(listOf(system))

        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalCost = 0.0
        fullPromptTokens = 0
        fullCompletionTokens = 0
        fullCost = 0.0
    }

    fun saveHistory(filePath: String) {
        val jsonArray = org.json.JSONArray()
        currentStrategy.getHistory().forEach {
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

        val jsonArray = org.json.JSONArray(file.readText())
        val loadedMessages = mutableListOf<Message>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            loadedMessages.add(Message(obj.getString("role"), obj.getString("content")))
        }
        if (loadedMessages.isNotEmpty()) {
            currentStrategy.loadHistory(loadedMessages)
            fullHistory.clear()
            fullHistory.addAll(loadedMessages)
        }
    }
}