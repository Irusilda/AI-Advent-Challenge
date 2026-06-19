import contextstrategy.ContextStrategy
import contextstrategy.impl.BranchingStrategy
import contextstrategy.impl.SlidingWindowStrategy
import contextstrategy.impl.StickyFactsStrategy
import models.*

class ChatAgent(val llm: DeepSeekClient) {

    val memoryManager = MemoryManager()
    private val fullHistory = mutableListOf<Message>()
    val invariantManager = InvariantManager()
    private var invariantViolationDetected = false

    var userProfile: UserProfile = UserProfile()
        set(value) {
            field = value
            updateSystemMessage()
        }

    val taskState = TaskStateMachine()

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
        val system = Message("system", buildPersonalizedSystemMessage())
        fullHistory.add(system)
        currentStrategy.loadHistory(listOf(system))
    }

    fun buildPersonalizedSystemMessage(): String {
        val profileInstruction = """
            Ты — ${userProfile.name}.
            Стиль: ${userProfile.style}
            Формат: ${userProfile.format}
            Ограничения: ${userProfile.constraints.joinToString(", ")}
            """.trimIndent()

        val stateInfo = """
            Текущее состояние задачи:
            - Этап: ${taskState.state.stage}
            - Шаг: ${taskState.state.currentStep}/${taskState.state.totalSteps}
            - Ожидаемое действие: ${taskState.state.expectedAction}
            - Пауза: ${if (taskState.state.isPaused) "да (не отвечай на запросы, пока не снимут паузу)" else "нет"}
            """.trimIndent()

        val invariantsInfo = invariantManager.getInvariantsDescription()

        val violationInfo = if (invariantViolationDetected) {
            """
            
            ⚠️ ВНИМАНИЕ: Было обнаружено нарушение инвариантов в предыдущем ответе!
            Пожалуйста, убедись, что следующий ответ не нарушает инварианты.
            """.trimIndent()
        } else ""

        return """
            $profileInstruction
            $stateInfo
            $invariantsInfo
            $violationInfo
            
            Важно:
            - Строго следуй указанному стилю и формату.
            - Если стоит пауза — ничего не делай, просто сообщи, что задача приостановлена.
            - Если задача на этапе PLANNING — сначала составь план, жди подтверждения.
            - На этапе EXECUTION выполняй шаги по порядку, не перескакивай.
            - На этапе VALIDATION — проверь, достигнута ли цель.
            - Не повторяй уже сказанное без необходимости.
            - Строго соблюдай инварианты! Если запрос пользователя нарушает инвариант, вежливо откажись и объясни причину.
            - Если не уверен, что ответ соответствует инвариантам, уточни или предложи альтернативу.
            """.trimIndent()
    }

    fun switchStrategy(strategyType: String) {
        val strategyDescription = when (strategyType.lowercase()) {
            "sliding" -> "SlidingWindowStrategy - хранит последние N сообщений"
            "sticky" -> "StickyFactsStrategy - хранит факты и последние сообщения"
            "branching" -> "BranchingStrategy - поддерживает ветвление диалога"
            else -> throw IllegalArgumentException("Unknown strategy: $strategyType")
        }

        val violations = invariantManager.checkTextForViolations(strategyDescription)
        if (violations.isNotEmpty()) {
            val criticalViolations = violations.filter {
                it.invariant.severity == InvariantSeverity.CRITICAL
            }
            if (criticalViolations.isNotEmpty()) {
                println("❌ Нельзя переключить стратегию: ${criticalViolations.first().explanation}")
                return
            }
        }

        val systemMsg = Message("system", buildPersonalizedSystemMessage())
        val currentMessages = currentStrategy.getHistory().filter { it.role != "system" }
        currentStrategy = when (strategyType.lowercase()) {
            "sliding" -> SlidingWindowStrategy(windowSize = 4, systemMessage = systemMsg)
            "sticky" -> StickyFactsStrategy(llm, keepLastMessages = 6, systemMessage = systemMsg)
            "branching" -> BranchingStrategy(systemMessage = systemMsg)
            else -> throw IllegalArgumentException("Unknown strategy: $strategyType")
        }
        currentStrategy.loadHistory(listOf(systemMsg) + currentMessages)
        println("✅ Переключено на стратегию: ${currentStrategy.javaClass.simpleName}")
    }

    fun getCurrentStrategyName(): String = currentStrategy.getName()

    fun getBranchingStrategyOrNull(): BranchingStrategy? = currentStrategy as? BranchingStrategy

    fun chat(userMessage: String): DeepSeekResponse {
        // 1. Проверяем, является ли сообщение командой шага
        val isStepCommand = userMessage.startsWith("step", ignoreCase = true) ||
                userMessage.equals("next", ignoreCase = true) ||
                userMessage.startsWith("описать план", ignoreCase = true) ||
                userMessage.startsWith("выполнить шаг", ignoreCase = true)

        // 2. Проверяем запрос пользователя на нарушение инвариантов
        val userMessageViolations = invariantManager.checkTextForViolations(userMessage)
        if (userMessageViolations.isNotEmpty()) {
            val criticalViolations = userMessageViolations.filter {
                it.invariant.severity == InvariantSeverity.CRITICAL
            }
            if (criticalViolations.isNotEmpty()) {
                val violationMessage = buildString {
                    appendLine("⚠️ Запрос нарушает критические инварианты:")
                    criticalViolations.forEach { violation ->
                        appendLine("- ${violation.invariant.description}")
                        appendLine("  Объяснение: ${violation.explanation ?: "Нарушение обнаружено"}")
                        appendLine("  Предложение: ${violation.suggestion ?: "Измените запрос"}")
                    }
                    appendLine("\nПожалуйста, измените запрос, чтобы он соответствовал инвариантам.")
                }
                return DeepSeekResponse(
                    content = violationMessage,
                    totalTokens = 0,
                    promptTokens = 0,
                    completionTokens = 0,
                    elapsedMs = 0,
                    costUsd = 0.0
                )
            }
        }

        // 3. Проверяем состояние задачи ТОЛЬКО для команд шага
        if (isStepCommand) {
            val validationResult = taskState.validateStep(userMessage, invariantManager)
            when (validationResult) {
                is ValidationResult.Success -> {
                    // Продолжаем выполнение
                }
                is ValidationResult.WrongAction -> {
                    return DeepSeekResponse(
                        content = "⚠️ ${validationResult.suggestion}",
                        totalTokens = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        elapsedMs = 0,
                        costUsd = 0.0
                    )
                }
                is ValidationResult.Paused -> {
                    return DeepSeekResponse(
                        content = "⏸️ ${validationResult.message}",
                        totalTokens = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        elapsedMs = 0,
                        costUsd = 0.0
                    )
                }
                is ValidationResult.InvariantViolation -> {
                    val violationMessage = buildString {
                        appendLine("❌ Действие нарушает инварианты:")
                        validationResult.violations.forEach { violation ->
                            appendLine("- ${violation.invariant.description}")
                            appendLine("  Объяснение: ${violation.explanation ?: "Нарушение обнаружено"}")
                        }
                    }
                    return DeepSeekResponse(
                        content = violationMessage,
                        totalTokens = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        elapsedMs = 0,
                        costUsd = 0.0
                    )
                }
                is ValidationResult.OutOfBounds -> {
                    return DeepSeekResponse(
                        content = "⚠️ ${validationResult.message}\n💡 ${validationResult.suggestion}",
                        totalTokens = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        elapsedMs = 0,
                        costUsd = 0.0
                    )
                }
            }
        }

        // 4. Обработка обычного запроса
        val userMsg = Message("user", userMessage)
        memoryManager.processUserMessage(userMsg)
        fullHistory.add(userMsg)
        currentStrategy.addUserMessage(userMsg)

        val context = memoryManager.buildContext(
            systemMessage = Message("system", buildPersonalizedSystemMessage()),
            recentMessages = currentStrategy.buildContext().filter { it.role != "system" }
        )

        val response = llm.ask(context)

        // 5. Проверяем ответ ассистента на нарушение инвариантов
        val responseViolations = invariantManager.checkTextForViolations(response.content)
        if (responseViolations.isNotEmpty()) {
            val criticalViolations = responseViolations.filter {
                it.invariant.severity == InvariantSeverity.CRITICAL
            }
            if (criticalViolations.isNotEmpty()) {
                invariantViolationDetected = true

                val refusalMessage = buildString {
                    appendLine("❌ Я не могу предоставить этот ответ, так как он нарушает следующие инварианты:")
                    criticalViolations.forEach { violation ->
                        appendLine("\n🔴 ${violation.invariant.description}")
                        appendLine("   Причина: ${violation.explanation ?: "Нарушение обнаружено"}")
                        appendLine("   Рекомендация: ${violation.suggestion ?: "Исправьте ответ"}")
                    }
                    appendLine("\n💡 Пожалуйста, уточните запрос, и я предложу альтернативное решение.")
                }

                val fixedResponse = DeepSeekResponse(
                    content = refusalMessage,
                    totalTokens = response.totalTokens,
                    promptTokens = response.promptTokens,
                    completionTokens = response.completionTokens,
                    elapsedMs = response.elapsedMs,
                    costUsd = response.costUsd
                )

                val safeAssistantMsg = Message("assistant", refusalMessage)
                memoryManager.processAssistantResponse(safeAssistantMsg)
                currentStrategy.addAssistantMessage(safeAssistantMsg)
                fullHistory.add(safeAssistantMsg)

                return fixedResponse
            }
        }

        invariantViolationDetected = false
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
        println()
        println("📋 ИНВАРИАНТЫ:")
        println(invariantManager.getInvariantsDescription())
        println()
        println(invariantManager.getStats())
        println("------------------------")
    }

    fun clearHistory() {
        fullHistory.clear()
        val system = Message("system", buildPersonalizedSystemMessage())
        fullHistory.add(system)
        currentStrategy.clear()
        currentStrategy.loadHistory(listOf(system))
        memoryManager.clearAll()
        taskState.reset()
        invariantManager.clearViolationHistory()

        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalCost = 0.0
        fullPromptTokens = 0
        fullCompletionTokens = 0
        fullCost = 0.0
        invariantViolationDetected = false
    }

    fun saveHistory(filePath: String) {
        try {
            // Проверяем сохраняемые данные на нарушение инвариантов
            val historyText = currentStrategy.getHistory().joinToString("\n") { "${it.role}: ${it.content}" }
            val violations = invariantManager.checkTextForViolations(historyText)
            val criticalViolations = violations.filter {
                it.invariant.severity == InvariantSeverity.CRITICAL
            }

            if (criticalViolations.isNotEmpty()) {
                println("⚠️ История содержит критическую информацию, которая нарушает инварианты")
                println("Рекомендуется очистить историю перед сохранением")
                // Сохраняем только безопасные сообщения
                val safeMessages = currentStrategy.getHistory().filter { msg ->
                    invariantManager.checkTextForViolations(msg.content).isEmpty()
                }
                if (safeMessages.isEmpty()) {
                    println("❌ Нет безопасных сообщений для сохранения")
                    return
                }
                // Сохраняем только безопасные сообщения
                saveSafeHistory(filePath, safeMessages)
                return
            }

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
                    // Проверяем память на нарушение инвариантов
                    val memViolations = invariantManager.checkTextForViolations(item.content)
                    if (memViolations.isEmpty() || memViolations.all { it.invariant.severity != InvariantSeverity.CRITICAL }) {
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
            }
            jsonObject.put("memory", memoryArray)
            jsonObject.put("profile_name", userProfile.name)
            jsonObject.put("profile_style", userProfile.style)
            jsonObject.put("profile_format", userProfile.format)
            jsonObject.put("profile_constraints", org.json.JSONArray(userProfile.constraints))

            val invariantsArray = org.json.JSONArray()
            invariantManager.getAllInvariants().forEach {
                invariantsArray.put(
                    org.json.JSONObject()
                        .put("id", it.id)
                        .put("category", it.category.name)
                        .put("description", it.description)
                        .put("severity", it.severity.name)
                        .put("isActive", it.isActive)
                )
            }
            jsonObject.put("invariants", invariantsArray)

            java.io.File(filePath).writeText(jsonObject.toString(2))
            println("✅ История сохранена в $filePath")
        } catch (e: Exception) {
            println("❌ Ошибка при сохранении истории: ${e.message}")
        }
    }

    private fun saveSafeHistory(filePath: String, safeMessages: List<Message>) {
        val jsonObject = org.json.JSONObject()
        val messagesArray = org.json.JSONArray()
        safeMessages.forEach {
            messagesArray.put(
                org.json.JSONObject()
                    .put("role", it.role)
                    .put("content", it.content)
            )
        }
        jsonObject.put("strategy_history", messagesArray)
        jsonObject.put("memory", org.json.JSONArray())
        jsonObject.put("profile_name", userProfile.name)
        jsonObject.put("profile_style", userProfile.style)
        jsonObject.put("profile_format", userProfile.format)
        jsonObject.put("profile_constraints", org.json.JSONArray(userProfile.constraints))

        val invariantsArray = org.json.JSONArray()
        invariantManager.getAllInvariants().forEach {
            invariantsArray.put(
                org.json.JSONObject()
                    .put("id", it.id)
                    .put("category", it.category.name)
                    .put("description", it.description)
                    .put("severity", it.severity.name)
                    .put("isActive", it.isActive)
            )
        }
        jsonObject.put("invariants", invariantsArray)

        java.io.File(filePath).writeText(jsonObject.toString(2))
        println("✅ Безопасная часть истории сохранена в $filePath")
    }

    fun loadHistory(filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                println("⚠️ Файл не найден: $filePath")
                return
            }

            val text = file.readText().trim()
            if (text.isEmpty()) {
                println("⚠️ Файл пуст")
                return
            }

            val isOldFormat = text.startsWith("[")

            if (isOldFormat) {
                val messagesArray = org.json.JSONArray(text)
                loadMessagesFromArray(messagesArray)
                memoryManager.clearAll()
                println("✅ История загружена в старом формате")
            } else {
                val jsonObject = org.json.JSONObject(text)
                jsonObject.optJSONArray("strategy_history")?.let { loadMessagesFromArray(it) }

                jsonObject.optJSONArray("memory")?.let { loadMemoryFromArray(it) }

                // Загружаем инварианты
                jsonObject.optJSONArray("invariants")?.let { loadInvariantsFromArray(it) }

                val name = jsonObject.optString("profile_name", "Пользователь")
                val style = jsonObject.optString("profile_style", "нейтральный")
                val format = jsonObject.optString("profile_format", "обычный")
                val constraintsArray = jsonObject.optJSONArray("profile_constraints")
                val constraints = if (constraintsArray != null) {
                    (0 until constraintsArray.length()).map { constraintsArray.getString(it) }
                } else emptyList()
                userProfile = UserProfile(name, style, format, constraints)

                println("✅ История загружена из $filePath")
                println("👤 Профиль: $name")
                println("📋 Инвариантов загружено: ${invariantManager.getAllInvariants().size}")
            }
        } catch (e: Exception) {
            println("❌ Ошибка при загрузке истории: ${e.message}")
        }
    }

    private fun loadMessagesFromArray(array: org.json.JSONArray) {
        val loadedMessages = mutableListOf<Message>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            loadedMessages.add(Message(obj.getString("role"), obj.getString("content")))
        }
        if (loadedMessages.isNotEmpty()) {
            // Проверяем загружаемые сообщения на нарушение инвариантов
            val violations = loadedMessages
                .map { invariantManager.checkTextForViolations(it.content) }
                .flatten()

            if (violations.isNotEmpty()) {
                println("⚠️ Загружаемая история содержит ${violations.size} нарушений инвариантов")
                val criticalViolations = violations.filter {
                    it.invariant.severity == InvariantSeverity.CRITICAL
                }
                if (criticalViolations.isNotEmpty()) {
                    println("❌ Обнаружены критические нарушения, пропускаем проблемные сообщения")
                }
                val safeMessages = loadedMessages.filter { msg ->
                    invariantManager.checkTextForViolations(msg.content).isEmpty()
                }
                if (safeMessages.isNotEmpty()) {
                    // Добавляем системное сообщение если его нет
                    val messagesToLoad = if (safeMessages.firstOrNull()?.role != "system") {
                        listOf(Message("system", buildPersonalizedSystemMessage())) + safeMessages
                    } else {
                        safeMessages
                    }
                    currentStrategy.loadHistory(messagesToLoad)
                    fullHistory.clear()
                    fullHistory.addAll(messagesToLoad)
                    println("✅ Загружено ${safeMessages.size} безопасных сообщений")
                } else {
                    println("⚠️ Нет безопасных сообщений для загрузки")
                    // Создаем пустую историю с системным сообщением
                    val systemMsg = Message("system", buildPersonalizedSystemMessage())
                    currentStrategy.loadHistory(listOf(systemMsg))
                    fullHistory.clear()
                    fullHistory.add(systemMsg)
                }
            } else {
                currentStrategy.loadHistory(loadedMessages)
                fullHistory.clear()
                fullHistory.addAll(loadedMessages)
                println("✅ Загружено ${loadedMessages.size} сообщений")
            }
        }
    }

    private fun loadMemoryFromArray(array: org.json.JSONArray) {
        var loadedCount = 0
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                val type = MemoryType.valueOf(obj.getString("type"))
                val content = obj.getString("content")

                // Проверяем память на нарушение инвариантов
                val violations = invariantManager.checkTextForViolations(content)
                if (violations.isEmpty() || violations.all { it.invariant.severity != InvariantSeverity.CRITICAL }) {
                    val item = MemoryItem(
                        type = type,
                        content = content,
                        timestamp = obj.getLong("timestamp"),
                        source = obj.optString("source", ""),
                        importance = obj.optDouble("importance", 1.0)
                    )
                    memoryManager.addToMemory(item)
                    loadedCount++
                }
            } catch (e: Exception) {
                // Пропускаем проблемные элементы
                println("⚠️ Пропущен элемент памяти: ${e.message}")
            }
        }
        println("✅ Загружено $loadedCount элементов памяти")
    }

    private fun loadInvariantsFromArray(array: org.json.JSONArray) {
        var loadedCount = 0
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                val invariant = Invariant(
                    id = obj.getString("id"),
                    category = InvariantCategory.valueOf(obj.getString("category")),
                    description = obj.getString("description"),
                    severity = InvariantSeverity.valueOf(obj.getString("severity")),
                    isActive = obj.getBoolean("isActive")
                )
                invariantManager.addInvariant(invariant)
                loadedCount++
            } catch (e: Exception) {
                println("⚠️ Пропущен инвариант: ${e.message}")
            }
        }
        println("✅ Загружено $loadedCount инвариантов")
    }

    fun getInvariants(): List<Invariant> = invariantManager.getAllInvariants()

    fun getActiveInvariants(): List<Invariant> = invariantManager.getActiveInvariants()

    fun addInvariant(invariant: Invariant) {
        invariantManager.addInvariant(invariant)
        updateSystemMessage()
        println("✅ Инвариант ${invariant.id} добавлен")
    }

    fun removeInvariant(id: String): Boolean {
        val result = invariantManager.removeInvariant(id)
        if (result) {
            updateSystemMessage()
            println("✅ Инвариант $id удален")
        } else {
            println("❌ Инвариант $id не найден")
        }
        return result
    }

    fun checkTextForInvariants(text: String): List<InvariantCheckResult> =
        invariantManager.checkTextForViolations(text)

    private fun updateSystemMessage() {
        try {
            val systemMsg = Message("system", buildPersonalizedSystemMessage())
            val currentMessages = currentStrategy.getHistory().filter { it.role != "system" }
            currentStrategy.clear()
            currentStrategy.loadHistory(listOf(systemMsg) + currentMessages)

            if (fullHistory.isNotEmpty()) {
                fullHistory[0] = systemMsg
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при обновлении системного сообщения: ${e.message}")
        }
    }

    fun printInvariants() {
        println(invariantManager.getInvariantsDescription())
        println()
        println(invariantManager.getStats())
    }

    fun getInvariantStats(): String = invariantManager.getStats()

    fun updateUserProfile(newProfile: UserProfile): Boolean {
        val profileText = newProfile.toInstruction()
        val violations = invariantManager.checkTextForViolations(profileText)
        val criticalViolations = violations.filter {
            it.invariant.severity == InvariantSeverity.CRITICAL
        }

        if (criticalViolations.isNotEmpty()) {
            println("❌ Профиль содержит данные, нарушающие инварианты:")
            criticalViolations.forEach {
                println("- ${it.invariant.description}: ${it.explanation}")
            }
            return false
        }

        userProfile = newProfile
        updateSystemMessage()
        println("✅ Профиль пользователя обновлен")
        return true
    }

    fun getTaskStateInfo(): String = taskState.getCurrentStepInfo()

    fun getViolationHistory(): List<InvariantCheckResult> = invariantManager.getViolationHistory()

    fun executeStep(action: String): ValidationResult {
        return taskState.validateStep(action, invariantManager)
    }
}