import models.*
import kotlinx.coroutines.*

class AgentOrchestrator(
    private val llm: DeepSeekClient,
    private val taskStateMachine: TaskStateMachine
) {
    private val agents = mutableMapOf<String, Agent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var pendingQuestion: String? = null
    private var pendingPlan: String? = null

    init {
        registerAgents()
    }

    private fun registerAgents() {
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
                // ===== ПЕРВАЯ ПРОВЕРКА: команды управления задачей =====
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

                // Проверяем, не приостановлена ли задача
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

                val currentStage = taskStateMachine.state.stage
                val hasPlan = taskStateMachine.state.plan.isNotEmpty()

                // Проверяем подтверждение плана
                if (pendingPlan != null && isConfirmationRequest(request)) {
                    return@withContext handlePlanConfirmation(request)
                }

                // Если есть ожидающий вопрос и пользователь ответил
                if (pendingQuestion != null && !hasPlan) {
                    if (!isCommand(request)) {
                        return@withContext handlePlanning(request)
                    }
                }

                // 1. Если нет плана - вызываем Планировщика
                if ((currentStage == Stage.INIT || currentStage == Stage.PLANNING) && !hasPlan) {
                    if (isPlanningRequest(request) || isCommand(request)) {
                        return@withContext handlePlanning(request)
                    }
                }

                // 2. Если есть план и мы в EXECUTION или PLAN_APPROVED
                if (currentStage == Stage.EXECUTION || currentStage == Stage.PLAN_APPROVED) {
                    if (isExecutionRequest(request)) {
                        return@withContext handleExecution(request)
                    }
                }

                // 3. Если мы в VALIDATION
                if (currentStage == Stage.VALIDATION) {
                    return@withContext handleValidation(request)
                }

                // 4. Если задача завершена
                if (currentStage == Stage.DONE) {
                    return@withContext buildString {
                        appendLine("✅ Обучение завершено!")
                        appendLine()
                        appendLine("📚 Что еще хотите изучить? Просто напишите новую тему.")
                        appendLine()
                        appendLine("Например:")
                        appendLine("  • 'Расскажи про корутины в Kotlin'")
                        appendLine("  • 'Объясни sealed class с примерами'")
                        appendLine("  • 'Как работает Flow в Kotlin'")
                        appendLine("  • 'Что такое Data Class'")
                    }
                }

                // 5. Запрос статуса
                if (isStatusRequest(request)) {
                    return@withContext taskStateMachine.getCurrentStepInfo()
                }

                // 6. Запрос помощи
                if (isHelpRequest(request)) {
                    return@withContext getHelpMessage()
                }

                // 7. Сброс
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

                // 8. Если есть ожидающий вопрос - передаем Планировщику
                if (pendingQuestion != null) {
                    return@withContext handlePlanning(request)
                }

                // 9. Общий запрос
                return@withContext handleGeneralQuery(request)

            } catch (e: Exception) {
                "❌ Ошибка: ${e.message}"
            }
        }
    }

    private fun isConfirmationRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower == "да" || lower == "д" ||
                lower == "yes" || lower == "y" ||
                lower == "утверждаю" || lower == "подтверждаю" ||
                lower == "начинай" || lower == "выполняй" ||
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

    private fun isPlanningRequest(request: String): Boolean {
        val lower = request.lowercase()
        return lower.contains("объясни") || lower.contains("расскажи") ||
                lower.contains("научи") || lower.contains("покажи") ||
                lower.contains("хочу") || lower.contains("как") ||
                lower.contains("что такое") || lower.contains("изучить") ||
                lower.contains("разобраться") || lower.contains("что значит")
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
                appendLine("Напишите:")
                appendLine("  • 'да' - для пошагового обучения")
                appendLine("  • 'да, все сразу' - для полного объяснения за раз")
                appendLine("  • 'нет' - для отклонения плана")
                appendLine("  • 'измени' - чтобы изменить план")
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
                appendLine()
                appendLine("Например:")
                appendLine("  • 'Измени план, сделай проще'")
                appendLine("  • 'Добавь больше примеров кода'")
                appendLine("  • 'Объясни иначе'")
            }
        }

        val plan = pendingPlan!!
        pendingPlan = null

        val stage = taskStateMachine.state.stage

        if (stage == Stage.INIT || stage == Stage.PLANNING) {
            if (stage == Stage.INIT) {
                taskStateMachine.transitionTo(Stage.PLANNING, "Начинаем планирование")
            }

            taskStateMachine.state = taskStateMachine.state.copy(plan = plan)
            taskStateMachine.transitionTo(Stage.PLAN_APPROVED, "План утвержден")
            taskStateMachine.transitionTo(Stage.EXECUTION, "Начинаем обучение")

            val steps = plan.split("Шаг").size - 1
            taskStateMachine.setTotalSteps(maxOf(steps, 1))
        }

        // Проверяем, нужно ли автоматическое выполнение
        val isAutoMode = request.lowercase().contains("все сразу") ||
                request.lowercase().contains("полностью") ||
                request.lowercase().contains("за раз") ||
                request.lowercase().contains("сразу всё")

        if (isAutoMode) {
            return executeAllSteps()
        }

        return buildString {
            appendLine("✅ План утвержден! Начинаю обучение.")
            appendLine()
            appendLine(taskStateMachine.getCurrentStepInfo())
            appendLine()
            appendLine("👉 Напишите 'дальше' для следующего шага")
            appendLine("👉 Или 'все сразу' для полного объяснения")
            appendLine("👉 Или задайте уточняющий вопрос по теме")
        }
    }

    private suspend fun executeAllSteps(): String {
        val totalSteps = taskStateMachine.state.totalSteps
        val results = mutableListOf<String>()

        while (taskStateMachine.state.currentStep < totalSteps) {
            val stepNumber = taskStateMachine.state.currentStep + 1
            val stepDescription = getStepDescription(taskStateMachine.state.plan, stepNumber)

            val executor = agents["executor-001"] ?: return "❌ Исполнитель не найден"

            val response = llm.ask(
                listOf(
                    Message("system", executor.systemPrompt),
                    Message("user", """
                        Объясни шаг $stepNumber из $totalSteps.
                        
                        Описание шага: $stepDescription
                        
                        План: ${taskStateMachine.state.plan}
                        
                        Дай понятное объяснение с примерами кода.
                        Помни: ты работаешь через API, даешь знания и примеры.
                    """.trimIndent())
                )
            )

            taskStateMachine.addStepResult(response.content)
            taskStateMachine.executeNextStep()

            results.add("📚 Шаг $stepNumber:\n${response.content}")
        }

        taskStateMachine.transitionTo(Stage.VALIDATION, "Обучение завершено")

        return buildString {
            appendLine("📚 Все шаги пройдены!")
            appendLine()
            results.forEach { appendLine(it) }
            appendLine()
            appendLine("👉 Понятно ли вам? Напишите:")
            appendLine("  • 'да, понял' - если все ясно")
            appendLine("  • 'нет, не понял' - если нужны пояснения")
            appendLine("  • 'вопрос: [ваш вопрос]' - если есть конкретный вопрос")
        }
    }

    private suspend fun handleExecution(request: String): String {
        // Проверяем состояние паузы в начале метода (на случай, если пауза была установлена)
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
                        appendLine("  • 'Объясни корутины в Kotlin'")
                        appendLine("  • 'Расскажи про sealed class'")
                        appendLine("  • 'Что такое Flow'")
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
                        appendLine("👉 Понятно ли вам? Напишите:")
                        appendLine("  • 'да, понял' - если все ясно")
                        appendLine("  • 'нет, не понял' - если нужны пояснения")
                        appendLine("  • 'вопрос: [ваш вопрос]' - если есть конкретный вопрос")
                    }
                Stage.DONE ->
                    buildString {
                        appendLine("✅ Обучение уже завершено!")
                        appendLine()
                        appendLine("📚 Что еще хотите изучить? Просто напишите новую тему.")
                    }
                else -> "⚠️ Неизвестное состояние"
            }
        }

        // Проверяем, есть ли еще шаги
        if (taskStateMachine.state.currentStep >= taskStateMachine.state.totalSteps) {
            taskStateMachine.transitionTo(Stage.VALIDATION, "Все шаги пройдены")
            return buildString {
                appendLine("📚 Все шаги пройдены!")
                appendLine()
                appendLine("👉 Понятно ли вам? Напишите:")
                appendLine("  • 'да, понял' - если все ясно")
                appendLine("  • 'нет, не понял' - если нужны пояснения")
                appendLine("  • 'вопрос: [ваш вопрос]' - если есть конкретный вопрос")
            }
        }

        // Проверяем номер шага
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
                appendLine("👉 Напишите 'дальше' или 'выполни шаг $expectedStep'")
            }
        }

        // Выполняем следующий шаг
        val stepNumber = expectedStep
        val totalSteps = taskStateMachine.state.totalSteps
        val stepDescription = getStepDescription(taskStateMachine.state.plan, stepNumber)

        val executor = agents["executor-001"]
            ?: return "❌ Исполнитель не найден"

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

        taskStateMachine.addStepResult(response.content)
        taskStateMachine.executeNextStep()

        val result = buildString {
            appendLine("📚 Шаг $stepNumber:")
            appendLine()
            appendLine(response.content)
            appendLine()
            appendLine(taskStateMachine.getCurrentStepInfo())

            if (taskStateMachine.state.stage == Stage.VALIDATION) {
                appendLine()
                appendLine("📚 Все шаги пройдены!")
                appendLine()
                appendLine("👉 Понятно ли вам? Напишите:")
                appendLine("  • 'да, понял' - если все ясно")
                appendLine("  • 'нет, не понял' - если нужны пояснения")
                appendLine("  • 'вопрос: [ваш вопрос]' - если есть конкретный вопрос")
            } else {
                val nextStep = taskStateMachine.state.currentStep + 1
                appendLine()
                appendLine("👉 Следующий шаг: $nextStep")
                appendLine("👉 Напишите 'дальше' для продолжения")
                appendLine("👉 Или 'все сразу' для полного объяснения")
            }
        }

        conversationHistory.add(request to result)
        return result
    }

    private suspend fun handleValidation(request: String): String {
        // Проверяем состояние паузы
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
                        appendLine("📚 Что еще хотите изучить? Просто напишите новую тему.")
                    }
                else ->
                    buildString {
                        appendLine("⚠️ Сначала завершите все шаги обучения.")
                        appendLine()
                        appendLine("👉 Напишите 'дальше' для продолжения")
                    }
            }
        }

        // Проверяем, подтверждает ли пользователь понимание
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

        // Если пользователь подтверждает понимание
        if (isConfirmation) {
            taskStateMachine.validate("Понимание подтверждено")
            val finalResult = buildString {
                appendLine("✅ Отлично! Понимание подтверждено!")
                appendLine()
                appendLine("🎉 Обучение завершено успешно!")
                appendLine()
                appendLine("📚 Что еще хотите изучить? Просто напишите новую тему.")
                appendLine()
                appendLine("Например:")
                appendLine("  • 'Расскажи про корутины в Kotlin'")
                appendLine("  • 'Объясни sealed class с примерами'")
                appendLine("  • 'Как работает Flow в Kotlin'")
                appendLine("  • 'Что такое Data Class'")
            }
            conversationHistory.add(request to finalResult)
            return finalResult
        }

        // Если пользователь не понял
        if (isRejection) {
            return buildString {
                appendLine("⚠️ Нужно пояснить материал подробнее.")
                appendLine()
                appendLine("💡 Какая часть осталась непонятной?")
                appendLine()
                appendLine("Напишите конкретный вопрос:")
                appendLine("  • 'Объясни еще раз шаг 2'")
                appendLine("  • 'Что значит [термин]'")
                appendLine("  • 'Покажи другой пример'")
                appendLine("  • 'Как это работает на практике'")
            }
        }

        // Если пользователь задает вопрос - обрабатываем через координатора
        if (request.lowercase().contains("вопрос") || request.contains("?")) {
            return handleGeneralQuery(request)
        }

        // Если непонятно, что пользователь имеет в виду
        return buildString {
            appendLine("📚 Все шаги пройдены!")
            appendLine()
            appendLine("👉 Понятно ли вам? Напишите:")
            appendLine("  • 'да, понял' - если все ясно")
            appendLine("  • 'нет, не понял' - если нужны пояснения")
            appendLine("  • 'вопрос: [ваш вопрос]' - если есть конкретный вопрос")
            appendLine()
            appendLine("📊 Текущее состояние:")
            appendLine(taskStateMachine.getCurrentStepInfo())
        }
    }

    private suspend fun handleGeneralQuery(request: String): String {
        // Проверяем состояние паузы
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

        val coordinator = agents["coordinator-001"]
            ?: return "❌ Координатор не найден"

        val historyContext = buildHistoryContext()

        val response = llm.ask(
            listOf(
                Message("system", coordinator.systemPrompt),
                Message("user", """
                    Пользователь сказал: "$request"
                    
                    Текущее состояние: ${taskStateMachine.state.stage}
                    План: ${if (taskStateMachine.state.plan.isNotEmpty()) "Есть" else "Нет"}
                    
                    История диалога:
                    $historyContext
                    
                    Ответь на запрос пользователя.
                    Если пользователь хочет что-то изучить - предложи создать план.
                    Если план уже есть - напомни о шагах обучения.
                    Если пользователь задает вопрос по теме - ответь подробно.
                """.trimIndent())
            )
        )

        conversationHistory.add(request to response.content)
        return response.content
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
            appendLine("=== СТАТУС АГЕНТОВ ===")
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