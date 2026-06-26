package contextstrategy.impl

import contextstrategy.ContextStrategy
import models.Message

class SlidingWindowStrategy(
    private val windowSize: Int = 4,
    systemMessage: Message
) : ContextStrategy {
    private val messages = mutableListOf<Message>().apply { add(systemMessage) }

    override fun addUserMessage(message: Message) {
        messages.add(message)
        trim()
    }

    override fun addAssistantMessage(message: Message) {
        messages.add(message)
        trim()
    }

    private fun trim() {
        if (messages.size > windowSize) {
            val system = messages.first()
            val tail = messages.takeLast(windowSize - 1)
            messages.clear()
            messages.add(system)
            messages.addAll(tail)
        }
    }

    override fun buildContext(): List<Message> = messages.toList()

    override fun clear() {
        val system = messages.firstOrNull() ?: Message("system", "Ты — полезный ассистент. Отвечай кратко и по делу на русском языке.")
        messages.clear()
        messages.add(system)
    }

    override fun loadHistory(messages: List<Message>) {
        this.messages.clear()
        this.messages.addAll(messages)
        trim()
    }

    override fun getHistory(): List<Message> = messages.toList()

    override fun getName(): String = "SlidingWindow(keep=$windowSize)"
}