import java.io.File

fun main() {
    val apiKey = requireNotNull(System.getenv("DEEPSEEK_API_KEY")) {
        "DEEPSEEK_API_KEY не задана"
    }
    val agent = ChatAgent(DeepSeekClient(apiKey))
    val historyFile = "chat_history.json"
    agent.loadHistory(historyFile)
    if (File(historyFile).exists()) {
        println("(История загружена из $historyFile)")
    }

    println("Чат-агент запущен (лимит контекста ${agent.llm.contextLimit} токенов).")
    println("Команды: 'exit' — выход, 'clear' — очистить историю, 'stats' — показать статистику.")
    while (true) {
        print("Вы: ")
        val input = readlnOrNull() ?: return

        when {
            input.equals("exit", ignoreCase = true) -> {
                agent.saveHistory(historyFile)
                return
            }
            input.equals("clear", ignoreCase = true) -> {
                agent.clearHistory()
                File(historyFile).delete()
                println("История очищена.")
            }
            input.equals("stats", ignoreCase = true) -> {
                agent.printStats()
            }
            input.startsWith("load ", ignoreCase = true) -> {
                val filename = input.removePrefix("load ").trim()
                val file = File(filename)
                if (!file.exists()) {
                    println("Файл не найден: $filename")
                } else {
                    try {
                        val text = file.readText()
                        println("Загружено ${text.length} символов из $filename. Отправляю агенту...")
                        val response = agent.chat(text)
                        println("Ассистент: ${response.content}")
                        println("... статистика ...")
                        agent.saveHistory(historyFile)
                    } catch (e: Exception) {
                        println("Ошибка при загрузке файла: ${e.message}")
                    }
                }
            }
            input.startsWith("compare ") -> {

                val q = input.removePrefix("compare ")

                val result = agent.compare(q)

                println("===== FULL =====")
                println(result.fullResponse.content)
                println("tokens: ${result.fullResponse.promptTokens}")

                println("===== COMPRESSED =====")
                println(result.compressedResponse.content)
                println("tokens: ${result.compressedResponse.promptTokens}")
            }
            else -> {
                try {
                    val response = agent.chat(input)
                    println("Ассистент: ${response.content}")
                    println("┌─────────────────────────────────────────┐")
                    println("│ Токены запроса (вся история): ${response.promptTokens}  │")
                    println("│ Токены ответа: ${response.completionTokens}   │")
                    println("│ Стоимость запроса: $${"%.6f".format(response.costUsd)} │")
                    println("└─────────────────────────────────────────┘")

                    agent.saveHistory(historyFile)
                } catch (e: Exception) {
                    println("Ошибка: ${e.message}")
                    if (e.message?.contains("400") == true || e.message?.contains("context") == true) {
                        println(">>> Контекст превысил лимит модели. Попробуйте очистить историю (команда 'clear').")
                    }
                }
            }
        }
    }
}