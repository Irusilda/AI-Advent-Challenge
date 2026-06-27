import models.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

fun main() {
    val apiKey = requireNotNull(System.getenv("DEEPSEEK_API_KEY")) {
        "DEEPSEEK_API_KEY не задана"
    }
    val llmClient = DeepSeekClient(apiKey)

    val mcpClientManager = McpClientManager()
    mcpClientManager.connectAll(listOf(
        ServerConfig("WeatherMcpServerKt", "weather"),
        ServerConfig("ScheduledMcpServerKt", "scheduler"),
        ServerConfig("PipelineMcpServerKt", "pipeline")
    ))

    val pipelineEngine = PipelineEngine(mcpClientManager)
    val agent = ChatAgent(llmClient, enableSwarm = true, mcpClientManager = mcpClientManager)
    val historyFile = "chat_history.json"
    agent.loadHistory(historyFile)
    if (File(historyFile).exists()) {
        println("(История загружена из $historyFile)")
    }
    println("Текущая стратегия: ${agent.getCurrentStrategyName()}")
    println("🐝 Рой агентов: ${if (agent.getSwarmStatus() != "Рой не активирован") "Активен" else "Отключен"}")
    val toolNames = mcpClientManager.listAllTools().joinToString(", ") { it.name() }
    println("🔧 MCP tools ($toolNames)")
    println("Чат-агент запущен (лимит контекста ${agent.llm.contextLimit} токенов).")

    printHelp()

    val mainScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var lastResultCount = 0
    var isFirstPoll = true

    mainScope.launch {
        while (true) {
            delay(10.seconds)
            try {
                val aggregated = mcpClientManager.callTool("get-aggregated", emptyMap())
                val match = Regex("""Total results: (\d+)""").find(aggregated)
                if (match != null) {
                    val current = match.groupValues[1].toInt()
                    if (isFirstPoll) {
                        isFirstPoll = false
                        lastResultCount = current
                    } else if (current > lastResultCount) {
                        val diff = current - lastResultCount
                        delay(2000)
                        val details = mcpClientManager.callTool("get-results", mapOf("limit" to diff))
                        val lines = details.lines()
                        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
                        var currentName: String? = null
                        var currentContent = mutableListOf<String>()
                        var collecting = false
                        for (line in lines) {
                            val trimmed = line.trimStart()
                            val headerMatch = Regex("""#\d+ \[.*?\] (.+) \((.+)\)""").find(trimmed)
                            if (headerMatch != null) {
                                if (currentName != null) blocks.add(currentName to currentContent)
                                currentName = "«${headerMatch.groupValues[1]}» (${headerMatch.groupValues[2]})"
                                currentContent = mutableListOf()
                                collecting = false
                            } else if (trimmed.startsWith("Data:") || trimmed.startsWith("Message:") || trimmed.startsWith("Error:")) {
                                currentContent.add(trimmed.substringAfter(":").trim())
                                collecting = true
                            } else if (trimmed.startsWith("Summary")) {
                                currentContent.add("Сводка по всем данным")
                                collecting = true
                            } else if (collecting && trimmed.isNotEmpty()) {
                                currentContent.add(trimmed)
                            }
                        }
                        if (currentName != null) blocks.add(currentName to currentContent)
                        val allTasks = mcpClientManager.callTool("list-tasks", mapOf("filter_status" to "active"))
                        blocks.removeAll { (name, _) ->
                            if (name.contains("(collect)")) {
                                val taskName = Regex("""«(.+)» \(collect\)""").find(name)?.groupValues?.get(1)
                                taskName != null && allTasks.contains("summary-$taskName")
                            } else false
                        }
                        println()
                        if (blocks.isNotEmpty()) {
                            blocks.forEach { (name, content) ->
                                println("🔔 Сработало: $name")
                                content.forEach { println("   $it") }
                            }
                        } else {
                            println("🔔 Сработало задач: $diff")
                        }
                    }
                    lastResultCount = current
                }
            } catch (_: Exception) {
                // ignore polling errors
            }
        }
    }

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
                "invariants", "invariant add", "invariant remove", "profile", "profile set", "strategy", "task", "violations", "memory", "branch", "compare", "load",
                "plan", "validate",
                "pipeline",
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
                    mcpClientManager.disconnectAll()
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
                input.equals("invariants", ignoreCase = true) -> {
                    agent.printInvariants()
                }
                input.startsWith("invariant add ", ignoreCase = true) -> {
                    val desc = input.removePrefix("invariant add ").trim()
                    if (desc.isNotEmpty()) {
                        val inv = Invariant(
                            id = "manual-${System.currentTimeMillis()}",
                            category = InvariantCategory.SECURITY,
                            description = desc,
                            severity = InvariantSeverity.CRITICAL,
                            isActive = true
                        )
                        agent.addInvariant(inv)
                        println("✅ Инвариант добавлен: $desc")
                    } else {
                        println("Usage: invariant add <описание>")
                    }
                }
                input.startsWith("invariant remove ", ignoreCase = true) -> {
                    val id = input.removePrefix("invariant remove ").trim()
                    if (agent.removeInvariant(id)) {
                        println("✅ Инвариант $id удален")
                    } else {
                        println("❌ Инвариант $id не найден")
                    }
                }
                input.equals("profile", ignoreCase = true) -> {
                    println("👤 Текущий профиль:")
                    println(agent.userProfile.toInstruction())
                }
                input.startsWith("profile set ", ignoreCase = true) -> {
                    val name = input.removePrefix("profile set ").trim()
                    if (agent.updateUserProfile(UserProfile(name = name))) {
                        println("✅ Профиль обновлен: $name")
                    }
                }
                input.startsWith("strategy ", ignoreCase = true) -> {
                    val strategy = input.removePrefix("strategy ").trim()
                    try {
                        agent.switchStrategy(strategy)
                        println("✅ Стратегия переключена на: ${agent.getCurrentStrategyName()}")
                    } catch (e: Exception) {
                        println("❌ Ошибка: ${e.message}")
                    }
                }
                input.equals("task", ignoreCase = true) -> {
                    println(agent.getTaskStateInfo())
                }
                input.equals("violations", ignoreCase = true) -> {
                    val history = agent.getViolationHistory()
                    if (history.isEmpty()) {
                        println("✅ Нарушений не было")
                    } else {
                        println("📋 История нарушений (${history.size}):")
                        history.forEach { println("- ${it.invariant.description}: ${it.explanation}") }
                    }
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
                input.startsWith("pipeline ", ignoreCase = true) -> {
                    val pipelineQuery = input.removePrefix("pipeline ").trim()
                    if (pipelineQuery.isEmpty()) {
                        println("Usage: pipeline <search query>")
                    } else {
                        println("┌─────────────────────────────────────────┐")
                        println("│  Pipeline: file search → summarize → save")
                        println("│  Query: \"$pipelineQuery\"")
                        println("└─────────────────────────────────────────┘")
                        println()

                        val result = pipelineEngine.executePipeline(pipelineQuery)
                        for ((i, step) in result.steps.withIndex()) {
                            println("Step ${i + 1}/${result.steps.size} ✓ ${step.stepName}")
                        }
                        println()

                        val lastStep = result.steps.lastOrNull()
                        val fileName = lastStep?.output
                            ?.removePrefix("Saved to ")
                            ?.substringBefore(" (")
                            ?.substringAfterLast("/")
                        if (fileName != null) println("✅ Сохранено: $fileName")
                        println()

                        val summarizeStep = result.steps.find { it.toolName == "summarize" }
                        val content = summarizeStep?.output
                        if (content != null && content.isNotBlank()) {
                            println("Содержимое:")
                            content.lines().forEach { line -> println("  $line") }
                        }
                    }
                }
                input.startsWith("веб ", ignoreCase = true) || input.startsWith("web ", ignoreCase = true) -> {
                    val webQuery = input.removePrefix("веб ").removePrefix("web ").trim()
                    if (webQuery.isEmpty()) {
                        println("Usage: веб <поисковый запрос>")
                    } else {
                        println("┌─────────────────────────────────────────┐")
                        println("│  Pipeline: web search → fetch → summarize → save")
                        println("│  Query: \"$webQuery\"")
                        println("└─────────────────────────────────────────┘")
                        println()

                        val result = pipelineEngine.executeWebPipeline(webQuery)
                        for ((i, step) in result.steps.withIndex()) {
                            println("Step ${i + 1}/${result.steps.size} ✓ ${step.stepName}")
                        }
                        println()

                        val lastStep = result.steps.lastOrNull()
                        val fileName = lastStep?.output
                            ?.removePrefix("Saved to ")
                            ?.substringBefore(" (")
                            ?.substringAfterLast("/")
                        if (fileName != null) println("✅ Сохранено: $fileName")
                        println()

                        val summarizeStep = result.steps.find { it.toolName == "summarize" }
                        val content = summarizeStep?.output
                        if (content != null && content.isNotBlank()) {
                            println("Содержимое:")
                            content.lines().forEach { line -> println("  $line") }
                        }
                    }
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
        
        🔧 ПАЙПЛАЙН (поиск → суммаризация → сохранение):
        - "pipeline [запрос]" - поиск в локальных .txt файлах
        - "веб [запрос]" - поиск в интернете (DuckDuckGo)
        
        💡 ПРОСТО ОБЩАЙТЕСЬ ЕСТЕСТВЕННО!
    """.trimIndent())
}