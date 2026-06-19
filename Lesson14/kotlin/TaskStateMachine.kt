import models.Stage
import models.TaskState
import models.InvariantSeverity
import models.ValidationResult

class TaskStateMachine {
    var state = TaskState()
    private val stepHistory = mutableListOf<String>()

    fun pause() { state = state.copy(isPaused = true) }
    fun resume() { state = state.copy(isPaused = false) }

    fun getStepHistory(): List<String> = stepHistory.toList()

    fun getCurrentStepInfo(): String = """
        Этап: ${state.stage}
        Шаг: ${state.currentStep}/${state.totalSteps}
        Ожидаемое действие: ${state.expectedAction}
        Пауза: ${if (state.isPaused) "Да" else "Нет"}
        Шагов выполнено: ${stepHistory.size}
    """.trimIndent()

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
        nextStep(action)

        return ValidationResult.Success(
            message = "✅ Действие выполнено успешно",
            newState = state
        )
    }

    private fun nextStep(action: String) {
        when (state.stage) {
            Stage.PLANNING -> {
                state = state.copy(
                    stage = Stage.EXECUTION,
                    currentStep = 1,
                    expectedAction = "выполнить шаг 1"
                )
            }
            Stage.EXECUTION -> {
                if (state.currentStep < state.totalSteps) {
                    state = state.copy(
                        currentStep = state.currentStep + 1,
                        expectedAction = "выполнить шаг ${state.currentStep + 1}"
                    )
                } else {
                    state = state.copy(
                        stage = Stage.VALIDATION,
                        expectedAction = "проверить результат"
                    )
                }
            }
            Stage.VALIDATION -> {
                state = state.copy(
                    stage = Stage.DONE,
                    expectedAction = "задача завершена"
                )
            }
            Stage.DONE -> Unit
        }
    }

    fun setTotalSteps(steps: Int) {
        state = state.copy(totalSteps = steps)
    }

    fun reset() {
        state = TaskState()
        stepHistory.clear()
    }
}