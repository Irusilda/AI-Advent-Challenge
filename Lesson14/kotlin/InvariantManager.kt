import models.Invariant
import models.InvariantCategory
import models.InvariantCheckResult
import models.InvariantSeverity

class InvariantManager {
    private val invariants = mutableMapOf<String, Invariant>()
    private val violationHistory = mutableListOf<InvariantCheckResult>()

    init {
        registerDefaultInvariants()
    }

    private fun registerDefaultInvariants() {
        addInvariant(
            Invariant(
                id = "ARCH-001",
                category = InvariantCategory.ARCHITECTURE,
                description = "Ассистент должен использовать Kotlin для реализации логики",
                severity = InvariantSeverity.CRITICAL
            )
        )
        addInvariant(
            Invariant(
                id = "ARCH-002",
                category = InvariantCategory.ARCHITECTURE,
                description = "Стратегии управления контекстом должны реализовывать интерфейс ContextStrategy",
                severity = InvariantSeverity.CRITICAL
            )
        )
        addInvariant(
            Invariant(
                id = "TECH-001",
                category = InvariantCategory.TECHNOLOGY,
                description = "API вызовы должны использовать DeepSeek API с корректной аутентификацией",
                severity = InvariantSeverity.CRITICAL
            )
        )
        addInvariant(
            Invariant(
                id = "TECH-002",
                category = InvariantCategory.TECHNOLOGY,
                description = "История диалога должна сохраняться в JSON формате",
                severity = InvariantSeverity.HIGH
            )
        )
        addInvariant(
            Invariant(
                id = "BUSINESS-001",
                category = InvariantCategory.BUSINESS_RULE,
                description = "Ассистент не должен предлагать решения, нарушающие авторские права",
                severity = InvariantSeverity.CRITICAL
            )
        )
        addInvariant(
            Invariant(
                id = "BUSINESS-002",
                category = InvariantCategory.BUSINESS_RULE,
                description = "Ассистент должен явно указывать источники информации при цитировании",
                severity = InvariantSeverity.HIGH
            )
        )
        addInvariant(
            Invariant(
                id = "SEC-001",
                category = InvariantCategory.SECURITY,
                description = "Ассистент не должен запрашивать или хранить пароли в открытом виде",
                severity = InvariantSeverity.CRITICAL
            )
        )
        addInvariant(
            Invariant(
                id = "PERF-001",
                category = InvariantCategory.PERFORMANCE,
                description = "Контекст не должен превышать лимит модели (ограничение токенов)",
                severity = InvariantSeverity.CRITICAL
            )
        )
        addInvariant(
            Invariant(
                id = "LEGAL-001",
                category = InvariantCategory.LEGAL,
                description = "Ассистент должен соблюдать GDPR правила обработки персональных данных",
                severity = InvariantSeverity.CRITICAL
            )
        )
    }

    fun addInvariant(invariant: Invariant) {
        invariants[invariant.id] = invariant
    }

    fun removeInvariant(id: String): Boolean {
        return invariants.remove(id) != null
    }

    fun getInvariant(id: String): Invariant? = invariants[id]

    fun getAllInvariants(): List<Invariant> = invariants.values.toList()

    fun getActiveInvariants(): List<Invariant> = invariants.values.filter { it.isActive }

    fun getInvariantsByCategory(category: InvariantCategory): List<Invariant> =
        invariants.values.filter { it.category == category && it.isActive }

    fun getViolationHistory(): List<InvariantCheckResult> = violationHistory.toList()

    fun clearViolationHistory() = violationHistory.clear()

    fun checkTextForViolations(text: String): List<InvariantCheckResult> {
        val results = mutableListOf<InvariantCheckResult>()

        getActiveInvariants().forEach { invariant ->
            val violation = detectViolation(text, invariant)
            if (violation != null) {
                results.add(violation)
                violationHistory.add(violation)
            }
        }

        return results
    }

