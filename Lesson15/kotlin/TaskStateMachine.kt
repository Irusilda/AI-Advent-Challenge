import models.*

class TaskStateMachine {
    var state = TaskState()
    private val stepHistory = mutableListOf<String>()
    private val transitionHistory = mutableListOf<TransitionRecord>()
    private val stepResults = mutableListOf<String>() // Храним результаты выполнения шагов

    // Определение допустимых переходов
    private val allowedTransitions = mapOf(
        Stage.INIT to setOf(Stage.PLANNING),
        Stage.PLANNING to setOf(Stage.PLAN_APPROVED, Stage.INIT),
        Stage.PLAN_APPROVED to setOf(Stage.EXECUTION, Stage.PLANNING),
        Stage.EXECUTION to setOf(Stage.EXECUTION, Stage.VALIDATION, Stage.PLANNING),
        Stage.VALIDATION to setOf(Stage.DONE, Stage.EXECUTION, Stage.PLANNING),
        Stage.DONE to setOf(Stage.INIT)
    )

    // Требования для переходов
    private val transitionRequirements = mapOf<Pair<Stage, Stage>, (TaskState) -> Boolean>(
        Stage.INIT to Stage.PLANNING to { it.plan.isEmpty() },
        Stage.PLANNING to Stage.PLAN_APPROVED to { it.plan.isNotEmpty() },
        Stage.PLAN_APPROVED to Stage.EXECUTION to { it.plan.isNotEmpty() },
        Stage.EXECUTION to Stage.VALIDATION to { it.currentStep >= it.totalSteps },
        Stage.VALIDATION to Stage.DONE to { it.validationNotes.isNotEmpty() }
    )

