import models.Message

class ConversationMemory(
    private val llm: DeepSeekClient,
    private val keepLastMessages: Int = 4,
) {

    private var summary = ""

    /**
     * Строит контекст для отправки в LLM:
     * - системное сообщение + summary (если есть) объединены в одно system-сообщение
     * - затем последние keepLastMessages сообщений (не system)
     */
    fun buildContext(messages: List<Message>): List<Message> {
        val result = mutableListOf<Message>()

        val originalSystem = messages.firstOrNull { it.role == "system" }
            ?: Message("system", "Ты — полезный ассистент.")

        val systemContent = if (summary.isNotBlank()) {
            "${originalSystem.content}\n\nКраткая история:\n$summary"
        } else {
            originalSystem.content
        }

        result.add(Message("system", systemContent))

        val nonSystemMessages = messages.filter { it.role != "system" }
        val lastMessages = nonSystemMessages.takeLast(keepLastMessages)
        result.addAll(lastMessages)

        return result
    }

    /**
     * Сжимает историю, если накопилось слишком много сообщений.
     * Удаляются ВСЕ сообщения, кроме последних keepLastMessages.
     * Удалённые сообщения превращаются в краткое содержание (summary).
     */
    fun compressIfNeeded(messages: MutableList<Message>) {
        val nonSystem = messages.filter { it.role != "system" }

        if (nonSystem.size <= keepLastMessages) return

        val toCompress = nonSystem.dropLast(keepLastMessages)
        if (toCompress.isEmpty()) return

        updateSummary(toCompress)

        messages.removeAll(toCompress.toSet())
    }

    /**
     * Обновляет краткое содержание: объединяет старый summary с новым блоком сообщений
     * и просит LLM сделать общий сжатый пересказ (чтобы summary не рос бесконечно).
     */
    private fun updateSummary(chunk: List<Message>) {
        val prompt = buildString {
            if (summary.isNotBlank()) {
                appendLine("Предыдущее краткое содержание диалога:")
                appendLine(summary)
                appendLine()
                appendLine("Новые сообщения:")
            } else {
                appendLine("Сделай краткое содержание следующих сообщений (только факты):")
            }
            chunk.forEach {
                appendLine("${it.role}: ${it.content}")
            }
        }

        val response = llm.ask(
            listOf(
                Message("system", "Ты — помощник, который сжимает историю диалога. Оставляй только важные факты и суть. Не добавляй лишних слов."),
                Message("user", prompt)
            )
        )

        summary = response.content
    }

    fun clear() {
        summary = ""
    }
}