    private fun detectViolation(text: String, invariant: Invariant): InvariantCheckResult? {
        val lowerText = text.lowercase()

        when (invariant.id) {
            "ARCH-001" -> {
                if (lowerText.contains("python") ||
                    (lowerText.contains("java") && !lowerText.contains("kotlin"))) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Предлагается использование другого языка программирования",
                        suggestion = "Используйте Kotlin для реализации логики"
                    )
                }
            }
            "ARCH-002" -> {
                if (lowerText.contains("наследование") && lowerText.contains("класс") &&
                    !lowerText.contains("interface") && !lowerText.contains("интерфейс")) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Предлагается реализация без использования интерфейса ContextStrategy",
                        suggestion = "Реализуйте интерфейс ContextStrategy для управления контекстом"
                    )
                }
            }
            "TECH-001" -> {
                if (lowerText.contains("использовать другой api") ||
                    lowerText.contains("заменить deepseek") && !lowerText.contains("проверка")) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Предлагается использование другого API",
                        suggestion = "Используйте DeepSeek API с корректной аутентификацией"
                    )
                }
            }
            "TECH-002" -> {
                if (lowerText.contains("сохранить") && lowerText.contains("xml") && !lowerText.contains("json")) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Предлагается сохранение в не-JSON формате",
                        suggestion = "Используйте JSON формат для сохранения истории"
                    )
                }
            }
            "BUSINESS-001" -> {
                val hasDownload = lowerText.contains("скачать") ||
                        lowerText.contains("download") ||
                        lowerText.contains("загрузить") ||
                        lowerText.contains("скачайте")

                val hasFree = lowerText.contains("бесплатно") ||
                        lowerText.contains("free") ||
                        lowerText.contains("даром") ||
                        lowerText.contains("бесплатный")

                val hasMovie = lowerText.contains("фильм") ||
                        lowerText.contains("movie") ||
                        lowerText.contains("кино") ||
                        lowerText.contains("сериал") ||
                        lowerText.contains("контент")

                val hasTorrent = lowerText.contains("torrent") ||
                        lowerText.contains("торрент") ||
                        lowerText.contains("пират") ||
                        lowerText.contains("пиратка") ||
                        lowerText.contains("пиратский")

                val isViolation = (hasDownload && (hasFree || hasMovie || hasTorrent)) ||
                        hasTorrent

                if (isViolation) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Запрос связан с нелегальным скачиванием контента",
                        suggestion = "Используйте легальные источники: онлайн-кинотеатры, стриминговые сервисы или официальные сайты"
                    )
                }
            }
            "BUSINESS-002" -> {
                if ((lowerText.contains("по мнению") || lowerText.contains("как сказано") ||
                            lowerText.contains("согласно") || lowerText.contains("исследование показало")) &&
                    !lowerText.contains("источник:") && !lowerText.contains("ссылка:")) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Не указан источник информации",
                        suggestion = "Добавьте ссылку на источник или поясните, что это ваше мнение"
                    )
                }
            }
            "SEC-001" -> {
                val hasPassword = lowerText.contains("пароль") ||
                        lowerText.contains("password") ||
                        lowerText.contains("пароли")

                val hasStore = lowerText.contains("хранить") ||
                        lowerText.contains("сохранить") ||
                        lowerText.contains("store") ||
                        lowerText.contains("save") ||
                        lowerText.contains("хранение") ||
                        lowerText.contains("хранения")

                val hasOpen = lowerText.contains("открытый") ||
                        lowerText.contains("plain") ||
                        lowerText.contains("текст") ||
                        lowerText.contains("незащищенный") ||
                        lowerText.contains("незащищённый")

                val hasQuestion = lowerText.contains("как") ||
                        lowerText.contains("какой") ||
                        lowerText.contains("что") ||
                        lowerText.contains("где") ||
                        lowerText.contains("почему")

                val hasDatabase = lowerText.contains("база данных") ||
                        lowerText.contains("бд") ||
                        lowerText.contains("database") ||
                        lowerText.contains("db")

                val isViolation = (hasPassword && hasStore) ||
                        (hasPassword && hasOpen) ||
                        (hasPassword && hasQuestion) ||
                        (hasPassword && hasDatabase)

                if (isViolation) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Запрос связан с хранением паролей в небезопасном виде",
                        suggestion = "Используйте безопасные методы аутентификации: OAuth, JWT, хеширование (bcrypt, Argon2) с солью"
                    )
                }
            }
            "PERF-001" -> {
                val tokenEstimate = text.length / 4
                if (tokenEstimate > 1000000) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Контекст может превысить лимит токенов (оценка: $tokenEstimate)",
                        suggestion = "Используйте стратегии сжатия контекста или разделите запрос на части"
                    )
                }
            }
            "LEGAL-001" -> {
                val hasPersonalData = lowerText.contains("личные данные") ||
                        lowerText.contains("персональные данные") ||
                        lowerText.contains("персональных данных")

                val hasName = lowerText.contains("имя") || lowerText.contains("name")
                val hasAddress = lowerText.contains("адрес") || lowerText.contains("address")
                val hasPhone = lowerText.contains("телефон") || lowerText.contains("phone")
                val hasNumber = lowerText.contains("номер") || lowerText.contains("number")

                val isViolation = hasPersonalData ||
                        (hasName && hasAddress) ||
                        (hasName && hasPhone) ||
                        (hasAddress && hasPhone) ||
                        (hasName && hasNumber)

                if (isViolation) {
                    return InvariantCheckResult(
                        invariant = invariant,
                        isViolated = true,
                        explanation = "Обработка персональных данных без явного согласия",
                        suggestion = "Запросите согласие пользователя, анонимизируйте данные или используйте агрегированные данные"
                    )
                }
            }
        }

        return null
    }

    fun getInvariantsDescription(): String {
        return buildString {
            appendLine("=== ИНВАРИАНТЫ (правила, которые нельзя нарушать) ===")
            getActiveInvariants().groupBy { it.category }.forEach { (category, items) ->
                appendLine("\n${category.name}:")
                items.forEach { invariant ->
                    appendLine("  • ${invariant.description} [${invariant.severity}]")
                }
            }
        }
    }

    fun hasCriticalViolations(text: String): Boolean {
        return checkTextForViolations(text).any {
            it.invariant.severity == InvariantSeverity.CRITICAL
        }
    }

    fun getStats(): String = buildString {
        appendLine("=== СТАТИСТИКА ИНВАРИАНТОВ ===")
        appendLine("Всего инвариантов: ${invariants.size}")
        appendLine("Активных: ${getActiveInvariants().size}")
        appendLine("Нарушений зафиксировано: ${violationHistory.size}")
        appendLine("\nПо категориям:")
        InvariantCategory.values().forEach { category ->
            val count = getInvariantsByCategory(category).size
            if (count > 0) {
                appendLine("  ${category.name}: $count")
            }
        }
        appendLine("\nПо степени важности:")
        InvariantSeverity.values().forEach { severity ->
            val count = invariants.values.filter { it.severity == severity && it.isActive }.size
            if (count > 0) {
                appendLine("  $severity: $count")
            }
        }
    }

    fun printViolationHistory() {
        if (violationHistory.isEmpty()) {
            println("✅ Нарушений не зафиксировано")
            return
        }

        println("=== ИСТОРИЯ НАРУШЕНИЙ ИНВАРИАНТОВ ===\n")
        violationHistory.forEachIndexed { index, violation ->
            println("${index + 1}. ${violation.invariant.description}")
            println("   Объяснение: ${violation.explanation ?: "Нарушение обнаружено"}")
            println("   Предложение: ${violation.suggestion ?: "Нет предложения"}")
            println()
        }
        println("---")
        println("Всего нарушений: ${violationHistory.size}")
    }
}