    fun pause() {
        if (!state.isPaused) {
            state = state.copy(isPaused = true)
            transitionHistory.add(TransitionRecord(
                from = state.stage,
                to = state.stage,
                type = TransitionType.PAUSE,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    fun resume() {
        if (state.isPaused) {
            state = state.copy(isPaused = false)
            transitionHistory.add(TransitionRecord(
                from = state.stage,
                to = state.stage,
                type = TransitionType.RESUME,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    fun transitionTo(newStage: Stage, reason: String = ""): TransitionResult {
        // Проверка паузы
        if (state.isPaused && newStage != state.stage) {
            return TransitionResult.Failed(
                "Задача приостановлена. Используйте 'resume' для продолжения.",
                state
            )
        }

        // Проверка допустимости перехода
        val allowed = allowedTransitions[state.stage] ?: emptySet()
        if (newStage !in allowed && newStage != state.stage) {
            return TransitionResult.Failed(
                "Недопустимый переход из ${state.stage} в $newStage. " +
                        "Допустимые: ${allowed.joinToString(", ")}",
                state
            )
        }

        // Проверка требований для перехода
        val requirementKey = state.stage to newStage
        val requirement = transitionRequirements[requirementKey]
        if (requirement != null && !requirement(state)) {
            return TransitionResult.Failed(
                "Не выполнены требования для перехода: ${getRequirementDescription(requirementKey)}",
                state
            )
        }

        // Выполнение перехода
        val oldStage = state.stage

        state = when (newStage) {
            Stage.INIT -> state.copy(
                stage = Stage.INIT,
                currentStep = 0,
                expectedAction = "описать план",
                plan = "",
                validationNotes = ""
            )
            Stage.PLANNING -> state.copy(
                stage = Stage.PLANNING,
                expectedAction = "описать план"
            )
            Stage.PLAN_APPROVED -> state.copy(
                stage = Stage.PLAN_APPROVED,
                expectedAction = "начать выполнение"
            )
            Stage.EXECUTION -> state.copy(
                stage = Stage.EXECUTION,
                currentStep = if (oldStage == Stage.PLAN_APPROVED) 1 else state.currentStep,
                expectedAction = "выполнить шаг ${state.currentStep + 1}"
            )
            Stage.VALIDATION -> state.copy(
                stage = Stage.VALIDATION,
                expectedAction = "проверить результат"
            )
            Stage.DONE -> state.copy(
                stage = Stage.DONE,
                expectedAction = "задача завершена"
            )
            else -> state.copy(stage = newStage)
        }

        transitionHistory.add(TransitionRecord(
            from = oldStage,
            to = newStage,
            type = try {
                TransitionType.valueOf("${oldStage.name}_TO_${newStage.name}")
            } catch (e: Exception) {
                TransitionType.REJECT
            },
            reason = reason,
            timestamp = System.currentTimeMillis()
        ))

        return TransitionResult.Success(state)
    }

    private fun getRequirementDescription(key: Pair<Stage, Stage>): String {
        return when (key) {
            Stage.INIT to Stage.PLANNING -> "план еще не создан"
            Stage.PLANNING to Stage.PLAN_APPROVED -> "план должен быть описан"
            Stage.PLAN_APPROVED to Stage.EXECUTION -> "план должен быть утвержден"
            Stage.EXECUTION to Stage.VALIDATION -> "все шаги должны быть выполнены"
            Stage.VALIDATION to Stage.DONE -> "должны быть добавлены заметки по валидации"
            else -> "неизвестные требования"
        }
    }

    fun setPlan(plan: String): Boolean {
        if (state.stage == Stage.PLANNING || state.stage == Stage.INIT) {
            state = state.copy(plan = plan)
            val result = transitionTo(Stage.PLAN_APPROVED, "План описан")
            return result is TransitionResult.Success
        }
        return false
    }

    fun executeStep(): Boolean {
        if (state.stage == Stage.EXECUTION && state.currentStep < state.totalSteps) {
            stepHistory.add("Шаг ${state.currentStep + 1} выполнен")
            state = state.copy(
                currentStep = state.currentStep + 1,
                expectedAction = if (state.currentStep + 1 < state.totalSteps)
                    "выполнить шаг ${state.currentStep + 2}"
                else
                    "завершить выполнение"
            )

            // Если все шаги выполнены, переходим к валидации
            if (state.currentStep >= state.totalSteps) {
                val result = transitionTo(Stage.VALIDATION, "Все шаги выполнены")
                return result is TransitionResult.Success
            }
            return true
        }
        return false
    }

    fun executeNextStep(): Boolean {
        // Проверяем, что мы в EXECUTION
        if (state.stage != Stage.EXECUTION) {
            return false
        }

        // Проверяем, есть ли еще шаги
        if (state.currentStep >= state.totalSteps) {
            transitionTo(Stage.VALIDATION, "Все шаги выполнены")
            return false
        }

        // Выполняем следующий шаг (только следующий!)
        state = state.copy(
            currentStep = state.currentStep + 1,
            expectedAction = if (state.currentStep + 1 < state.totalSteps)
                "выполнить шаг ${state.currentStep + 2}"
            else
                "завершить выполнение"
        )

        stepHistory.add("Шаг ${state.currentStep} выполнен")

        // Если все шаги выполнены, переходим к валидации
        if (state.currentStep >= state.totalSteps) {
            transitionTo(Stage.VALIDATION, "Все шаги выполнены")
        }

        return true
    }

    fun addStepResult(result: String) {
        stepResults.add("Шаг ${state.currentStep}: $result")
    }

    fun getStepResults(): List<String> = stepResults.toList()

    fun validate(result: String): Boolean {
        if (state.stage == Stage.VALIDATION) {
            state = state.copy(validationNotes = result)
            val transitionResult = transitionTo(Stage.DONE, "Валидация пройдена")
            return transitionResult is TransitionResult.Success
        }
        return false
    }

    /**
     * Валидация шага с проверкой инвариантов
     */
    fun validateStep(action: String, invariantManager: InvariantManager): ValidationResult {
        if (state.isPaused) {
            return ValidationResult.Paused("Задача приостановлена, не могу выполнить действие. Используйте 'resume' для продолжения.")
        }

        // 1. Проверка инвариантов
        val violations = invariantManager.checkTextForViolations(action)
        val criticalViolations = violations.filter {
            it.invariant.severity == InvariantSeverity.CRITICAL
        }

        if (criticalViolations.isNotEmpty()) {
            return ValidationResult.InvariantViolation(
                message = "Действие нарушает критические инварианты",
                violations = criticalViolations
            )
        }

        // 2. Проверка соответствия ожидаемому действию
        if (!action.contains(state.expectedAction, ignoreCase = true) &&
            state.stage != Stage.DONE) {
            return ValidationResult.WrongAction(
                expected = state.expectedAction,
                actual = action,
                suggestion = "Пожалуйста, выполните: ${state.expectedAction}"
            )
        }

        // 3. Проверка последовательности шагов
        if (state.stage == Stage.EXECUTION && state.currentStep > state.totalSteps) {
            return ValidationResult.OutOfBounds(
                message = "Все шаги выполнены, перейдите к валидации",
                suggestion = "Используйте 'next' для перехода к валидации"
            )
        }

        // 4. Проверка, что действие не пытается перепрыгнуть через этапы
        if (state.stage == Stage.PLANNING && !action.contains("план", ignoreCase = true) &&
            !action.contains("plan", ignoreCase = true)) {
            return ValidationResult.WrongAction(
                expected = "описать план",
                actual = action,
                suggestion = "Сначала опишите план действий, затем переходите к выполнению"
            )
        }

        stepHistory.add(action)
        val stepResult = executeStep()

        return if (stepResult) {
            ValidationResult.Success(
                message = "✅ Действие выполнено успешно",
                newState = state
            )
        } else {
            ValidationResult.OutOfBounds(
                message = "Не удалось выполнить шаг",
                suggestion = "Проверьте состояние задачи"
            )
        }
    }

    fun getStepHistory(): List<String> = stepHistory.toList()
    fun getTransitionHistory(): List<TransitionRecord> = transitionHistory.toList()

    fun getCurrentStepInfo(): String = """
        Состояние: ${state.stage}
        Шаг: ${state.currentStep}/${state.totalSteps}
        Ожидаемое действие: ${state.expectedAction}
        Пауза: ${if (state.isPaused) "Да" else "Нет"}
        План: ${if (state.plan.isNotEmpty()) "✓ Описан" else "✗ Не описан"}
        Валидация: ${if (state.validationNotes.isNotEmpty()) "✓ Выполнена" else "✗ Не выполнена"}
        Шагов выполнено: ${stepHistory.size}
        Всего переходов: ${transitionHistory.size}
    """.trimIndent()

    fun setTotalSteps(steps: Int) {
        state = state.copy(totalSteps = steps)
    }

    fun reset() {
        state = TaskState()
        stepHistory.clear()
        transitionHistory.clear()
        stepResults.clear()
    }
}

sealed class TransitionResult {
    data class Success(val newState: TaskState) : TransitionResult()
    data class Failed(val message: String, val currentState: TaskState) : TransitionResult()
}

data class TransitionRecord(
    val from: Stage,
    val to: Stage,
    val type: TransitionType,
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)