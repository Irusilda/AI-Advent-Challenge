import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val server = WeatherMcpServer()
    server.start()
    server.await()
}

class WeatherMcpServer {

    private var server: McpSyncServer? = null
    private val latch = CountDownLatch(1)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun start(): McpSyncServer {
        val transport = StdioServerTransportProvider(McpJsonDefaults.getMapper())

        server = McpServer.sync(transport)
            .serverInfo("weather-server", "1.0.0")
            .tools(createToolSpecs())
            .build()

        System.err.println("✅ Weather MCP Server started (Stdio)")
        return server!!
    }

    fun await() {
        latch.await()
    }

    private fun createToolSpecs(): List<McpServerFeatures.SyncToolSpecification> {
        return listOf(currentWeatherTool(), forecastTool())
    }

    private fun currentWeatherTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "city" to mapOf("type" to "string", "description" to "City name")
            ),
            "required" to listOf("city")
        )
        val tool = McpSchema.Tool.builder("get-current-weather", schema)
            .description("Get current weather conditions for a city via wttr.in")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val city = request.arguments()?.get("city") as? String
                    ?: return@callHandler error("Missing required argument 'city'")
                val result = fetchWeather(city)
                success(result)
            }
            .build()
    }

    private fun forecastTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "city" to mapOf("type" to "string", "description" to "City name"),
                "days" to mapOf("type" to "integer", "description" to "Number of days (1-7, default: 3)")
            ),
            "required" to listOf("city")
        )
        val tool = McpSchema.Tool.builder("get-forecast", schema)
            .description("Get weather forecast for a city for the next N days via wttr.in")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: return@callHandler error("No arguments")
                val city = args["city"] as? String ?: return@callHandler error("Missing 'city'")
                val days = (args["days"] as? Number)?.toInt()?.coerceIn(1, 7) ?: 3
                val result = fetchForecast(city, days)
                success(result)
            }
            .build()
    }

    private fun fetchWeather(city: String): String {
        return try {
            val encoded = URLEncoder.encode(city, "UTF-8")
            val resp = httpClient.newCall(
                Request.Builder().url("https://wttr.in/$encoded?format=%C+%t+%h+%w+%P").get().build()
            ).execute()
            val body = resp.body?.string()?.trim() ?: return "No response from weather service"
            if (!resp.isSuccessful) return "Weather API error: ${resp.code}"
            if (body.contains("Unknown location")) return "City not found: $city"
            val parts = body.split("\\s+".toRegex())
            var idx = 0
            val conditionParts = mutableListOf<String>()
            while (idx < parts.size) {
                val p = parts[idx]
                if (p.startsWith("+") || p.startsWith("-") || p.endsWith("°C")) break
                conditionParts.add(p)
                idx++
            }
            val condition = conditionParts.joinToString(" ").ifEmpty { "?" }
            val temp = parts.getOrElse(idx) { "?" }; idx++
            val humidity = parts.getOrElse(idx) { "?" }; idx++
            val wind = parts.drop(idx).dropLast(1).joinToString(" ")
            val pressure = parts.lastOrNull() ?: "?"
            buildString {
                appendLine("Weather in $city:")
                appendLine("  Conditions: $condition")
                appendLine("  Temperature: $temp")
                appendLine("  Humidity: $humidity")
                appendLine("  Wind: $wind")
                appendLine("  Pressure: $pressure")
            }
        } catch (e: Exception) {
            "Error fetching weather: ${e.message}"
        }
    }

    private fun fetchForecast(city: String, days: Int): String {
        return try {
            val encoded = URLEncoder.encode(city, "UTF-8")
            val resp = httpClient.newCall(
                Request.Builder().url("https://wttr.in/$encoded?format=j1").get().build()
            ).execute()
            val body = resp.body?.string() ?: return "No response from weather service"
            if (!resp.isSuccessful) return "Weather API error: ${resp.code}"

            val json = org.json.JSONObject(body)
            val forecasts = json.getJSONArray("weather")

            buildString {
                appendLine("Forecast for $city (next $days days):")
                for (i in 0 until minOf(days, forecasts.length())) {
                    val day = forecasts.getJSONObject(i)
                    val date = day.optString("date", "?")
                    val maxTemp = day.optString("maxtempC", "?")
                    val minTemp = day.optString("mintempC", "?")
                    val avgTemp = day.optString("avgtempC", "?")
                    val maxWind = day.optString("maxwindKmph", "?")
                    val sunHours = day.optString("sunHour", "?")
                    val uvIndex = day.optString("uvIndex", "?")
                    val desc = day.optJSONArray("hourly")
                        ?.optJSONObject(0)
                        ?.optJSONArray("weatherDesc")
                        ?.optJSONObject(0)
                        ?.optString("value", "")

                    appendLine("  $date: $desc")
                    appendLine("    Temperature: $minTemp – $maxTemp°C (avg $avgTemp)")
                    appendLine("    Wind: $maxWind km/h, Sun: $sunHours h, UV: $uvIndex")
                }
            }
        } catch (e: Exception) {
            "Forecast temporarily unavailable: ${e.message}"
        }
    }

    private fun success(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder().addContent(McpSchema.TextContent.builder(text).build()).build()

    private fun error(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder().addContent(McpSchema.TextContent.builder(text).build()).isError(true).build()
}
