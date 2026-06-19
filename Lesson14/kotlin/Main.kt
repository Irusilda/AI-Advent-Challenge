import models.*
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
    println("  'strategy sliding' — переключить на Sliding Window (последние 4 сообщений)")
    println("  'strategy facts'   — переключить на Sticky Facts (факты + последние 6 сообщений)")
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
    println("  'step <действие>' — выполнить шаг задачи с проверкой инвариантов")
    println("  'pause' — приостановить задачу")
    println("  'resume' — возобновить задачу")
    println("  'next' — перейти к следующему шагу")
    println("  'task status' — показать текущий статус задачи")
    println("  'task reset' — сбросить состояние задачи")
    println("  'profile show' — показать текущий профиль пользователя")
    println("  'profile update <имя> <стиль> <формат>' — обновить профиль")
    println("  'invariants show' — показать все инварианты")
    println("  'invariants active' — показать только активные инварианты")
    println("  'invariants add <id> <category> <description>' — добавить инвариант")
    println("  'invariants remove <id>' — удалить инвариант")
    println("  'invariants check <текст>' — проверить текст на нарушение инвариантов")
    println("  'invariants toggle <id>' — включить/выключить инвариант")
    println("  'invariants stats' — показать статистику инвариантов")
    println("  'invariants history' — показать историю нарушений")

    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: return

        when {
            input.equals("exit", ignoreCase = true) -> {
                agent.saveHistory(historyFile)
                println("До свидания!")
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
                agent.switchStrategy("sliding")
            }
            input.equals("strategy facts", ignoreCase = true) -> {
                agent.switchStrategy("sticky")
            }
            input.equals("strategy branch", ignoreCase = true) -> {
                agent.switchStrategy("branching")
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
            input.equals("pause", ignoreCase = true) -> {
                agent.taskState.pause()
                println("⏸️ Задача приостановлена")
            }
            input.equals("resume", ignoreCase = true) -> {
                agent.taskState.resume()
                println("▶️ Задача возобновлена")
            }
            input.equals("next", ignoreCase = true) -> {
                val result = agent.executeStep("next")
                when (result) {
                    is ValidationResult.Success -> {
                        println("✅ ${result.message}")
                        println("Текущее состояние: ${result.newState.stage}")
                    }
                    is ValidationResult.Paused -> println("⏸️ ${result.message}")
                    is ValidationResult.WrongAction -> println("⚠️ ${result.suggestion}")
                    is ValidationResult.InvariantViolation -> {
                        println("❌ ${result.message}")
                        result.violations.forEach { violation ->
                            println("- ${violation.invariant.description}")
                            println("  ${violation.explanation}")
                        }
                    }
                    is ValidationResult.OutOfBounds -> {
                        println("⚠️ ${result.message}")
                        println("💡 ${result.suggestion}")
                    }
                }
            }
            input.equals("task status", ignoreCase = true) -> {
                println(agent.getTaskStateInfo())
            }
            input.equals("task reset", ignoreCase = true) -> {
                agent.taskState.reset()
                println("✅ Состояние задачи сброшено")
            }
            input.equals("profile show", ignoreCase = true) -> {
                println("👤 Текущий профиль:")
                println(agent.userProfile.toInstruction())
            }
            input.startsWith("profile update", ignoreCase = true) -> {
                val parts = input.removePrefix("profile update").trim().split(" ", limit = 4)
                if (parts.size < 4) {
                    println("Формат: profile update <имя> <стиль> <формат>")
                    println("Пример: profile update Ирина официальный краткий")
                    continue
                }
                val name = parts[0]
                val style = parts[1]
                val format = parts[2]
                val constraints = if (parts.size > 3) {
                    parts.subList(3, parts.size).joinToString(" ").split(",").map { it.trim() }
                } else emptyList()

                val newProfile = UserProfile(
                    name = name,
                    style = style,
                    format = format,
                    constraints = constraints
                )

                if (agent.updateUserProfile(newProfile)) {
                    println("✅ Профиль обновлен")
                }
            }
            input.startsWith("step ", ignoreCase = true) -> {
                val action = input.removePrefix("step ").trim()
                val result = agent.executeStep(action)
                when (result) {
                    is ValidationResult.Success -> {
                        println("✅ ${result.message}")
                        println("Текущее состояние: ${result.newState.stage}")
                    }
                    is ValidationResult.Paused -> println("⏸️ ${result.message}")
                    is ValidationResult.WrongAction -> println("⚠️ ${result.suggestion}")
                    is ValidationResult.InvariantViolation -> {
                        println("❌ ${result.message}")
                        result.violations.forEach { violation ->
                            println("- ${violation.invariant.description}")
                            println("  ${violation.explanation}")
                        }
                    }
                    is ValidationResult.OutOfBounds -> {
                        println("⚠️ ${result.message}")
                        println("💡 ${result.suggestion}")
                    }
                }
            }
            input.equals("invariants show", ignoreCase = true) -> {
                agent.printInvariants()
            }
            input.equals("invariants active", ignoreCase = true) -> {
                val active = agent.getActiveInvariants()
                if (active.isEmpty()) {
                    println("Нет активных инвариантов")
                } else {
                    println("---- АКТИВНЫЕ ИНВАРИАНТЫ ----")
                    active.forEach { invariant ->
                        println("[${invariant.id}] ${invariant.description} (${invariant.severity})")
                    }
                }
            }
            input.startsWith("invariants add", ignoreCase = true) -> {
                val parts = input.removePrefix("invariants add").trim().split(" ", limit = 4)
                if (parts.size < 4) {
                    println("Формат: invariants add <id> <category> <description>")
                    println("Категории: ARCHITECTURE, TECHNOLOGY, BUSINESS_RULE, SECURITY, PERFORMANCE, LEGAL")
                    continue
                }
                try {
                    val id = parts[0]
                    val category = InvariantCategory.valueOf(parts[1].uppercase())
                    val description = parts.subList(2, parts.size).joinToString(" ")
                    val invariant = Invariant(
                        id = id,
                        category = category,
                        description = description,
                        severity = InvariantSeverity.HIGH
                    )
                    agent.addInvariant(invariant)
                } catch (e: Exception) {
                    println("❌ Ошибка: ${e.message}")
                }
            }
            input.startsWith("invariants remove", ignoreCase = true) -> {
                val id = input.removePrefix("invariants remove").trim()
                agent.removeInvariant(id)
            }
            input.startsWith("invariants check", ignoreCase = true) -> {
                val text = input.removePrefix("invariants check").trim()
                if (text.isEmpty()) {
                    println("Укажите текст для проверки")
                    continue
                }
                val results = agent.checkTextForInvariants(text)
                if (results.isEmpty()) {
                    println("✅ Текст соответствует всем инвариантам")
                } else {
                    println("❌ Найдены нарушения инвариантов:")
                    results.forEach { result ->
                        println("- ${result.invariant.description}")
                        println("  Объяснение: ${result.explanation ?: "Нарушение обнаружено"}")
                        if (result.suggestion != null) {
                            println("  Предложение: ${result.suggestion}")
                        }
                    }
                }
            }
            input.startsWith("invariants toggle", ignoreCase = true) -> {
                val id = input.removePrefix("invariants toggle").trim()
                val invariant = agent.getInvariants().find { it.id == id }
                if (invariant != null) {
                    val updated = invariant.copy(isActive = !invariant.isActive)
                    agent.removeInvariant(id)
                    agent.addInvariant(updated)
                    println("✅ Инвариант $id ${if (updated.isActive) "включен" else "выключен"}")
                } else {
                    println("❌ Инвариант $id не найден")
                }
            }
            input.equals("invariants stats", ignoreCase = true) -> {
                println(agent.getInvariantStats())
            }
            input.equals("invariants history", ignoreCase = true) -> {
                println("---- ИСТОРИЯ НАРУШЕНИЙ ИНВАРИАНТОВ ----")
                val history = agent.getViolationHistory()
                if (history.isEmpty()) {
                    println("Нарушений не зафиксировано")
                } else {
                    history.forEachIndexed { index, violation ->
                        println("${index + 1}. ${violation.invariant.description}")
                        println("   Объяснение: ${violation.explanation ?: "Нарушение обнаружено"}")
                        println("   Предложение: ${violation.suggestion ?: "Нет предложения"}")
                        println()
                    }
                }
            }
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