package models

/**
 * Инвариант - правило, которое ассистент не имеет права нарушать
 */
data class Invariant(
    val id: String,
    val category: InvariantCategory,
    val description: String,
    val severity: InvariantSeverity = InvariantSeverity.HIGH,
    val isActive: Boolean = true
)

enum class InvariantCategory {
    ARCHITECTURE,      // Архитектурные решения
    TECHNOLOGY,        // Технологический стек
    BUSINESS_RULE,     // Бизнес-правила
    SECURITY,          // Безопасность
    PERFORMANCE,       // Производительность
    LEGAL              // Правовые ограничения
}

enum class InvariantSeverity {
    CRITICAL,   // Нарушение невозможно
    HIGH,       // Нарушение крайне нежелательно
    MEDIUM,     // Нарушение возможно с обоснованием
    LOW         // Рекомендация
}

/**
 * Результат проверки инварианта
 */
data class InvariantCheckResult(
    val invariant: Invariant,
    val isViolated: Boolean,
    val explanation: String? = null,
    val suggestion: String? = null
)
