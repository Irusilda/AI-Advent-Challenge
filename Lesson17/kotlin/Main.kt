import models.*
import java.io.File
import kotlinx.coroutines.*

fun main() {
    val apiKey = requireNotNull(System.getenv("DEEPSEEK_API_KEY")) {
        "DEEPSEEK_API_KEY не задана"
    }
    val llmClient = DeepSeekClient(apiKey)

    val mcpClientManager = McpClientManager()
    mcpClientManager.connect()

    val agent = ChatAgent(llmClient, enableSwarm = true, mcpClientManager = mcpClientManager)
    val historyFile = "chat_history.json"
    agent.loadHistory(historyFile)
    if (File(historyFile).exists()) {
        println("(История загружена из $historyFile)")
    }
    println("Текущая стратегия: ${agent.getCurrentStrategyName()}")
    println("🐝 Рой агентов: ${if (agent.getSwarmStatus() != "Рой не активирован") "Активен" else "Отключен"}")
    val toolNames = mcpClientManager.connect().listTools().tools().joinToString(", ") { it.name() }
    println("🔧 MCP Weather tools: $toolNames")
    println("Чат-агент запущен (лимит контекста ${agent.llm.contextLimit} токенов).")

    printHelp()

    val mainScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val job = mainScope.launch {
        while (true) {
            print("\nВы: ")
            val input = readlnOrNull() ?: break

            // Список команд, для которых НЕ показываем статистику
            val commandsWithoutStats = listOf(
                "статус", "status",
                "помощь", "help",
                "пауза", "pause",
                "resume",
                "сброс", "reset", "новая задача",
                "дальше", "next", "продолжи",
                "шаг", "step",
                "clear", "stats",
                "swarm status", "swarm disable", "swarm enable",
                "invariants", "memory", "branch", "compare", "load",
                "plan", "validate",
                "да, понял", "нет, не понял"
            )

            // Проверяем, является ли ввод командой без статистики
            val isCommandWithoutStats = commandsWithoutStats.any {
                input.lowercase().startsWith(it) || input.lowercase() == it
            }

            when {
                input.equals("exit", ignoreCase = true) -> {
                    agent.saveHistory(historyFile)
                    agent.shutdown()
                    mcpClientManager.disconnect()
                    mainScope.cancel()
                    println("До свидания!")
                    return@launch
                }
                input.equals("clear", ignoreCase = true) -> {
                    agent.clearHistory()
                    File(historyFile).delete()
                    println("✅ История очищена")
                }
                input.equals("stats", ignoreCase = true) -> {
                    agent.printStats()
                }
                input.equals("help", ignoreCase = true) -> {
                    printHelp()
                }
                input.equals("swarm status", ignoreCase = true) -> {
                    println(agent.getSwarmStatus())
                }
                input.equals("swarm disable", ignoreCase = true) -> {
                    val response = agent.processNaturalLanguage(input)
                    println("Ассистент: $response")
                }
                input.equals("swarm enable", ignoreCase = true) -> {
                    val response = agent.processNaturalLanguage(input)
                    println("Ассистент: $response")
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
                            val response = agent.processNaturalLanguage(text)
                            println("Ассистент: $response")
                            agent.saveHistory(historyFile)
                        } catch (e: Exception) {
                            println("Ошибка при загрузке файла: ${e.message}")
                        }
                    }
                }
                else -> {
                    try {
                        val response = agent.processNaturalLanguageWithStats(input)
                        val content = response.first
                        val stats = response.second

                        println("Ассистент: $content")

                        if (!isCommandWithoutStats && (stats.promptTokens > 0 || stats.completionTokens > 0)) {
                            println("┌─────────────────────────────────────────┐")
                            println("│ Токены запроса: ${stats.promptTokens}  │")
                            println("│ Токены ответа: ${stats.completionTokens}   │")
                            println("│ Стоимость: ${"%.6f".format(stats.costUsd)} │")
                            println("└─────────────────────────────────────────┘")
                        }

                        agent.saveHistory(historyFile)
                    } catch (e: Exception) {
                        println("❌ Ошибка: ${e.message}")
                        if (e.message?.contains("400") == true || e.message?.contains("context") == true) {
                            println(">>> Контекст превысил лимит модели. Попробуйте очистить историю (clear) или сменить стратегию.")
                        }
                    }
                }
            }
        }
    }

    runBlocking {
        job.join()
    }
}

private fun printHelp() {
    println("""
        🤖 ДОСТУПНЫЕ КОМАНДЫ:
        
        📝 СОЗДАТЬ ЗАДАЧУ:
        - "Объясни [тема]"
        - "Расскажи про [тема]"
        - "Научи меня [тема]"
        
        ⚡ ВЫПОЛНЕНИЕ:
        - "дальше" - следующий шаг
        - "шаг N" - выполнить шаг N (последовательно)
        - "все сразу" - полное объяснение за раз
        
        🔍 ПОДТВЕРЖДЕНИЕ:
        - "да, понял" - подтвердить понимание
        - "нет, не понял" - запросить пояснение
        
        ℹ️ ИНФОРМАЦИЯ:
        - "статус" - показать состояние
        - "помощь" - эта справка
        - "stats" - статистика агента
        
        🔄 УПРАВЛЕНИЕ:
        - "пауза" - приостановить задачу
        - "resume" - возобновить задачу
        - "сброс" - сбросить состояние
        - "clear" - очистить историю
        
        🐝 УПРАВЛЕНИЕ РОЕМ:
        - "swarm status" - статус агентов
        - "swarm disable" - отключить рой
        - "swarm enable" - включить рой
        
        💡 ПРОСТО ОБЩАЙТЕСЬ ЕСТЕСТВЕННО!
    """.trimIndent())
}