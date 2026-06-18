package contextstrategy.impl

import DeepSeekClient
import contextstrategy.ContextStrategy
import models.Message

class StickyFactsStrategy(
    private val llm: DeepSeekClient,
    private val keepLastMessages: Int = 6,
    systemMessage: Message
) : ContextStrategy {
    private val facts = mutableMapOf<String, String>()
    private val recentMessages = mutableListOf<Message>()
    private val systemMsg = systemMessage

    override fun addUserMessage(message: Message) {
        recentMessages.add(message)
        updateFacts()
    }

    override fun addAssistantMessage(message: Message) {
        recentMessages.add(message)
    }

    private fun updateFacts() {
        if (recentMessages.isEmpty()) return

        val prompt = buildString {
            appendLine("Извлеки важные факты из диалога ниже. Факты — это ключевая информация: цели, ограничения, предпочтения, решения, договорённости.")
            appendLine("Текущие факты (если есть):")
            if (facts.isEmpty()) appendLine("(нет)")
            else facts.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine("\nНовые сообщения:")
            recentMessages.takeLast(4).forEach { msg ->
                appendLine("${msg.role}: ${msg.content}")
            }
            appendLine("\nВерни обновлённый список фактов в формате ключ: значение, каждый с новой строки. Не добавляй лишних пояснений.")
        }

        val response = llm.ask(
            listOf(
                Message(
                    "system",
                    "Ты — помощник, который выделяет ключевые факты из диалога. Отвечай только перечислением фактов в формате 'ключ: значение'."
                ),
                Message("user", prompt)
            )
        )

        val newFacts = mutableMapOf<String, String>()
        response.content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.contains(":")) {
                val colonIndex = trimmed.indexOf(':')
                val key = trimmed.substring(0, colonIndex).trim()
                val value = trimmed.substring(colonIndex + 1).trim()
                if (key.isNotBlank()) newFacts[key] = value
            }
        }
        facts.clear()
        facts.putAll(newFacts)
    }

    override fun buildContext(): List<Message> {
        val result = mutableListOf<Message>()

        val systemContent = if (facts.isNotEmpty()) {
            "${systemMsg.content}\n\nВажные факты из диалога:\n" +
                    facts.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        } else {
            systemMsg.content
        }
        result.add(Message("system", systemContent))
        val last = recentMessages.takeLast(keepLastMessages)
        result.addAll(last)
        return result
    }

    override fun clear() {
        facts.clear()
        recentMessages.clear()
    }

    override fun loadHistory(messages: List<Message>) {
        recentMessages.clear()
        recentMessages.addAll(messages.filter { it.role != "system" })
        facts.clear()
    }

    override fun getHistory(): List<Message> = recentMessages.toList()

    override fun getName(): String = "StickyFacts(facts=${facts.size}, keep=$keepLastMessages)"
}
