import models.*
import kotlinx.coroutines.*

class AgentOrchestrator(
    private val llm: DeepSeekClient,
    private val taskStateMachine: TaskStateMachine,
    private val mcpClientManager: McpClientManager? = null
) {
    private val agents = mutableMapOf<String, Agent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var pendingQuestion: String? = null
    private var pendingPlan: String? = null
    private var mcpReActEngine: McpReActEngine? = null

    init {
        registerAgents()
        if (mcpClientManager != null) {
            mcpReActEngine = McpReActEngine(llm, mcpClientManager)
            println("⚙️ AgentOrchestrator: McpReActEngine инициализирован")
        }
    }

    private fun registerAgents() {
        val mcpToolsDescription = if (mcpClientManager != null) {
            val tools = mcpClientManager.listAgentTools()
            if (tools.isNotEmpty()) {
                "Доступные MCP-инструменты:\n" +
                        tools.joinToString("\n") { tool ->
                            val params = tool.inputSchema()?.get("properties") as? Map<*, *>
                            val paramsDesc = params?.keys?.joinToString(", ") ?: "нет параметров"
                            "  - ${tool.name()}: ${tool.description()} (параметры: $paramsDesc)"
                        }
            } else ""
        } else ""

        registerAgent(
            Agent(
                id = "planner-001",
                role = AgentRole.PLANNER,
                name = "Планировщик",
                systemPrompt = """
                    Ты - Планировщик. Твоя задача - создавать планы для ОБУЧЕНИЯ и ПОНИМАНИЯ.
                    
                    ВАЖНО: Ты работаешь через API и НЕ МОЖЕШЬ создавать файлы или выполнять код.
                    Твоя задача - давать ПОШАГОВЫЕ ОБЪЯСНЕНИЯ и ПРИМЕРЫ КОДА.
                    
                    Формат плана:
                    План: [описание цели]
                    
                    Шаг 1: [объяснение концепции]
                    Пример кода: [код]
                    
                    Шаг 2: [объяснение следующего шага]
                    Пример кода: [код]
                    
                    ...
                    
                    Критерии успеха: [что должен понять пользователь]
                    
                    Если информации недостаточно - задай уточняющие вопросы.
                    Не давай инструкций, которые требуют физического доступа к файлам.
                    
                    $mcpToolsDescription
                    
                    Если для выполнения задачи нужны данные из внешних источников, ты можешь включить в план шаги вида:
                    MCP: toolName с аргументами {key: value, ...}
                    Например: MCP: get-current-weather с аргументами {city: London}
                    Эти шаги будут выполнены специальным исполнителем, который вернёт результат.
                """.trimIndent(),
                capabilities = listOf("planning", "analysis", "decomposition", "teaching")
            )
        )

        registerAgent(
            Agent(
                id = "executor-001",
                role = AgentRole.EXECUTOR,
                name = "Исполнитель",
                systemPrompt = """
                    Ты - Исполнитель. Твоя задача - ПОКАЗЫВАТЬ примеры и ОБЪЯСНЯТЬ.
                    
                    ВАЖНО: Ты работаешь через API и НЕ МОЖЕШЬ создавать файлы или выполнять код.
                    
                    Твоя задача:
                    1. Давать готовые примеры кода
                    2. Объяснять, как работает код
                    3. Показывать, что должно получиться
                    4. Объяснять архитектуру и концепции
                    5. Спрашивать, понял ли пользователь
                    
                    НЕЛЬЗЯ:
                    - Давать инструкции "создай файл"
                    - Говорить "установи программу"
                    - Требовать физических действий от пользователя
                    
                    Ты - ОБУЧАЮЩИЙ ассистент. Дай пользователю понимание и примеры.
                    
                    $mcpToolsDescription
                    
                    Если в плане есть шаг вида "MCP: toolName с аргументами {...}", то ты должен выполнить этот вызов, используя доступные MCP-инструменты, и результат использовать в ответе.
                """.trimIndent(),
                capabilities = listOf("explanation", "code_examples", "teaching", "architecture")
            )
        )

        registerAgent(
            Agent(
                id = "validator-001",
                role = AgentRole.VALIDATOR,
                name = "Валидатор",
                systemPrompt = """
                    Ты - Валидатор. Твоя задача - проверять, понял ли пользователь материал.
                    
                    Ты проверяешь:
                    1. Понял ли пользователь концепцию
                    2. Может ли он использовать примеры
                    3. Есть ли у него вопросы
                    
                    Если пользователь подтверждает понимание - напиши "✅ Понимание подтверждено"
                    Если пользователь говорит, что не понял - задай уточняющие вопросы.
                """.trimIndent(),
                capabilities = listOf("validation", "teaching_assessment")
            )
        )

        registerAgent(
            Agent(
                id = "coordinator-001",
                role = AgentRole.COORDINATOR,
                name = "Координатор",
                systemPrompt = """
                    Ты - Координатор. Твоя задача - управлять процессом обучения.
                    
                    Правила:
                    1. Если пользователь хочет что-то изучить - передай Планировщику
                    2. Если пользователь спрашивает конкретный вопрос - ответь сам
                    3. Если пользователь подтвердил план - передай Исполнителю
                    4. Если пользователь говорит "да, понял" - передай Валидатору
                    5. Если пользователь говорит "не понял" - верни к Исполнителю
                    
                    $mcpToolsDescription
                    
                    Если пользователь просит найти информацию в интернете или показать погоду, ты можешь сразу передать запрос Планировщику, чтобы он создал план с MCP-вызовами.
                """.trimIndent(),
                capabilities = listOf("coordination", "decision_making", "context_understanding")
            )
        )
    }

    private fun registerAgent(agent: Agent) {
        if (agents.containsKey(agent.id)) {
            println("⚠️ Агент с ID ${agent.id} уже существует")
            return
        }
        agents[agent.id] = agent
        println("✅ Зарегистрирован агент: ${agent.name} (${agent.role})")
    }

    suspend fun processUserRequest(request: String): String {
        return withContext(Dispatchers.IO) {
            try {
                when (request.lowercase()) {
                    "пауза", "pause" -> {
                        taskStateMachine.pause()
                        return@withContext buildString {
                            appendLine("⏸️ Задача приостановлена.")
                            appendLine()
                            appendLine(taskStateMachine.getCurrentStepInfo())
                            appendLine()
                            appendLine("👉 Напишите 'resume' или 'продолжить' для возобновления.")
                        }
                    }
                    "resume", "продолжить", "продолжи" -> {
                        taskStateMachine.resume()
                        return@withContext buildString {
                            appendLine("▶️ Задача возобновлена.")
                            appendLine()
                            appendLine(taskStateMachine.getCurrentStepInfo())
                            appendLine()
                            if (taskStateMachine.state.stage == Stage.VALIDATION) {
                                appendLine("👉 Понятно ли вам? Напишите 'да, понял' или 'нет, не понял'")
                            } else if (taskStateMachine.state.stage == Stage.EXECUTION) {
                                appendLine("👉 Напишите 'дальше' для продолжения обучения")
                            } else {
                                appendLine("👉 Напишите, что хотите изучить дальше")
                            }
                        }
                    }
                }

                if (taskStateMachine.state.isPaused) {
                    return@withContext buildString {
                        appendLine("⏸️ Задача приостановлена.")
                        appendLine()
                        appendLine("Напишите 'resume' или 'продолжить' для возобновления.")
                        appendLine()
                        appendLine("📊 Текущее состояние:")
                        appendLine(taskStateMachine.getCurrentStepInfo())
                    }
                }

                if (pendingPlan != null && isConfirmationRequest(request)) {
                    return@withContext handlePlanConfirmation(request)
                }

                if (pendingQuestion != null) {
                    if (!isCommand(request)) {
                        return@withContext handlePlanning(request)
                    }
                }

                val isEducational = isEducationalRequest(request)
                val isInfo = isInformationRequest(request)

                if (isEducational) {
                    val currentStage = taskStateMachine.state.stage
                    val hasPlan = taskStateMachine.state.plan.isNotEmpty()
                    if ((currentStage == Stage.INIT || currentStage == Stage.PLANNING) && !hasPlan) {
                        return@withContext handlePlanning(request)
                    }
                    if (currentStage == Stage.EXECUTION || currentStage == Stage.PLAN_APPROVED) {
                        if (isExecutionRequest(request)) {
                            return@withContext handleExecution(request)
                        }
                    }
                    if (currentStage == Stage.VALIDATION) {
                        return@withContext handleValidation(request)
                    }
                    if (currentStage == Stage.DONE) {
                        return@withContext buildString {
                            appendLine("✅ Обучение завершено!")
                            appendLine()
                            appendLine("📚 Что еще хотите изучить? Просто напишите новую тему.")
                        }
                    }
                }

                if (isInfo) {
                    return@withContext executeInformationRequest(request)
                }

                if (isStatusRequest(request)) {
                    return@withContext taskStateMachine.getCurrentStepInfo()
                }

                if (isHelpRequest(request)) {
                    return@withContext getHelpMessage()
                }

                if (isResetRequest(request)) {
                    taskStateMachine.reset()
                    conversationHistory.clear()
                    pendingQuestion = null
                    pendingPlan = null
                    return@withContext buildString {
                        appendLine("🔄 Состояние сброшено.")
                        appendLine()
                        appendLine("📚 Готов изучать новую тему!")
                        appendLine()
                        appendLine("Напишите, что хотите изучить:")
                        appendLine("  • 'Объясни Kotlin Coroutines'")
                        appendLine("  • 'Расскажи про Flow'")
                        appendLine("  • 'Как работают Data Class'")
                        appendLine("  • 'Что такое sealed class'")
                    }
                }

                if (isCommand(request)) {
                    return@withContext "Неизвестная команда. Напишите 'помощь' для списка команд."
                }

                return@withContext processFlexible(request)

            } catch (e: Exception) {
                "❌ Ошибка: ${e.message}"
            }
        }
    }

    private fun isEducationalRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("объясни") || lower.contains("расскажи") ||
                lower.contains("научи") || lower.contains("покажи") && lower.contains("как") ||
                lower.contains("что такое") || lower.contains("изучить") ||
                lower.contains("разобраться") || lower.contains("что значит") ||
                lower.contains("пример") || lower.contains("код") || lower.contains("алгоритм")
    }

    private fun isInformationRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("погод") || lower.contains("weather") ||
                lower.contains("найди") || lower.contains("поищи") ||
                lower.contains("search") || lower.contains("интернет") ||
                lower.contains("курс") || lower.contains("rate") ||
                lower.contains("новост") || lower.contains("news")
    }

    private suspend fun executeInformationRequest(request: String): String {
        val engine = mcpReActEngine ?: return "❌ MCP-инструменты недоступны."

        val result = engine.execute(
            systemPrompt = "Ты — информационный ассистент. Получи актуальные данные через MCP-инструменты и ответь пользователю.",
            userMessage = request,
            requireTools = true
        )

        val trace = engine.buildToolTrace(result.toolCalls)
        return result.content + trace
    }

    private suspend fun processFlexible(request: String): String {
        val engine = mcpReActEngine ?: return "❌ MCP-инструменты недоступны"

        val planTool = ToolSchema(
            name = "plan",
            description = "Создать структурированный план для выполнения сложного запроса, который требует нескольких шагов, обучения или анализа.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "plan_text" to mapOf(
                        "type" to "string",
                        "description" to "Текст плана в формате: План: ... \nШаг 1: ... \nШаг 2: ... \nКритерии успеха: ..."
                    )
                ),
                "required" to listOf("plan_text")
            )
        )

        val systemPrompt = """
            Ты — интеллектуальный ассистент с доступом к MCP-инструментам.
            Если запрос можно выполнить одним или двумя вызовами инструментов — сделай это и ответь.
            Если запрос сложный, требует обучения, анализа или нескольких шагов — вызови инструмент 'plan' с описанием плана.
            План должен содержать шаги, некоторые из которых могут быть MCP-вызовами в формате:
            MCP: toolName с аргументами {key: value, ...}
            После выполнения плана ты получишь результаты и сможешь ответить.
            Ответь пользователю понятно и структурированно.
        """.trimIndent()

        val result = engine.execute(
            systemPrompt = systemPrompt,
            userMessage = request,
            additionalTools = listOf(planTool),
            requireTools = true
        )

        // Проверяем, был ли создан план через виртуальный tool
        val planCall = result.toolCalls.find { it.toolName == "plan" }
        if (planCall != null) {
            val planText = planCall.arguments["plan_text"]?.toString() ?: ""
            if (planText.isNotBlank()) {
                taskStateMachine.state = taskStateMachine.state.copy(plan = planText)
                taskStateMachine.transitionTo(Stage.PLAN_APPROVED, "Автоматический план")
                taskStateMachine.transitionTo(Stage.EXECUTION, "Автоматическое выполнение")
                val steps = planText.split("Шаг").size - 1
                taskStateMachine.setTotalSteps(maxOf(steps, 1))
                return executeAllSteps()
            }
        }

        val trace = engine.buildToolTrace(result.toolCalls)
        return result.content + trace
    }

    private suspend fun executeAllSteps(): String {
        val totalSteps = taskStateMachine.state.totalSteps
        val results = mutableListOf<String>()
        val stepContext = mutableMapOf<Int, String>() // контекст между шагами

        while (taskStateMachine.state.currentStep < totalSteps) {
            val stepNumber = taskStateMachine.state.currentStep + 1
            val stepDescription = getStepDescription(taskStateMachine.state.plan, stepNumber)

            val executor = agents["executor-001"] ?: return "❌ Исполнитель не найден"

            // Собираем контекст из предыдущих MCP-шагов
            val previousMcpResults = stepContext.filterKeys { it < stepNumber }
                .map { (step, result) -> "Результат шага $step: $result" }
                .joinToString("\n")

            val stepResult = if (stepDescription.startsWith("MCP:")) {
                val mcpResult = executeMcpStep(stepDescription)
                stepContext[stepNumber] = mcpResult
                mcpResult
            } else {
                val contextBlock = if (previousMcpResults.isNotBlank()) {
                    "\n\nКонтекст из предыдущих шагов:\n$previousMcpResults"
                } else ""

                val response = llm.ask(
                    listOf(
                        Message("system", executor.systemPrompt),
                        Message("user", """
                            Выполни шаг $stepNumber из $totalSteps.
                            
                            Описание шага: $stepDescription
                            
                            План: ${taskStateMachine.state.plan}
                            $contextBlock
                            
                            Дай понятное объяснение с примерами, если нужно.
                            Если шаг уже выполнен (через MCP), используй результат.
                        """.trimIndent())
                    )
                )
                response.content
            }

            taskStateMachine.addStepResult(stepResult)
            taskStateMachine.executeNextStep()
            results.add("📚 Шаг $stepNumber:\n$stepResult")
        }

        taskStateMachine.transitionTo(Stage.VALIDATION, "План выполнен")
        return buildString {
            appendLine("✅ План выполнен!")
            appendLine()
            results.forEach { appendLine(it) }
            appendLine()
            appendLine("👉 Если всё понятно, напишите 'да, понял' для завершения.")
        }
    }

    private suspend fun executeMcpStep(stepDescription: String): String {
        if (mcpClientManager == null) {
            return "❌ MCP-инструменты недоступны"
        }

        val pattern = Regex("""MCP:\s*(\S+)\s+с\s+аргументами\s*\{([^}]*)\}""", RegexOption.IGNORE_CASE)
        val match = pattern.find(stepDescription)
        if (match == null) {
            return "❌ Не удалось распарсить MCP-шаг: $stepDescription"
        }

        val toolName = match.groupValues[1]
        val argsString = match.groupValues[2].trim()
        val args = if (argsString.isNotEmpty()) {
            argsString.split(",").mapNotNull { pair ->
                val parts = pair.split(":").map { it.trim() }
                if (parts.size == 2) parts[0] to parts[1] as Any? else null
            }.toMap()
        } else emptyMap()

        return try {
            val result = mcpClientManager.callTool(toolName, args)
            if (result.startsWith("Error:")) {
                "❌ Ошибка при вызове $toolName: $result"
            } else {
                result
            }
        } catch (e: Exception) {
            "❌ Исключение при вызове $toolName: ${e.message}"
        }
    }

    private fun isConfirmationRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower == "да" || lower == "д" ||
                lower == "yes" || lower == "y" ||
                lower == "утверждаю" || lower == "подтверждаю" ||
                lower == "ок" || lower == "ok" ||
                lower == "хорошо" || lower == "ага" ||
                lower == "конечно" || lower == "согласен" ||
                lower == "понятно" || lower == "окей"
    }

    private fun isRejectionRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower == "нет" || lower == "н" ||
                lower == "no" || lower == "n" ||
                lower == "отмена" || lower == "не надо" ||
                lower == "план плохой" || lower == "переделай" ||
                lower == "не подходит" || lower == "измени" ||
                lower == "не понял"
    }

    private fun isCommand(request: String): Boolean {
        val lower = request.lowercase()
        return lower == "статус" || lower == "status" ||
                lower == "помощь" || lower == "help" ||
                lower == "новая задача" || lower == "reset" ||
                lower == "сброс" || lower == "clear" ||
                lower == "stats" || lower == "swarm status"
    }

    private fun isExecutionRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("шаг") || lower.contains("step") ||
                lower.contains("дальше") || lower.contains("next") ||
                lower.contains("продолжи") || lower.contains("еще") ||
                lower.contains("следующий") || lower.contains("continue")
    }

    private fun isStatusRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("статус") || lower.contains("status") ||
                lower.contains("состояние") || lower.contains("прогресс")
    }

    private fun isHelpRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("помощь") || lower.contains("help") ||
                lower.contains("команды") || lower.contains("что умеешь") ||
                lower.contains("подскажи")
    }

    private fun isResetRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("новая задача") || lower.contains("reset") ||
                lower.contains("сброс") || lower.contains("очистить") ||
                lower.contains("начать сначала")
    }

    private suspend fun handlePlanning(request: String): String {
        val planner = agents["planner-001"]
            ?: return "❌ Планировщик не найден"

        val historyContext = buildHistoryContext()
        val questionContext = if (pendingQuestion != null) {
            "\nПользователь отвечает на вопрос: ${pendingQuestion}"
        } else ""

        val response = llm.ask(
            listOf(
                Message("system", planner.systemPrompt),
                Message("user", """
                    Создай обучающий план для запроса пользователя: "$request"
                    
                    История диалога:
                    $historyContext
                    $questionContext
                    
                    Помни: ты работаешь через API, НЕ МОЖЕШЬ создавать файлы или выполнять код.
                    Твоя задача - ДАТЬ ПОНИМАНИЕ и ПРИМЕРЫ.
                    
                    Если информации недостаточно - задай уточняющие вопросы.
                    Если информации достаточно - предоставь обучающий план в формате:
                    План: [чему научимся]
                    Шаг 1: [концепция] + [пример кода]
                    Шаг 2: [концепция] + [пример кода]
                    ...
                    Критерии успеха: [что должен понять пользователь]
                    
                    Если для выполнения задачи нужны данные из внешних источников (погода, поиск в интернете), ты можешь включить шаги вида:
                    MCP: toolName с аргументами {key: value, ...}
                    Например: MCP: get-current-weather с аргументами {city: London}
                    Эти шаги будут выполнены специальным исполнителем.
                """.trimIndent())
            )
        )

        val plan = extractPlan(response.content)
        if (plan.isNotEmpty()) {
            pendingQuestion = null
            pendingPlan = plan

            return buildString {
                appendLine("📋 Я подготовил обучающий план:")
                appendLine()
                appendLine(plan)
                appendLine()
                appendLine("✅ План готов! Подтвердите, чтобы начать обучение.")
                appendLine()
                appendLine("Напишите 'да' для подтверждения или 'нет' для отклонения.")
            }
        }

        if (response.content.contains("?")) {
            pendingQuestion = response.content
        }

        conversationHistory.add(request to response.content)
        return "🤔 Планировщик анализирует запрос:\n\n${response.content}"
    }

    private suspend fun handlePlanConfirmation(request: String): String {
        if (pendingPlan == null) {
            return "⚠️ Нет плана для подтверждения. Сначала создайте план."
        }

        if (isRejectionRequest(request)) {
            val plan = pendingPlan
            pendingPlan = null
            return buildString {
                appendLine("❌ План отклонен:")
                appendLine()
                appendLine(plan)
                appendLine()
                appendLine("💡 Напишите, что нужно изменить, или создайте новый запрос.")
            }
        }

        val plan = pendingPlan!!
        pendingPlan = null

        taskStateMachine.state = taskStateMachine.state.copy(plan = plan)
        taskStateMachine.transitionTo(Stage.PLAN_APPROVED, "План утвержден")
        taskStateMachine.transitionTo(Stage.EXECUTION, "Начинаем обучение")

        val steps = plan.split("Шаг").size - 1
        taskStateMachine.setTotalSteps(maxOf(steps, 1))

        return executeAllSteps()
    }

    private suspend fun handleExecution(request: String): String {
        if (taskStateMachine.state.isPaused) {
            return buildString {
                appendLine("⏸️ Задача приостановлена.")
                appendLine()
                appendLine("Напишите 'resume' или 'продолжить' для возобновления.")
                appendLine()
                appendLine("📊 Текущее состояние:")
                appendLine(taskStateMachine.getCurrentStepInfo())
            }
        }

        if (taskStateMachine.state.stage != Stage.EXECUTION) {
            return when (taskStateMachine.state.stage) {
                Stage.INIT, Stage.PLANNING ->
                    buildString {
                        appendLine("📝 Сначала создайте план.")
                        appendLine()
                        appendLine("Напишите, что хотите изучить:")
                    }
                Stage.PLAN_APPROVED -> {
                    taskStateMachine.transitionTo(Stage.EXECUTION, "Начинаем обучение")
                    buildString {
                        appendLine("✅ Перехожу к обучению.")
                        appendLine()
                        appendLine(taskStateMachine.getCurrentStepInfo())
                        appendLine()
                        appendLine("👉 Напишите 'дальше' для первого шага")
                    }
                }
                Stage.VALIDATION ->
                    buildString {
                        appendLine("📚 Все шаги пройдены!")
                        appendLine()
                        appendLine("👉 Понятно ли вам? Напишите 'да, понял' или 'нет, не понял'")
                    }
                Stage.DONE ->
                    buildString {
                        appendLine("✅ Обучение уже завершено!")
                        appendLine()
                        appendLine("📚 Что еще хотите изучить?")
                    }
                else -> "⚠️ Неизвестное состояние"
            }
        }

        if (taskStateMachine.state.currentStep >= taskStateMachine.state.totalSteps) {
            taskStateMachine.transitionTo(Stage.VALIDATION, "Все шаги пройдены")
            return buildString {
                appendLine("📚 Все шаги пройдены!")
                appendLine()
                appendLine("👉 Понятно ли вам? Напишите 'да, понял' или 'нет, не понял'")
            }
        }

        val expectedStep = taskStateMachine.state.currentStep + 1
        val requestedStep = extractStepNumber(request)

        if (requestedStep != null && requestedStep != expectedStep) {
            return buildString {
                appendLine("❌ Нельзя выполнить шаг $requestedStep, так как следующий шаг - $expectedStep.")
                appendLine()
                appendLine("Выполняйте шаги последовательно!")
                appendLine()
                appendLine(taskStateMachine.getCurrentStepInfo())
                appendLine()
                appendLine("👉 Напишите 'дальше' для выполнения следующего шага.")
            }
        }

        val stepNumber = expectedStep
        val totalSteps = taskStateMachine.state.totalSteps
        val stepDescription = getStepDescription(taskStateMachine.state.plan, stepNumber)

        val executor = agents["executor-001"]
            ?: return "❌ Исполнитель не найден"

        val stepResult = if (stepDescription.startsWith("MCP:")) {
            executeMcpStep(stepDescription)
        } else {
            val response = llm.ask(
                listOf(
                    Message("system", executor.systemPrompt),
                    Message("user", """
                        Объясни шаг $stepNumber из $totalSteps.
                        
                        Описание шага: $stepDescription
                        
                        План: ${taskStateMachine.state.plan}
                        
                        Дай понятное объяснение с примерами кода.
                        Помни: ты работаешь через API, даешь знания и примеры.
                        Не давай инструкций по установке или созданию файлов.
                    """.trimIndent())
                )
            )
            response.content
        }

        taskStateMachine.addStepResult(stepResult)
        taskStateMachine.executeNextStep()

        val result = buildString {
            appendLine("📚 Шаг $stepNumber:")
            appendLine()
            appendLine(stepResult)
            appendLine()
            appendLine(taskStateMachine.getCurrentStepInfo())

            if (taskStateMachine.state.stage == Stage.VALIDATION) {
                appendLine()
                appendLine("📚 Все шаги пройдены!")
                appendLine()
                appendLine("👉 Понятно ли вам? Напишите 'да, понял' или 'нет, не понял'")
            } else {
                val nextStep = taskStateMachine.state.currentStep + 1
                appendLine()
                appendLine("👉 Следующий шаг: $nextStep")
                appendLine("👉 Напишите 'дальше' для продолжения")
            }
        }

        conversationHistory.add(request to result)
        return result
    }

    private suspend fun handleValidation(request: String): String {
        if (taskStateMachine.state.isPaused) {
            return buildString {
                appendLine("⏸️ Задача приостановлена.")
                appendLine()
                appendLine("Напишите 'resume' или 'продолжить' для возобновления.")
                appendLine()
                appendLine("📊 Текущее состояние:")
                appendLine(taskStateMachine.getCurrentStepInfo())
            }
        }

        if (taskStateMachine.state.stage != Stage.VALIDATION) {
            return when (taskStateMachine.state.stage) {
                Stage.EXECUTION -> {
                    val remaining = taskStateMachine.state.totalSteps - taskStateMachine.state.currentStep
                    buildString {
                        appendLine("📚 Еще не все шаги пройдены. Осталось: $remaining")
                        appendLine()
                        appendLine("👉 Продолжайте обучение: напишите 'дальше'")
                    }
                }
                Stage.DONE ->
                    buildString {
                        appendLine("✅ Обучение уже завершено!")
                        appendLine()
                        appendLine("📚 Что еще хотите изучить?")
                    }
                else ->
                    buildString {
                        appendLine("⚠️ Сначала завершите все шаги обучения.")
                        appendLine()
                        appendLine("👉 Напишите 'дальше' для продолжения")
                    }
            }
        }

        val isConfirmation = request.lowercase().contains("понял") ||
                request.lowercase().contains("yes") ||
                request.lowercase().contains("да") ||
                request.lowercase().contains("ясно") ||
                request.lowercase().contains("ок") ||
                request.lowercase().contains("ok") ||
                request.lowercase().contains("всё") ||
                request.lowercase().contains("все")

        val isRejection = request.lowercase().contains("не понял") ||
                request.lowercase().contains("no") ||
                request.lowercase().contains("нет")

        if (isConfirmation) {
            taskStateMachine.validate("Понимание подтверждено")
            val finalResult = buildString {
                appendLine("✅ Отлично! Понимание подтверждено!")
                appendLine()
                appendLine("🎉 Обучение завершено успешно!")
                appendLine()
                appendLine("📚 Что еще хотите изучить? Просто напишите новую тему.")
            }
            conversationHistory.add(request to finalResult)
            return finalResult
        }

        if (isRejection) {
            return buildString {
                appendLine("⚠️ Нужно пояснить материал подробнее.")
                appendLine()
                appendLine("💡 Какая часть осталась непонятной?")
                appendLine()
                appendLine("Напишите конкретный вопрос или попросите ещё раз объяснить шаг.")
            }
        }

        if (request.lowercase().contains("вопрос") || request.contains("?")) {
            val coordinator = agents["coordinator-001"] ?: return "❌ Координатор не найден"
            val historyContext = buildHistoryContext()
            val response = llm.ask(
                listOf(
                    Message("system", coordinator.systemPrompt),
                    Message("user", """
                        Пользователь задал вопрос: "$request"
                        Контекст: обучение завершено, шаги пройдены.
                        Ответь на вопрос, используя ранее объяснённый материал.
                        История: $historyContext
                    """.trimIndent())
                )
            )
            return response.content
        }

        return buildString {
            appendLine("📚 Все шаги пройдены!")
            appendLine()
            appendLine("👉 Понятно ли вам? Напишите 'да, понял' или 'нет, не понял'")
            appendLine()
            appendLine("📊 Текущее состояние:")
            appendLine(taskStateMachine.getCurrentStepInfo())
        }
    }

    private fun buildHistoryContext(): String {
        if (conversationHistory.isEmpty()) {
            return "История пуста"
        }
        return conversationHistory.takeLast(5).joinToString("\n") {
            "Пользователь: ${it.first}\nАссистент: ${it.second.take(100)}..."
        }
    }

    private fun getStepDescription(plan: String, stepNumber: Int): String {
        val lines = plan.lines()
        for (line in lines) {
            if (line.contains("Шаг $stepNumber:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return "Шаг $stepNumber"
    }

    private fun extractStepNumber(request: String): Int? {
        val patterns = listOf(
            Regex("""шаг\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""step\s*(\d+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(request)
            val number = match?.groupValues?.get(1)?.toIntOrNull()
            if (number != null) {
                return number
            }
        }
        return null
    }

    private fun extractPlan(text: String): String {
        val lines = text.lines()
        val planLines = mutableListOf<String>()
        var inPlan = false
        var hasPlanHeader = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("План:", ignoreCase = true)) {
                inPlan = true
                hasPlanHeader = true
                planLines.add(trimmed)
            } else if (inPlan && trimmed.isNotEmpty()) {
                planLines.add(trimmed)
            } else if (inPlan && trimmed.isEmpty() && hasPlanHeader) {
                val nextLine = if (lines.indexOf(line) + 1 < lines.size)
                    lines[lines.indexOf(line) + 1].trim() else ""
                if (!nextLine.contains("Шаг", ignoreCase = true) &&
                    !nextLine.contains("Критерии", ignoreCase = true)) {
                    break
                }
            }
        }

        if (!hasPlanHeader) {
            val steps = lines.filter {
                it.trim().contains("Шаг", ignoreCase = true) &&
                        it.trim().contains(":")
            }
            if (steps.isNotEmpty()) {
                return buildString {
                    appendLine("План: ${text.lines().firstOrNull() ?: "Изучение темы"}")
                    steps.forEach { appendLine(it.trim()) }
                }
            }
        }

        return if (planLines.isNotEmpty()) planLines.joinToString("\n") else ""
    }

    private fun getHelpMessage(): String {
        return """
            🤖 Я - ОБУЧАЮЩИЙ АССИСТЕНТ
            
            📚 ЧТО Я УМЕЮ:
            - Объяснять концепции программирования
            - Показывать примеры кода
            - Давать пошаговые инструкции
            - Проверять понимание материала
            
            📝 КАКИЕ ЗАПРОСЫ РАБОТАЮТ ЛУЧШЕ ВСЕГО:
            ✅ "Объясни, как работает [тема]"
            ✅ "Расскажи про [технология] с примерами"
            ✅ "Научи меня [тема] с нуля"
            ✅ "Что такое [концепция] в Kotlin"
            
            ⚠️ ЧЕГО Я НЕ МОГУ:
            ❌ Создавать файлы на вашем компьютере
            ❌ Устанавливать программы
            ❌ Выполнять код
            ❌ Деплоить приложения
            
            💡 ПРОСТО ОБЩАЙТЕСЬ ЕСТЕСТВЕННО!
            Я сам пойму, что вам нужно объяснить.
            
            📊 ТЕКУЩЕЕ СОСТОЯНИЕ:
            ${taskStateMachine.getCurrentStepInfo()}
            
            👉 Напишите 'дальше' для продолжения обучения
            👉 Или задайте новый вопрос
        """.trimIndent()
    }

    fun getAgentStatus(): String {
        return buildString {
            appendLine("---- СТАТУС АГЕНТОВ ----")
            agents.forEach { (_, agent) ->
                appendLine("${agent.name}: ${if (agent.isActive) "🟢 Активен" else "🔴 Неактивен"}")
                appendLine("  Роль: ${agent.role}")
                appendLine("  Возможности: ${agent.capabilities.joinToString(", ")}")
            }
            appendLine()
            appendLine("📊 СОСТОЯНИЕ ЗАДАЧИ:")
            appendLine(taskStateMachine.getCurrentStepInfo())
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}