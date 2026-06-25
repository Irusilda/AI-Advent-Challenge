import models.MemoryItem
import models.MemoryType
import models.Message

/**
 * Управление памятью с явным разделением на слои
 */
class MemoryManager {
    private val shortTermMemory = mutableListOf<MemoryItem>()
    private val workingMemory = mutableListOf<MemoryItem>()
    private val longTermMemory = mutableListOf<MemoryItem>()

    // Лимиты для каждого типа памяти
    private val shortTermLimit = 20
    private val workingLimit = 10
    private val longTermLimit = 100

    /**
     * Добавить элемент в указанный тип памяти
     */
    fun addToMemory(item: MemoryItem) {
        when (item.type) {
            MemoryType.SHORT_TERM -> {
                shortTermMemory.add(item)
                if (shortTermMemory.size > shortTermLimit) {
                    // Перемещаем старые элементы в рабочую память
                    val oldItems = shortTermMemory.dropLast(shortTermLimit / 2)
                    oldItems.forEach {
                        promoteToWorking(it)
                    }
                    shortTermMemory.removeAll(oldItems.toSet())
                }
            }
            MemoryType.WORKING -> {
                workingMemory.add(item)
                if (workingMemory.size > workingLimit) {
                    // Старые элементы рабочей памяти могут стать долговременными
                    val oldItems = workingMemory.dropLast(workingLimit / 2)
                    oldItems.forEach {
                        if (it.importance > 0.5) {
                            promoteToLongTerm(it)
                        }
                    }
                    workingMemory.removeAll(oldItems.toSet())
                }
            }
            MemoryType.LONG_TERM -> {
                longTermMemory.add(item)
                if (longTermMemory.size > longTermLimit) {
                    // Удаляем наименее важные элементы
                    longTermMemory.sortByDescending { it.importance }
                    while (longTermMemory.size > longTermLimit) {
                        longTermMemory.removeAt(longTermMemory.size - 1)
                    }
                }
            }
        }
    }

    /**
     * Повысить краткосрочную память до рабочей
     */
    private fun promoteToWorking(item: MemoryItem) {
        addToMemory(item.copy(
            type = MemoryType.WORKING,
            importance = calculateImportance(item)
        ))
    }

    /**
     * Повысить рабочую память до долговременной
     */
    private fun promoteToLongTerm(item: MemoryItem) {
        addToMemory(item.copy(
            type = MemoryType.LONG_TERM,
            timestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Рассчитать важность элемента памяти
     */
    private fun calculateImportance(item: MemoryItem): Double {
        // Ключевые слова, указывающие на важность
        val importantKeywords = listOf(
            "решил", "выбрал", "предпочитаю", "хочу", "нужно",
            "важно", "ключевой", "основной", "главный",
            "профиль", "настройки", "правило", "ограничение"
        )

        var importance = item.importance
        importantKeywords.forEach { keyword ->
            if (item.content.contains(keyword, ignoreCase = true)) {
                importance += 0.2
            }
        }

        // Длинные сообщения вероятнее содержат важную информацию
        if (item.content.length > 100) {
            importance += 0.1
        }

        return importance.coerceIn(0.0, 1.0)
    }

    /**
     * Получить контекст для LLM с учетом всех слоев памяти
     */
    fun buildContext(systemMessage: Message, recentMessages: List<Message>): List<Message> {
        val context = mutableListOf<Message>()

        // Системное сообщение с долговременной памятью
        val systemContent = buildString {
            appendLine(systemMessage.content)

            // Добавляем долговременную память (профиль, знания)
            if (longTermMemory.isNotEmpty()) {
                appendLine("--- Долговременная память (профиль, решения, знания) ---")
                longTermMemory.forEach { item ->
                    appendLine("- ${item.content}")
                }
            }

            // Добавляем рабочую память (текущая задача)
            if (workingMemory.isNotEmpty()) {
                appendLine("--- Рабочая память (текущая задача) ---")
                        workingMemory.forEach { item ->
                    appendLine("- ${item.content}")
                }
            }
        }

        context.add(Message("system", systemContent))

        // Добавляем краткосрочную память (текущий диалог)
        context.addAll(recentMessages)

        return context
    }

    /**
     * Сохранить информацию в соответствующий слой памяти
     */
    fun storeInformation(type: MemoryType, content: String, source: String = "", importance: Double = 1.0) {
        val item = MemoryItem(
            type = type,
            content = content,
            source = source,
            importance = 1.0
        )
        addToMemory(item)
    }

    /**
     * Обработать сообщение пользователя и распределить по слоям памяти
     */
    fun processUserMessage(message: Message) {
        val content = message.content

        // Всегда сохраняем в краткосрочную память
        storeInformation(
            MemoryType.SHORT_TERM,
            "Пользователь: $content",
            source = "user_message",
            importance = 0.5
        )

        // Анализируем и сохраняем в рабочую память если это задача
        if (content.contains("задача", ignoreCase = true) ||
            content.contains("нужно", ignoreCase = true) ||
            content.contains("сделай", ignoreCase = true)) {
            storeInformation(
                MemoryType.WORKING,
                "Текущая задача: $content",
                source = "task_detection",
                importance = 0.8
            )
        }

        // Сохраняем важные факты в долговременную память
        if (content.contains("меня зовут", ignoreCase = true) ||
            content.contains("мой профиль", ignoreCase = true) ||
            content.contains("я предпочитаю", ignoreCase = true)) {
            storeInformation(
                MemoryType.LONG_TERM,
                "Профиль пользователя: $content",
                source = "profile_extraction",
                importance = 1.0
            )
        }
    }

    /**
     * Обработать ответ ассистента и сохранить важные решения
     */
    fun processAssistantResponse(message: Message) {
        val content = message.content

        // Сохраняем важные решения в долговременную память
        if (content.contains("решил", ignoreCase = true) ||
            content.contains("рекомендую", ignoreCase = true) ||
            content.contains("советую", ignoreCase = true)) {
            storeInformation(
                MemoryType.LONG_TERM,
                "Решение ассистента: $content",
                source = "decision",
                importance = 0.9
            )
        }
    }

    /**
     * Получить содержимое определенного слоя памяти
     */
    fun getMemoryByType(type: MemoryType): List<MemoryItem> {
        return when (type) {
            MemoryType.SHORT_TERM -> shortTermMemory.toList()
            MemoryType.WORKING -> workingMemory.toList()
            MemoryType.LONG_TERM -> longTermMemory.toList()
        }
    }

    /**
     * Очистить определенный слой памяти
     */
    fun clearMemory(type: MemoryType) {
        when (type) {
            MemoryType.SHORT_TERM -> shortTermMemory.clear()
            MemoryType.WORKING -> workingMemory.clear()
            MemoryType.LONG_TERM -> longTermMemory.clear()
        }
    }

    /**
     * Очистить всю память
     */
    fun clearAll() {
        shortTermMemory.clear()
        workingMemory.clear()
        longTermMemory.clear()
    }

    /**
     * Получить статистику по памяти
     */
    fun getStats(): String {
        return """
            Краткосрочная память: ${shortTermMemory.size} элементов
            Рабочая память: ${workingMemory.size} элементов
            Долговременная память: ${longTermMemory.size} элементов
        """.trimIndent()
    }
}