import models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Единый движок для ReAct-цикла с MCP-инструментами.
 *
 * Решает проблему: "LLM может проигнорировать инструменты и ответить из памяти".
 *
 * Механизмы принуждения:
 * 1. System prompt с жёсткими правилами
 * 2. Проверка: если запрос требует данных, но LLM не вызвала инструменты — принудительный повтор
 * 3. Проверка: если ответ противоречит вызванным инструментам — принудительный повтор
 * 4. Максимум N итераций, после чего принудительное завершение
 * 5. Fallback: если LLM упорно игнорирует — возвращаем ошибку
 */
class McpReActEngine(
    private val llm: DeepSeekClient,
    private val mcpClientManager: McpClientManager
) {
    companion object {
        private const val MAX_ITERATIONS = 10
        private val REQUIRED_DATA_KEYWORDS = listOf(
            "погод", "weather", "температур", "temperature",
            "курс", "rate", "валют",
            "новост", "news",
            "поиск", "search", "найди",
            "время", "time", "дата", "date",
            "пробк", "traffic"
        )
    }

    data class ReActResult(
        val content: String,
        val toolCalls: List<ToolCallRecord>,
        val iterationsUsed: Int,
        val wasForced: Boolean // true если пришлось принуждать LLM
    )

    /**
     * Выполнить ReAct-цикл с поддержкой многошагового рассуждения.
     *
     * LLM сама решает:
     * - Какие инструменты вызывать
     * - В каком порядке
     * - Когда достаточно данных для ответа
     *
     * @param systemPrompt системный промпт
     * @param userMessage сообщение пользователя
     * @param additionalTools дополнительные виртуальные инструменты (например, plan)
     * @param requireTools true — принудительно требовать вызова инструментов для data-запросов
     */
    suspend fun execute(
        systemPrompt: String,
        userMessage: String,
        additionalTools: List<ToolSchema>? = null,
        requireTools: Boolean = true
    ): ReActResult {
        return withContext(Dispatchers.IO) {
            mcpClientManager.clearCallHistory()

            val toolSchemas = mcpClientManager.listAgentTools().map { tool ->
                ToolSchema(
                    name = tool.name(),
                    description = tool.description() ?: "",
                    inputSchema = tool.inputSchema()
                )
            }

            val allTools = if (additionalTools != null) toolSchemas + additionalTools else toolSchemas

            val toolList = allTools.joinToString(", ") { it.name }

            // Усиленный system prompt с жёсткими правилами
            val forcedSystemPrompt = buildForcedSystemPrompt(systemPrompt, toolList, requireTools)

            val messagesJson = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", forcedSystemPrompt))
                put(JSONObject().put("role", "user").put("content", userMessage))
            }

            var response = llm.askJson(messagesJson, tools = allTools)
            var iterations = 0
            var wasForced = false

            // Основной цикл
            while (iterations < MAX_ITERATIONS) {
                val toolCalls = response.toolCalls

                // Если LLM решила ответить без вызова инструментов
                if (toolCalls.isNullOrEmpty()) {
                    val content = response.content

                    // Проверка 1: запрос требует данных, но LLM не вызвала инструменты
                    if (requireTools && requiresData(userMessage) && !contentContainsData(content)) {
                        wasForced = true
                        messagesJson.put(JSONObject().apply {
                            put("role", "user")
                            put("content", buildForceToolCallPrompt(userMessage, toolList))
                        })
                        response = llm.askJson(messagesJson, tools = allTools)
                        iterations++
                        continue
                    }

                    // Проверка 2: ответ пустой или "я не могу"
                    if (content.isBlank() || isRefusalResponse(content)) {
                        wasForced = true
                        messagesJson.put(JSONObject().apply {
                            put("role", "user")
                            put("content", buildForceToolCallPrompt(userMessage, toolList))
                        })
                        response = llm.askJson(messagesJson, tools = allTools)
                        iterations++
                        continue
                    }

                    // Проверка 3: ответ выглядит как галлюцинация (слишком общий, без конкретики)
                    if (looksLikeHallucination(content, userMessage)) {
                        wasForced = true
                        messagesJson.put(JSONObject().apply {
                            put("role", "user")
                            put("content", buildForceToolCallPrompt(userMessage, toolList))
                        })
                        response = llm.askJson(messagesJson, tools = allTools)
                        iterations++
                        continue
                    }

                    // Валидный ответ — выходим
                    break
                }

                // LLM вызвала инструменты — обрабатываем
                iterations++
                val assistantJson = JSONObject().apply {
                    put("role", "assistant")
                    put("content", JSONObject.NULL)
                    put("tool_calls", buildToolCallsJson(toolCalls))
                }
                messagesJson.put(assistantJson)

                for (tc in toolCalls) {
                    val result = try {
                        val jsonArgs = JSONObject(tc.function.arguments)
                        val args = jsonArgs.keySet().associate { it to jsonArgs.get(it) }
                        mcpClientManager.callTool(tc.function.name, args)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    messagesJson.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", tc.id)
                        put("content", result)
                    })
                }

                // После обработки инструментов — даём LLM возможность подумать и решить,
                // нужны ли ещё вызовы или можно ответить
                response = llm.askJson(messagesJson, tools = allTools)
            }

            // Если после всех итераций ответ всё ещё пустой или отказ
            val finalContent = response.content
            val safeContent = when {
                finalContent.isNotBlank() && !isRefusalResponse(finalContent) -> finalContent
                wasForced -> buildForcedResponse(userMessage, mcpClientManager.getCallHistory())
                else -> "❌ Не удалось выполнить запрос. Пожалуйста, уточните, что именно вы хотите узнать."
            }

            ReActResult(
                content = safeContent,
                toolCalls = mcpClientManager.getCallHistory(),
                iterationsUsed = iterations,
                wasForced = wasForced
            )
        }
    }

    /**
     * Построить усиленный system prompt.
     */
    private fun buildForcedSystemPrompt(
        basePrompt: String,
        toolList: String,
        requireTools: Boolean
    ): String {
        val forceRule = if (requireTools) {
            """
            ⚠️ ВАЖНОЕ ПРАВИЛО ⚠️
            У тебя есть доступ к инструментам: $toolList.
            
            ЕСЛИ пользователь просит:
            - погоду, температуру, прогноз
            - курс валют, курсы
            - новости, поиск в интернете
            - любые актуальные данные
            - информацию, которой у тебя нет в обучении
            
            Ты ОБЯЗАНА вызвать соответствующий инструмент.
            НЕЛЬЗЯ отвечать из памяти или говорить "я не могу".
            НЕЛЬЗЯ придумывать данные.
            
            Если ты не знаешь, какой инструмент вызвать — вызови 'web-search' или 'search'.
            Если инструмент вернул ошибку — сообщи об этом пользователю.
            
            После вызова инструмента и получения результата — сформируй ответ на основе полученных данных.
            
            🔄 МНОГОШАГОВЫЕ ЦЕПОЧКИ:
            Если для выполнения запроса нужно несколько шагов, ты можешь:
            1. Вызвать первый инструмент
            2. Получить результат
            3. Использовать результат как аргумент для следующего инструмента
            4. Повторять, пока не получишь все нужные данные
            5. Сформировать ответ
            
            Пример цепочки для веб-поиска:
            Шаг 1: web-search(query="London attractions") → получаешь URL
            Шаг 2: fetch-url(text="...URL из шага 1...") → получаешь контент
            Шаг 3: summarize(text="...контент из шага 2...") → получаешь саммари
            Шаг 4: отвечаешь пользователю с результатами
            
            Пример цепочки для погоды + планирования:
            Шаг 1: get-current-weather(city="London") → получаешь погоду
            Шаг 2: schedule-mcp-tool(name="London Weather", mcp_tool="get-current-weather", ...) → создаёшь задачу
            Шаг 3: отвечаешь пользователю с погодой и информацией о задаче
            
            Ты можешь делать сколько угодно шагов, пока не соберёшь все данные.
            """
        } else ""

        return """
            $basePrompt
            $forceRule
        """.trimIndent()
    }

    /**
     * Проверить, требует ли запрос актуальных данных (погода, новости и т.д.).
     */
    private fun requiresData(request: String): Boolean {
        val lower = request.lowercase()
        return REQUIRED_DATA_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Проверить, содержит ли ответ конкретные данные (температуру, курс и т.д.).
     */
    private fun contentContainsData(content: String): Boolean {
        val lower = content.lowercase()
        // Проверяем наличие конкретных данных
        val hasNumbers = Regex("""\d+[°°]?""").containsMatchIn(content)
        val hasPercent = content.contains("%")
        val hasCurrency = Regex("""\d+[\s]*(руб|\$|€|₽|usd|eur)""", RegexOption.IGNORE_CASE).containsMatchIn(content)
        val hasTemperature = Regex("""[-+]?\d+°[CFcf]?""").containsMatchIn(content)
        val hasDate = Regex("""\d{1,2}[./]\d{1,2}[./]\d{2,4}""").containsMatchIn(content)
        val hasCity = Regex("""[А-Я][а-я]+[,\s]+""").containsMatchIn(content)

        return hasNumbers || hasPercent || hasCurrency || hasTemperature || hasDate
    }

    /**
     * Проверить, является ли ответ отказом.
     */
    private fun isRefusalResponse(content: String): Boolean {
        val lower = content.lowercase()
        val refusalPhrases = listOf(
            "я не могу", "не могу", "извините", "простите",
            "у меня нет доступа", "нет доступа",
            "я не имею доступа", "не имею доступа",
            "я не знаю", "не знаю",
            "у меня нет информации", "нет информации",
            "я не могу предоставить", "не могу предоставить",
            "i cannot", "i can't", "i don't have access",
            "i don't know", "sorry"
        )
        return refusalPhrases.any { lower.contains(it) }
    }

    /**
     * Проверить, выглядит ли ответ как галлюцинация.
     */
    private fun looksLikeHallucination(content: String, request: String): Boolean {
        val lower = content.lowercase()
        val requestLower = request.lowercase()

        // Если запрос про погоду, а ответ не содержит чисел — галлюцинация
        if (requestLower.contains("погод") || requestLower.contains("weather")) {
            if (!Regex("""\d+""").containsMatchIn(content)) {
                return true
            }
        }

        // Если запрос про курс валют, а ответ не содержит чисел — галлюцинация
        if (requestLower.contains("курс") || requestLower.contains("rate")) {
            if (!Regex("""\d+[.,]\d+""").containsMatchIn(content)) {
                return true
            }
        }

        // Если запрос про новости, а ответ слишком короткий — галлюцинация
        if (requestLower.contains("новост") || requestLower.contains("news")) {
            if (content.length < 50) {
                return true
            }
        }

        return false
    }

    /**
     * Построить промпт для принудительного вызова инструмента.
     */
    private fun buildForceToolCallPrompt(userMessage: String, toolList: String): String {
        return """
            [СИСТЕМНОЕ СООБЩЕНИЕ]
            Ты НЕ ответила на запрос пользователя, а дала общий ответ без конкретных данных.
            
            Запрос пользователя: "$userMessage"
            
            Доступные инструменты: $toolList
            
            Ты ОБЯЗАНА вызвать один или несколько инструментов, чтобы получить актуальные данные.
            После получения данных — сформируй конкретный ответ с цифрами и фактами.
            
            НЕЛЬЗЯ:
            - Отвечать "я не могу"
            - Придумывать данные
            - Давать общие ответы без конкретики
            - Игнорировать инструменты
            
            Вызови инструмент СЕЙЧАС.
        """.trimIndent()
    }

    /**
     * Построить ответ, если LLM так и не вызвала инструменты.
     */
    private fun buildForcedResponse(userMessage: String, callHistory: List<ToolCallRecord>): String {
        return buildString {
            appendLine("⚠️ Ассистент не смог самостоятельно обработать запрос.")
            appendLine()
            appendLine("📊 Были выполнены следующие вызовы инструментов:")
            if (callHistory.isEmpty()) {
                appendLine("  (инструменты не вызывались)")
            } else {
                callHistory.forEachIndexed { i, call ->
                    val icon = if (call.isError) "❌" else "✅"
                    appendLine("  $icon ${call.serverName}:${call.toolName}")
                    if (call.isError) {
                        appendLine("     Ошибка: ${call.result.take(100)}")
                    }
                }
            }
            appendLine()
            appendLine("💡 Пожалуйста, уточните запрос или попробуйте переформулировать:")
            appendLine("  • Укажите конкретный город для погоды")
            appendLine("  • Уточните, что именно искать")
            appendLine("  • Попросите вызвать конкретный инструмент")
        }
    }

    private fun buildToolCallsJson(toolCalls: List<ToolCall>): JSONArray {
        return JSONArray().apply {
            toolCalls.forEach { tc ->
                put(JSONObject().apply {
                    put("id", tc.id)
                    put("type", tc.type)
                    put("function", JSONObject().apply {
                        put("name", tc.function.name)
                        put("arguments", tc.function.arguments)
                    })
                })
            }
        }
    }

    fun buildToolTrace(history: List<ToolCallRecord>): String {
        if (history.isEmpty()) return ""

        val serverIcons = mapOf(
            "weather" to "🌤",
            "scheduler" to "⏰",
            "pipeline" to "🔧"
        )
        val servers = history.map { it.serverName }.distinct()

        val depEdges = mutableListOf<Pair<Int, Int>>()
        val dataArgs = setOf("text", "content")
        for (i in 1 until history.size) {
            val args = history[i].arguments
            for (key in dataArgs) {
                val value = args[key]?.toString() ?: continue
                if (value.length > 80) {
                    for (j in i - 1 downTo 0) {
                        if (!history[j].isError && value.contains(history[j].resultPreview.take(30))) {
                            depEdges.add(j to i)
                            break
                        }
                    }
                    break
                }
            }
        }

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("┌─────────────────────────────────────────────────┐")
        sb.appendLine("│ 📊 MCP Tool Execution Trace: ${servers.size} servers, ${history.size} calls")
        sb.appendLine("└─────────────────────────────────────────────────┘")
        sb.appendLine()
        sb.appendLine("  Servers: ${servers.joinToString(", ") { "${serverIcons[it] ?: "🖥"} $it" }}")
        sb.appendLine()

        for ((idx, call) in history.withIndex()) {
            val num = idx + 1
            val icon = if (call.isError) "✗" else "✓"
            val srvIcon = serverIcons[call.serverName] ?: "🖥"

            sb.appendLine("  $num. [$icon] $srvIcon ${call.serverName}:${call.toolName}")

            val incoming = depEdges.filter { it.second == idx }
            if (incoming.isNotEmpty()) {
                val fromLabels = incoming.joinToString(", ") { "#${it.first + 1}" }
                sb.appendLine("     ← data from $fromLabels")
            }

            val nonDataArgs = call.arguments.filterKeys { it !in dataArgs }
                .mapValues { it.value.toString() }
                .filter { it.value.length < 60 }
            if (nonDataArgs.isNotEmpty()) {
                sb.appendLine("     args: ${nonDataArgs.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }

            if (!call.isError && call.resultPreview.isNotEmpty()) {
                sb.appendLine("     → ${call.resultPreview.take(80)}")
            } else if (call.isError) {
                sb.appendLine("     → ERROR: ${call.resultPreview.take(80)}")
            }
        }

        sb.appendLine()
        val errorCount = history.count { it.isError }
        sb.appendLine("  ─── Sequential | ${servers.size} servers | $errorCount errors ───")
        return sb.toString()
    }
}
