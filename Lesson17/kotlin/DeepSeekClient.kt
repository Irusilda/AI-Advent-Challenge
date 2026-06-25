import models.DeepSeekResponse
import models.GenerationConfig
import models.Message
import models.ToolCall
import models.ToolCallFunction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ToolSchema(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)

class DeepSeekClient(private val apiKey: String, val contextLimit: Int = 1000000) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    val costPer1kPromptTokens = 0.00014
    private val costPer1kCompletionTokens = 0.00028

    fun ask(
        messages: List<Message>,
        config: GenerationConfig = GenerationConfig(),
        tools: List<ToolSchema>? = null
    ): DeepSeekResponse {
        val messagesArray = JSONArray().apply {
            messages.forEach { msg ->
                val obj = JSONObject().put("role", msg.role)
                if (msg.role == "tool") {
                    obj.put("tool_call_id", msg.toolCallId)
                }
                obj.put("content", msg.content)
                put(obj)
            }
        }
        return askJson(messagesArray, config, tools)
    }

    fun askJson(
        messagesArray: JSONArray,
        config: GenerationConfig = GenerationConfig(),
        tools: List<ToolSchema>? = null
    ): DeepSeekResponse {
        val startTime = System.currentTimeMillis()

        val requestBody = JSONObject()
            .put("model", "deepseek-v4-flash")
            .put("messages", messagesArray)
            .put("temperature", config.temperature)
            .put("top_p", config.topP)
            .put("max_tokens", config.maxTokens)

        if (tools != null && tools.isNotEmpty()) {
            requestBody.put("tools", buildToolsArray(tools))
            requestBody.put("tool_choice", "auto")
        }

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Ошибка API: ${response.code} - ${response.message}"
            }

            val json = JSONObject(response.body!!.string())
            val messageJson = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")

            val answerText = messageJson.optString("content", "")
                .takeIf { it != "null" } ?: ""

            val toolCalls = messageJson.optJSONArray("tool_calls")?.let { parseToolCalls(it) }

            val usage = json.getJSONObject("usage")
            val promptTokens = usage.getInt("prompt_tokens")
            val completionTokens = usage.getInt("completion_tokens")
            val totalTokens = usage.getInt("total_tokens")

            val elapsedMs = System.currentTimeMillis() - startTime
            val costUsd = calculateCost(promptTokens, completionTokens)

            return DeepSeekResponse(
                answerText,
                totalTokens,
                promptTokens,
                completionTokens,
                elapsedMs,
                costUsd,
                toolCalls
            )
        }
    }

    private fun buildToolsArray(tools: List<ToolSchema>): JSONArray {
        return JSONArray().apply {
            tools.forEach { tool ->
                put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        @Suppress("UNCHECKED_CAST")
                        put("parameters", JSONObject(tool.inputSchema as Map<String, Any>))
                    })
                })
            }
        }
    }

    private fun parseToolCalls(array: JSONArray): List<ToolCall> {
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val func = obj.getJSONObject("function")
            ToolCall(
                id = obj.getString("id"),
                type = obj.getString("type"),
                function = ToolCallFunction(
                    name = func.getString("name"),
                    arguments = func.getString("arguments")
                )
            )
        }
    }

    private fun calculateCost(promptTokens: Int, completionTokens: Int): Double {
        return (promptTokens / 1000.0) * costPer1kPromptTokens +
                (completionTokens / 1000.0) * costPer1kCompletionTokens
    }
}
