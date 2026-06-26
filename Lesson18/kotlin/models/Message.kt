package models

data class Message(
    val role: String,
    val content: String,
    val toolCallId: String? = null
) {
    companion object {
        fun user(content: String) = Message("user", content)
        fun assistant(content: String) = Message("assistant", content)
        fun tool(toolCallId: String, content: String) = Message("tool", content, toolCallId = toolCallId)
        fun system(content: String) = Message("system", content)
    }
}
