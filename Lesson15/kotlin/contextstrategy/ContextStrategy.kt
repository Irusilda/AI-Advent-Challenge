package contextstrategy

import models.Message

/**
 * Интерфейс стратегии управления контекстом диалога.
 */
interface ContextStrategy {
    /** Добавить сообщение пользователя в историю (до ответа ассистента) */
    fun addUserMessage(message: Message)

    /** Добавить ответ ассистента в историю */
    fun addAssistantMessage(message: Message)

    /** Построить список сообщений для отправки в LLM */
    fun buildContext(): List<Message>

    /** Очистить всю память стратегии */
    fun clear()

    /** Загрузить историю извне (синхронизация) */
    fun loadHistory(messages: List<Message>)

    /** Получить текущую историю (для сохранения) */
    fun getHistory(): List<Message>

    /** Вернуть имя стратегии */
    fun getName(): String
}