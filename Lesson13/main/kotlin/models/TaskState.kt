package models

data class TaskState(
    val stage: Stage = Stage.PLANNING,
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val expectedAction: String = "описать план",
    val isPaused: Boolean = false
)