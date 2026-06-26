package models

sealed class ValidationResult {

    data class Success(val message: String, val newState: TaskState) : ValidationResult()

    data class InvariantViolation(
        val message: String,
        val violations: List<InvariantCheckResult>
    ) : ValidationResult()

    data class WrongAction(
        val expected: String,
        val actual: String,
        val suggestion: String
    ) : ValidationResult()

    data class OutOfBounds(val message: String, val suggestion: String) : ValidationResult()

    data class Paused(val message: String) : ValidationResult()
}