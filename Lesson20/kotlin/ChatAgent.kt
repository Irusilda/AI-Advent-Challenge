import contextstrategy.ContextStrategy
import contextstrategy.impl.BranchingStrategy
import contextstrategy.impl.SlidingWindowStrategy
import contextstrategy.impl.StickyFactsStrategy
import models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class ChatAgent(
    val llm: DeepSeekClient,
    enableSwarm: Boolean = true,
    private val mcpClientManager: McpClientManager? = null
) {

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

    var currentStrategy: ContextStrategy = SlidingWindowStrategy(
        windowSize = 4,
        systemMessage = Message("system", "Ты — полезный ассистент. Отвечай на русском языке.")
    )

    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalCost = 0.0

    private var fullPromptTokens = 0
    private var fullCompletionTokens = 0
    private var fullCost = 0.0

    private var orchestrator: AgentOrchestrator? = null
    private var mcpReActEngine: McpReActEngine? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageChannel = Channel<Pair<String, CompletableDeferred<DeepSeekResponse>>>(capacity = 100)
    private val isProcessing = AtomicBoolean(false)

    init {
        if (enableSwarm) {
            orchestrator = AgentOrchestrator(llm, taskState, mcpClientManager)
            println("🐝 Рой агентов инициализирован с поддержкой MCP")
        }

        if (mcpClientManager != null) {
            mcpReActEngine = McpReActEngine(llm, mcpClientManager)
            println("⚙️ McpReActEngine инициализирован")
        }

        val system = Message("system", buildPersonalizedSystemMessage())
        fullHistory.add(system)
        currentStrategy.loadHistory(listOf(system))

        startMessageProcessor()
    }

    private fun startMessageProcessor() {
        scope.launch {
            for ((message, deferred) in messageChannel) {
                try {
                    if (!isProcessing.compareAndSet(false, true)) {
                        delay(100.milliseconds)
                        continue
                    }

                    val response = processMessageInternal(message)
                    deferred.complete(response)

                    isProcessing.set(false)
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                    isProcessing.set(false)
                }
            }
        }
    }

    suspend fun sendMessageAsync(userMessage: String): DeepSeekResponse {
        val deferred = CompletableDeferred<DeepSeekResponse>()
        messageChannel.send(userMessage to deferred)
        return deferred.await()
    }

    private suspend fun processMessageInternal(userMessage: String): DeepSeekResponse {
        when (userMessage.lowercase()) {
            "swarm status" -> return createEmptyResponse(orchestrator?.getAgentStatus() ?: "Рой не активирован")
            "swarm disable" -> {
                orchestrator?.shutdown()
                orchestrator = null
                return createEmptyResponse("🐝 Рой агентов отключен. Переключение на обычный режим.")
            }
            "swarm enable" -> {
                if (orchestrator == null) {
                    orchestrator = AgentOrchestrator(llm, taskState, mcpClientManager)
                }
                return createEmptyResponse("🐝 Рой агентов активирован")
            }
            "pause", "пауза" -> {
                taskState.pause()
                return createEmptyResponse("⏸️ Задача приостановлена. Используйте 'resume' для продолжения.")
            }
            "resume", "продолжить" -> {
                taskState.resume()
                return createEmptyResponse("▶️ Задача возобновлена. Текущее состояние: ${taskState.state.stage}")
            }
            "next", "дальше" -> {
                if (taskState.state.stage == Stage.PLAN_APPROVED) {
                    val result = taskState.transitionTo(Stage.EXECUTION, "Начало выполнения")
                    if (result is TransitionResult.Success) {
                        return createEmptyResponse("✅ Выполнение начато.\n\n${taskState.getCurrentStepInfo()}")
                    } else {
                        return createEmptyResponse("❌ Не удалось начать выполнение.\n\n${taskState.getCurrentStepInfo()}")
                    }
                }
                return if (taskState.executeNextStep()) {
                    createEmptyResponse("✅ Шаг выполнен.\n\n${taskState.getCurrentStepInfo()}")
                } else {
                    createEmptyResponse("❌ Не удалось выполнить шаг. Проверьте состояние задачи.\n\n${taskState.getCurrentStepInfo()}")
                }
            }
        }

        when {
            userMessage.startsWith("plan:", ignoreCase = true) -> {
                val plan = userMessage.substringAfter("plan:").trim()
                return if (taskState.setPlan(plan)) {
                    createEmptyResponse("✅ План утвержден! Можете начинать выполнение.\n\nПлан:\n$plan")
                } else {
                    createEmptyResponse("❌ Не удалось утвердить план. Текущее состояние: ${taskState.state.stage}")
                }
            }
            userMessage.startsWith("validate:", ignoreCase = true) -> {
                val result = userMessage.substringAfter("validate:").trim()
                return if (taskState.validate(result)) {
                    createEmptyResponse("✅ Валидация пройдена! Задача завершена.\n\nРезультат валидации:\n$result")
                } else {
                    createEmptyResponse("❌ Не удалось выполнить валидацию. Текущее состояние: ${taskState.state.stage}")
                }
            }
            userMessage.startsWith("step", ignoreCase = true) -> {
                return if (taskState.executeNextStep()) {
                    createEmptyResponse("✅ Шаг выполнен.\n\n${taskState.getCurrentStepInfo()}")
                } else {
                    createEmptyResponse("❌ Не удалось выполнить шаг. Проверьте состояние задачи.\n\n${taskState.getCurrentStepInfo()}")
                }
            }
        }

        if (orchestrator != null) {
            val result = orchestrator?.processUserRequest(userMessage)
            if (result != null) {
                return createEmptyResponse("🐝 [Рой агентов]\n$result")
            }
        }

        return processRegularMessage(userMessage)
    }

    private fun createEmptyResponse(content: String): DeepSeekResponse {
        return DeepSeekResponse(
            content = content,
            totalTokens = 0,
            promptTokens = 0,
            completionTokens = 0,
            elapsedMs = 0,
            costUsd = 0.0
        )
    }

    private suspend fun processRegularMessage(userMessage: String): DeepSeekResponse {
        val criticalViolations = checkInvariants(userMessage)
        if (criticalViolations.isNotEmpty()) {
            return createViolationResponse(criticalViolations)
        }

        if (orchestrator == null) {
            return processNormalMessage(userMessage)
        }

        val validationResult = taskState.validateStep(userMessage, invariantManager)
        return when (validationResult) {
            is ValidationResult.Success -> processNormalMessage(userMessage)
            is ValidationResult.Paused -> createEmptyResponse("⏸️ ${validationResult.message}")
            is ValidationResult.WrongAction -> createEmptyResponse("⚠️ ${validationResult.suggestion}")
            is ValidationResult.InvariantViolation -> {
                val message = buildString {
                    appendLine("❌ Действие нарушает инварианты:")
                    validationResult.violations.forEach { violation ->
                        appendLine("- ${violation.invariant.description}")
                        appendLine("  Объяснение: ${violation.explanation ?: "Нарушение обнаружено"}")
                    }
                }
                createEmptyResponse(message)
            }
            is ValidationResult.OutOfBounds -> createEmptyResponse("⚠️ ${validationResult.message}\n💡 ${validationResult.suggestion}")
        }
    }

    private suspend fun processNormalMessage(userMessage: String): DeepSeekResponse {
        val userMsg = Message("user", userMessage)
        memoryManager.processUserMessage(userMsg)
        fullHistory.add(userMsg)
        currentStrategy.addUserMessage(userMsg)

        val systemMessage = Message("system", buildPersonalizedSystemMessage())
        val context = memoryManager.buildContext(
            systemMessage = systemMessage,
            recentMessages = currentStrategy.buildContext().filter { it.role != "system" }
        )

        if (mcpClientManager != null) {
            return processWithMcpTools(systemMessage, context, userMsg)
        }

        val response = withContext(Dispatchers.IO) {
            llm.ask(context)
        }

        val responseCriticalViolations = checkInvariants(response.content)
        if (responseCriticalViolations.isNotEmpty()) {
            invariantViolationDetected = true
            val refusalMessage = buildRefusalMessage(responseCriticalViolations)

            val fixedResponse = DeepSeekResponse(
                content = refusalMessage,
                totalTokens = response.totalTokens,
                promptTokens = response.promptTokens,
                completionTokens = response.completionTokens,
                elapsedMs = response.elapsedMs,
                costUsd = response.costUsd
            )

            val safeAssistantMsg = Message("assistant", refusalMessage)
            addAssistantMessage(safeAssistantMsg)

            return fixedResponse
        }

        invariantViolationDetected = false
        val assistantMsg = Message("assistant", response.content)
        addAssistantMessage(assistantMsg)

        totalPromptTokens += response.promptTokens
        totalCompletionTokens += response.completionTokens
        totalCost += response.costUsd

        scope.launch {
            val fullContext = fullHistory.toList()
            val fullResponse = withContext(Dispatchers.IO) {
                llm.ask(fullContext)
            }
            fullPromptTokens += fullResponse.promptTokens
            fullCompletionTokens += fullResponse.completionTokens
            fullCost += fullResponse.costUsd
        }

        return response
    }

    private suspend fun processWithMcpTools(
        systemMessage: Message,
        initialMessages: List<Message>,
        userMessage: Message
    ): DeepSeekResponse {
        val engine = mcpReActEngine ?: return processNormalMessage(userMessage.content)

        // Специфичные инструкции для планировщика задач
        val schedulerInstructions = """
        Для создания напоминаний используй schedule-task с типом reminder.
        Для периодического вызова MCP-инструментов (погода, курсы, etc): schedule-mcp-tool.
        schedule-task type=collect — ТОЛЬКО для HTTP-запросов по url. Для MCP-инструментов НЕ ИСПОЛЬЗУЙ его — используй schedule-mcp-tool.
        Показывать сырые данные каждый N минут → schedule-mcp-tool (результаты показываются в чате).
        Сводка (агрегация) → schedule-task type=summary + ОБЯЗАТЕЛЬНО params.source_task_name = название collect-задачи.
        ВАЖНО: schedule-task type=summary без source_task_name вернёт ОШИБКУ. Всегда передавай params={"source_task_name":"<имя collect-задачи>"}.
        Если пользователь просит «показывай погоду» → достаточно schedule-mcp-tool.
        Если пользователь просит «сводку» → создай обе задачи: schedule-mcp-tool (для сбора) + schedule-task type=summary с params={"source_task_name":"<имя collect-задачи>"}. Сырые данные скрыты, только сводка.
        get-aggregated — ТОЛЬКО для просмотра уже существующих результатов. НЕ вызывай его перед созданием задачи.
        ВНИМАНИЕ: Параметры внешнего MCP-инструмента (например city, days) — ТОЛЬКО через tool_args="key=value, key=value". НЕ передавай city или другие поля как прямые параметры schedule-mcp-tool — MCP SDK вернёт ошибку.
        Пример: schedule-mcp-tool name="москва" mcp_tool="get-current-weather" mcp_server="WeatherMcpServerKt" interval_minutes=1 tool_args="city=Москва"
        
        ВАЖНО: Если запрос можно выполнить одним MCP-вызовом (например, 
        schedule-task) — вызови инструмент без пайплайна планирования. 
        Не нужно описывать план, этапы и валидацию. Просто сделай вызов 
        и сообщи результат в 1-2 предложения.
        
        ЗАПРЕЩЕНО: придумывать ID задач. ID возвращает только инструмент.
        Если ты не вызвала schedule-task или schedule-mcp-tool — задачи не существует.
        Не пиши «Задача создана» без вызова инструмента.
        НЕ создавай дублирующиеся задачи. Если задача с таким именем уже существует — не создавай новую.
        Сначала проверь существующие задачи через list-tasks.
        
        ДЛЯ ВЕБ-ПОИСКА: используй run-pipeline с source="web". 
        run-pipeline сам выполнит цепочку: web-search → fetch-url → summarize → saveToFile.
        НЕ вызывай web-search, fetch-url, summarize и saveToFile по отдельности — используй run-pipeline.
        """.trimIndent()

        val fullSystemPrompt = "${systemMessage.content}\n\n$schedulerInstructions"

        val result = engine.execute(
            systemPrompt = fullSystemPrompt,
            userMessage = userMessage.content,
            requireTools = true
        )

        val callHistory = result.toolCalls
        val traceString = if (callHistory.isNotEmpty()) {
            engine.buildToolTrace(callHistory)
        } else ""

        val finalContent = result.content + traceString
        val assistantMsg = Message("assistant", finalContent)
        addAssistantMessage(assistantMsg)

        return DeepSeekResponse(
            content = finalContent,
            totalTokens = 0,
            promptTokens = 0,
            completionTokens = 0,
            elapsedMs = 0,
            costUsd = 0.0
        )
    }

    private fun checkInvariants(text: String): List<InvariantCheckResult> =
        invariantManager.checkTextForViolations(text)
            .filter { it.invariant.severity == InvariantSeverity.CRITICAL }

    private fun createViolationResponse(violations: List<InvariantCheckResult>): DeepSeekResponse {
        val message = buildString {
            appendLine("⚠️ Запрос нарушает критические инварианты:")
            violations.forEach { violation ->
                appendLine("- ${violation.invariant.description}")
                appendLine("  Объяснение: ${violation.explanation ?: "Нарушение обнаружено"}")
                appendLine("  Предложение: ${violation.suggestion ?: "Измените запрос"}")
            }
            appendLine("\nПожалуйста, измените запрос, чтобы он соответствовал инвариантам.")
        }
        return createEmptyResponse(message)
    }

    private fun buildRefusalMessage(violations: List<InvariantCheckResult>): String {
        return buildString {
            appendLine("❌ Я не могу предоставить этот ответ, так как он нарушает следующие инварианты:")
            violations.forEach { violation ->
                appendLine("\n🔴 ${violation.invariant.description}")
                appendLine("   Причина: ${violation.explanation ?: "Нарушение обнаружено"}")
                appendLine("   Рекомендация: ${violation.suggestion ?: "Исправьте ответ"}")
            }
            appendLine("\n💡 Пожалуйста, уточните запрос, и я предложу альтернативное решение.")
        }
    }

    private fun addAssistantMessage(msg: Message) {
        memoryManager.processAssistantResponse(msg)
        currentStrategy.addAssistantMessage(msg)
        fullHistory.add(msg)
    }

    suspend fun compareAsync(question: String): CompareResult = withContext(Dispatchers.IO) {
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

        CompareResult(
            fullResponse = fullResponse,
            compressedResponse = compressedResponse
        )
    }

    fun compare(question: String): CompareResult = runBlocking {
        compareAsync(question)
    }

    fun buildPersonalizedSystemMessage(): String {
        val profileInstruction = """
        Ты — ${userProfile.name}.
        Стиль: ${userProfile.style}
        Формат: ${userProfile.format}
        Ограничения: ${userProfile.constraints.joinToString(", ")}
    """.trimIndent()

        val stateInfo = """
        Состояние задачи: ${taskState.state.stage}
        Шаг: ${taskState.state.currentStep}/${taskState.state.totalSteps}
        Ожидаемое действие: ${taskState.state.expectedAction}
        Пауза: ${if (taskState.state.isPaused) "ДА" else "НЕТ"}
        План: ${if (taskState.state.plan.isNotEmpty()) "✓" else "✗"}
        Валидация: ${if (taskState.state.validationNotes.isNotEmpty()) "✓" else "✗"}
    """.trimIndent()

        val rules = """
        ПРАВИЛА:
        1. Использовать Kotlin и ContextStrategy
        2. Использовать DeepSeek API
        3. Сохранять историю в JSON
        4. Уважать интеллектуальную собственность
        5. Указывать источники
        6. Безопасная аутентификация (OAuth/JWT/хеширование)
        7. Соблюдать лимиты контекста
        8. Соблюдать правила работы с данными
        
        ПЕРЕХОДЫ: INIT→PLANNING→PLAN_APPROVED→EXECUTION→VALIDATION→DONE
        НЕЛЬЗЯ: перепрыгивать этапы, выполнять без плана, завершать без валидации
    """.trimIndent()

        val violationInfo = if (invariantViolationDetected) {
            "\n⚠️ Обнаружено нарушение правил в предыдущем ответе! Исправься."
        } else ""

        return """
        $profileInstruction
        $stateInfo
        $rules
        $violationInfo
        
        Строго следуй правилам и стилю. Не перепрыгивай этапы.
    """.trimIndent()
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
        println()
        println("🐝 РОЙ АГЕНТОВ:")
        println(orchestrator?.getAgentStatus() ?: "Рой не активирован")
        println()
        println("📊 СОСТОЯНИЕ ЗАДАЧИ:")
        println(taskState.getCurrentStepInfo())
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
            val historyText = currentStrategy.getHistory().joinToString("\n") { "${it.role}: ${it.content}" }
            val violations = invariantManager.checkTextForViolations(historyText)
            val criticalViolations = violations.filter {
                it.invariant.severity == InvariantSeverity.CRITICAL
            }

            if (criticalViolations.isNotEmpty()) {
                println("⚠️ История содержит критическую информацию, которая нарушает инварианты")
                val safeMessages = currentStrategy.getHistory().filter { msg ->
                    invariantManager.checkTextForViolations(msg.content).isEmpty()
                }
                if (safeMessages.isEmpty()) {
                    println("❌ Нет безопасных сообщений для сохранения")
                    return
                }
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
            MemoryType.entries.forEach { type ->
                memoryManager.getMemoryByType(type).forEach { item ->
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

            jsonObject.put("task_stage", taskState.state.stage.name)
            jsonObject.put("task_step", taskState.state.currentStep)
            jsonObject.put("task_total_steps", taskState.state.totalSteps)
            jsonObject.put("task_plan", taskState.state.plan)
            jsonObject.put("task_validation", taskState.state.validationNotes)

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
                jsonObject.optJSONArray("invariants")?.let { loadInvariantsFromArray(it) }

                val name = jsonObject.optString("profile_name", "Пользователь")
                val style = jsonObject.optString("profile_style", "нейтральный")
                val format = jsonObject.optString("profile_format", "обычный")
                val constraintsArray = jsonObject.optJSONArray("profile_constraints")
                val constraints = if (constraintsArray != null) {
                    (0 until constraintsArray.length()).map { constraintsArray.getString(it) }
                } else emptyList()
                userProfile = UserProfile(name, style, format, constraints)

                try {
                    val stageStr = jsonObject.optString("task_stage", "INIT")
                    val stage = Stage.valueOf(stageStr)
                    val step = jsonObject.optInt("task_step", 0)
                    val totalSteps = jsonObject.optInt("task_total_steps", 1)
                    val plan = jsonObject.optString("task_plan", "")
                    val validation = jsonObject.optString("task_validation", "")

                    taskState.state = TaskState(
                        stage = stage,
                        currentStep = step,
                        totalSteps = totalSteps,
                        plan = plan,
                        validationNotes = validation,
                        expectedAction = when (stage) {
                            Stage.INIT, Stage.PLANNING -> "описать план"
                            Stage.PLAN_APPROVED -> "начать выполнение"
                            Stage.EXECUTION -> "выполнить шаг ${step + 1}"
                            Stage.VALIDATION -> "проверить результат"
                            Stage.DONE -> "задача завершена"
                        }
                    )
                } catch (e: Exception) {
                    println("⚠️ Не удалось загрузить состояние задачи: ${e.message}")
                }

                println("✅ История загружена из $filePath")
                println("👤 Профиль: $name")
                println("📋 Инвариантов загружено: ${invariantManager.getAllInvariants().size}")
                println("📊 Состояние задачи: ${taskState.state.stage}")
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
            val violations = loadedMessages
                .flatMap { invariantManager.checkTextForViolations(it.content) }

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
    fun getTransitionHistory(): List<TransitionRecord> = taskState.getTransitionHistory()
    fun getSwarmStatus(): String = orchestrator?.getAgentStatus() ?: "Рой не активирован"

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

    fun clearViolationHistory() {
        invariantManager.clearViolationHistory()
    }

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

    suspend fun processNaturalLanguage(userMessage: String): DeepSeekResponse {
        return sendMessageAsync(userMessage)
    }

    suspend fun processNaturalLanguageWithStats(userMessage: String): Pair<String, DeepSeekResponse> {
        val response = sendMessageAsync(userMessage)
        return response.content to response
    }

    fun shutdown() {
        scope.cancel()
        orchestrator?.shutdown()
        messageChannel.close()
    }
}