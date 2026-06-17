package models

/**
 * Элемент памяти с указанием типа
 */
data class MemoryItem(
    val type: MemoryType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "", // откуда получено (сообщение пользователя, решение, факт)
    val importance: Double = 1.0 // важность для долговременной памяти
)
