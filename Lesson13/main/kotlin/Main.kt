import contextstrategy.impl.BranchingStrategy
import contextstrategy.impl.SlidingWindowStrategy
import contextstrategy.impl.StickyFactsStrategy
import models.MemoryType
import models.Message
import models.UserProfile
import java.io.File

fun main() {
    val apiKey = requireNotNull(System.getenv("DEEPSEEK_API_KEY")) {
        "DEEPSEEK_API_KEY не задана"
    }
    val llmClient = DeepSeekClient(apiKey)
    val agent = ChatAgent(llmClient)
    val historyFile = "chat_history.json"
    agent.loadHistory(historyFile)
    if (File(historyFile).exists()) {
        println("(История загружена из $historyFile)")
    }
    println("Текущая стратегия: ${agent.getCurrentStrategyName()}")
    println("Чат-агент запущен (лимит контекста ${agent.llm.contextLimit} токенов).")
    println("Команды:")
    println("  'exit' — выход")
    println("  'clear' — очистить историю")
    println("  'stats' — показать статистику")
    println("  'strategy sliding' — переключить на Sliding Window (последние 10 сообщений)")
    println("  'strategy facts'   — переключить на Sticky Facts (ключ-значение)")
    println("  'strategy branch'  — переключить на Branching (ветки диалога)")
    println("  (для веток:)")
    println("    'branch checkpoint' — создать контрольную точку")
    println("    'branch new <name>' — создать новую ветку от checkpoint")
    println("    'branch switch <name>' — переключиться на ветку")
    println("    'branch list' — показать список веток")
    println("    'branch delete <name>' — удалить ветку")
    println("  'compare <вопрос>' — сравнить ответ текущей стратегии с полной историей")
    println("  'load <файл>' — загрузить содержимое файла и отправить как сообщение")
    println("  'memory show' — показать содержимое всех слоев памяти")
    println("  'memory clear <short/working/long>' — очистить слой памяти")
    println("  'memory store <type> <text>' — сохранить текст в указанный слой")


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
            }
            input.equals("strategy sliding", ignoreCase = true) -> {
                val sysMsg = Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
                agent.setStrategy(SlidingWindowStrategy(windowSize = 4, systemMessage = sysMsg))
                println("Переключено на Sliding Window (последние 4 сообщений)")
            }
            input.equals("strategy facts", ignoreCase = true) -> {
                val sysMsg = Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
                agent.setStrategy(StickyFactsStrategy(llmClient, keepLastMessages = 6, systemMessage = sysMsg))
                println("Переключено на Sticky Facts (факты + последние 6 сообщений)")
            }
            input.equals("strategy branch", ignoreCase = true) -> {
                val sysMsg = Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
                agent.setStrategy(BranchingStrategy(sysMsg))
                println("Переключено на Branching (ветки диалога)")
            }
            input.startsWith("branch ", ignoreCase = true) -> {
                val parts = input.split(" ", limit = 3)
                if (parts.size < 2) {
                    println("Неполная команда branch")
                    continue
                }
                val branchCmd = parts[1].lowercase()
                val branchStrategy = agent.getBranchingStrategyOrNull()
                if (branchStrategy == null) {
                    println("Текущая стратегия не поддерживает ветвление. Переключитесь на 'strategy branch'")
                    continue
                }
                when (branchCmd) {
                    "checkpoint" -> {
                        val cp = branchStrategy.createCheckpoint()
                        println("Создан checkpoint на позиции $cp в текущей ветке")
                    }
                    "new" -> {
                        if (parts.size < 3) {
                            println("Укажите имя новой ветки: branch new <name>")
                            continue
                        }
                        val branchName = parts[2]
                        println("Введите номер checkpoint (или нажмите Enter для текущего момента):")
                        val cpInput = readlnOrNull()?.trim()
                        val checkpoint = if (cpInput.isNullOrEmpty()) branchStrategy.createCheckpoint()
                        else cpInput.toIntOrNull() ?: run {
                            println("Неверное число, использую текущий момент")
                            branchStrategy.createCheckpoint()
                        }
                        if (branchStrategy.createBranchFromCheckpoint(branchName, checkpoint)) {
                            println("Ветка '$branchName' создана от checkpoint $checkpoint")
                        } else {
                            println("Не удалось создать ветку (имя уже существует или неверный checkpoint)")
                        }
                    }
                    "switch" -> {
                        if (parts.size < 3) {
                            println("Укажите имя ветки: branch switch <name>")
                            continue
                        }
                        val branchName = parts[2]
                        if (branchStrategy.switchToBranch(branchName)) {
                            println("Переключено на ветку '$branchName'")
                        } else {
                            println("Ветка '$branchName' не найдена")
                        }
                    }
                    "list" -> {
                        val branches = branchStrategy.listBranches()
                        println("Ветки: ${branches.joinToString()}")
                    }
                    "delete" -> {
                        if (parts.size < 3) {
                            println("Укажите имя ветки: branch delete <name>")
                            continue
                        }
                        val branchName = parts[2]
                        if (branchStrategy.deleteBranch(branchName)) {
                            println("Ветка '$branchName' удалена")
                        } else {
                            println("Не удалось удалить (возможно, это main или ветка не существует)")
                        }
                    }
                    else -> println("Неизвестная подкоманда branch: $branchCmd")
                }
            }
            input.startsWith("compare ", ignoreCase = true) -> {
                val question = input.substringAfter("compare ").trim()
                if (question.isEmpty()) {
                    println("Укажите вопрос: compare <текст>")
                    continue
                }
                val result = agent.compare(question)
                println("----  FULL (полная история) ----")
                println(result.fullResponse.content)
                println("tokens: ${result.fullResponse.promptTokens}")
                println("---- COMPRESSED (текущая стратегия) ----")
                println(result.compressedResponse.content)
                println("tokens: ${result.compressedResponse.promptTokens}")
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
                        agent.saveHistory(historyFile)
                    } catch (e: Exception) {
                        println("Ошибка при загрузке файла: ${e.message}")
                    }
                }
            }
            input.startsWith("memory show", ignoreCase = true) -> {
                println("----  Краткосрочная память ----")
                agent.memoryManager.getMemoryByType(MemoryType.SHORT_TERM).forEach {
                    println("- ${it.content}")
                }
                println(" ----- Рабочая память -----")
                agent.memoryManager.getMemoryByType(MemoryType.WORKING).forEach {
                    println("- ${it.content}")
                }
                println(" ---- Долговременная память ----")
                agent.memoryManager.getMemoryByType(MemoryType.LONG_TERM).forEach {
                    println("- ${it.content}")
                }
            }
            input.startsWith("memory clear ", ignoreCase = true) -> {
                val typeStr = input.removePrefix("memory clear ").trim().lowercase()
                val type = when (typeStr) {
                    "short" -> MemoryType.SHORT_TERM
                    "working" -> MemoryType.WORKING
                    "long" -> MemoryType.LONG_TERM
                    else -> {
                        println("Неизвестный тип памяти. Используйте: short, working, long")
                        continue
                    }
                }
                agent.memoryManager.clearMemory(type)
                println("Память типа $type очищена")
            }
            input.startsWith("memory store ", ignoreCase = true) -> {
                val parts = input.removePrefix("memory store ").split(" ", limit = 2)
                if (parts.size < 2) {
                    println("Формат: memory store <type> <text>")
                    continue
                }
                val typeStr = parts[0].lowercase()
                val text = parts[1]
                val type = when (typeStr) {
                    "short" -> MemoryType.SHORT_TERM
                    "working" -> MemoryType.WORKING
                    "long" -> MemoryType.LONG_TERM
                    else -> {
                        println("Неизвестный тип памяти. Используйте: short, working, long")
                        continue
                    }
                }
                agent.memoryManager.storeInformation(type, text, "manual")
                println("Сохранено в $type память")
            }
            input.contains("pause") -> agent.taskState.pause()
            input.contains("resume") -> agent.taskState.resume()
            input.contains("next") -> agent.taskState.nextStep("выполнено")
            else -> {
                try {
                    val response = agent.chat(input)
                    println("Ассистент: ${response.content}")
                    println("┌─────────────────────────────────────────┐")
                    println("│ Токены запроса (по стратегии): ${response.promptTokens}  │")
                    println("│ Токены ответа: ${response.completionTokens}   │")
                    println("│ Стоимость запроса: ${"%.6f".format(response.costUsd)} │")
                    println("└─────────────────────────────────────────┘")
                    agent.saveHistory(historyFile)
                } catch (e: Exception) {
                    println("Ошибка: ${e.message}")
                    if (e.message?.contains("400") == true || e.message?.contains("context") == true) {
                        println(">>> Контекст превысил лимит модели. Попробуйте очистить историю (clear) или сменить стратегию.")
                    }
                }
            }
        }
    }
}