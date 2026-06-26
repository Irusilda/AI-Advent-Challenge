package models

data class UserProfile(
    val name: String = "Ирина",
    val style: String = "официальный",
    val format: String = "краткий",
    val constraints: List<String> = listOf("использовать эмодзи")
) {
    fun toInstruction(): String {
        val sb = StringBuilder()
        sb.append("Профиль пользователя: имя — $name, стиль общения — $style, формат ответа — $format.")
        if (constraints.isNotEmpty()) {
            sb.append(" Ограничения: ")
            sb.append(constraints.joinToString("; "))
        }
        return sb.toString()
    }
}