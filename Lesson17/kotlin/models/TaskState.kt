package models

data class TaskState(
    val stage: Stage = Stage.INIT,
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val expectedAction: String = "описать план",
    val isPaused: Boolean = false,
    val plan: String = "",
    val validationNotes: String = ""
)

enum class Stage {
    INIT,           // Начальное состояние
    PLANNING,       // Планирование
    PLAN_APPROVED,  // План утвержден
    EXECUTION,      // Выполнение
    VALIDATION,     // Валидация
    DONE            // Завершено
}

enum class TransitionType {
    INIT_TO_PLANNING,
    PLANNING_TO_APPROVED,
    APPROVED_TO_EXECUTION,
    EXECUTION_STEP,
    EXECUTION_TO_VALIDATION,
    VALIDATION_TO_DONE,
    PAUSE,
    RESUME,
    REJECT
}