import models.Stage
import models.TaskState

class TaskStateMachine {
    var state = TaskState()

    fun pause() { state = state.copy(isPaused = true) }
    fun resume() { state = state.copy(isPaused = false) }

    fun nextStep(action: String) {
        if (state.isPaused) return
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
                    state = state.copy(currentStep = state.currentStep + 1)
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
}