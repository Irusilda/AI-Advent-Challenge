import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val server = ScheduledMcpServer()
    server.start()
    server.await()
}

class ScheduledMcpServer {

    private var server: McpSyncServer? = null
    private val latch = CountDownLatch(1)
    private val scheduler = Executors.newScheduledThreadPool(4)
    private val counter = AtomicInteger(0)

    private val dataDir = File("data")
    private val tasksFile = File(dataDir, "scheduled_tasks.json")
    private val resultsFile = File(dataDir, "task_results.json")

    private val activeFutures = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val tasks = ConcurrentHashMap<String, JSONObject>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        dataDir.mkdirs()
        if (!tasksFile.exists()) tasksFile.writeText("[]")
        if (!resultsFile.exists()) resultsFile.writeText("[]")
    }

    fun start(): McpSyncServer {
        val transport = StdioServerTransportProvider(McpJsonDefaults.getMapper())

        server = McpServer.sync(transport)
            .serverInfo("scheduled-server", "1.0.0")
            .tools(createToolSpecs())
            .build()

        recoverTasks()
        System.err.println("Scheduled MCP Server started (Stdio)")
        return server!!
    }

    fun await() {
        latch.await()
    }

    private fun recoverTasks() {
        val tasksArray = readJsonArray(tasksFile)
        val toRemove = mutableListOf<JSONObject>()
        for (i in 0 until tasksArray.length()) {
            val obj = tasksArray.getJSONObject(i)
            val type = obj.optString("type", "")
            val params = obj.optJSONObject("params")
            val hasUrl = params?.has("url") == true && !params.isNull("url")
            val hasMcpTool = params?.has("mcp_tool") == true && !params.isNull("mcp_tool")
            if (type == "collect" && !hasUrl && !hasMcpTool) {
                toRemove.add(obj)
                continue
            }
            if (obj.optString("status", "active") == "active") {
                val id = obj.getString("id")
                tasks[id] = obj
                scheduleTask(obj)
            }
        }
        if (toRemove.isNotEmpty()) {
            val idsToRemove = toRemove.map { it.optString("id") }.toSet()
            val cleaned = JSONArray()
            for (i in 0 until tasksArray.length()) {
                val item = tasksArray.getJSONObject(i)
                if (item.optString("id") !in idsToRemove) cleaned.put(item)
            }
            tasksFile.writeText(cleaned.toString(2))
            System.err.println("  Cleaned ${toRemove.size} stale collect tasks (no data source)")
        }
        System.err.println("  Recovered ${tasks.size} active tasks")
    }

    private fun scheduleTask(obj: JSONObject) {
        val id = obj.getString("id")
        val type = obj.optString("type", "reminder")
        val delayMin = if (obj.has("delayMinutes") && !obj.isNull("delayMinutes"))
            obj.getInt("delayMinutes") else -1
        val intervalMin = if (obj.has("intervalMinutes") && !obj.isNull("intervalMinutes"))
            obj.getInt("intervalMinutes") else -1

        val isPeriodic = type != "reminder" && intervalMin > 0
        val initialDelay = if (delayMin > 0) delayMin.toLong()
            else if (isPeriodic) intervalMin.toLong()
            else 1L

        val future = if (isPeriodic) {
            scheduler.scheduleAtFixedRate(
                { executeTask(id) },
                initialDelay, intervalMin.toLong(), TimeUnit.MINUTES
            )
        } else {
            scheduler.schedule(
                { executeTask(id) },
                initialDelay, TimeUnit.MINUTES
            )
        }

        activeFutures[id] = future
    }

    private fun executeTask(taskId: String) {
        val obj = tasks[taskId] ?: return
        val type = obj.optString("type", "reminder")
        val name = obj.optString("name", "Unnamed")
        val runCount = obj.optInt("runCount", 0) + 1
        val now = LocalDateTime.now().format(fmt)

        val result = JSONObject()
        result.put("taskId", taskId)
        result.put("taskName", name)
        result.put("type", type)
        result.put("timestamp", now)
        result.put("runNumber", runCount)

        when (type) {
            "reminder" -> {
                result.put("message", "Reminder: $name")
                result.put("triggered", true)
                tasks.remove(taskId)
            }
            "collect" -> {
                val params = obj.optJSONObject("params")
                val url = params?.optString("url", null)
                val mcpTool = params?.optString("mcp_tool", null)

                if (mcpTool != null) {
                    val mcpServer = params.optString("mcp_server", null)
                    if (mcpServer == null) {
                        result.put("error", "mcp_server is required when using mcp_tool")
                    } else {
                        try {
                            val toolArgsObj = params.optJSONObject("mcp_tool_args") ?: JSONObject()
                            val mcpParams = ServerParameters.builder("java")
                                .args("-cp", System.getProperty("java.class.path"), mcpServer)
                                .build()
                            val transport = StdioClientTransport(mcpParams, McpJsonDefaults.getMapper())
                            val client = McpClient.sync(transport)
                                .requestTimeout(java.time.Duration.ofSeconds(30))
                                .initializationTimeout(java.time.Duration.ofSeconds(30))
                                .build()
                            client.initialize()
                            val toolArgs = toolArgsObj.keySet().associate { it to toolArgsObj.get(it) }
                            val response = client.callTool(
                                McpSchema.CallToolRequest.builder(mcpTool)
                                    .arguments(toolArgs).build()
                            )
                            val text = response.content()
                                .filterIsInstance<McpSchema.TextContent>()
                                .joinToString("\n") { it.text() }
                            result.put("data", text)
                            val fields = JSONObject()
                            for (resultLine in text.lines()) {
                                val separatorIndex = resultLine.indexOf(": ")
                                if (separatorIndex > 0) {
                                    val key = resultLine.substring(0, separatorIndex).trim().lowercase()
                                    val value = resultLine.substring(separatorIndex + 2).trim()
                                    if (key.isNotEmpty() && value.isNotEmpty() && !key.contains(" ")) {
                                        fields.put(key, value)
                                    }
                                }
                            }
                            if (fields.length() > 0) result.put("fields", fields)
                            client.closeGracefully()
                        } catch (e: Exception) {
                            result.put("error", e.message)
                        }
                    }
                } else if (url != null) {
                    try {
                        val resp = httpClient.newCall(
                            Request.Builder().url(url).get().build()
                        ).execute()
                        val body = resp.body?.string() ?: "No response"
                        result.put("httpStatus", resp.code)
                        result.put("data", body.take(2000))
                        val fields = JSONObject()
                        for (resultLine in body.lines()) {
                            val separatorIndex = resultLine.indexOf(": ")
                            if (separatorIndex > 0) {
                                val key = resultLine.substring(0, separatorIndex).trim().lowercase()
                                val value = resultLine.substring(separatorIndex + 2).trim()
                                if (key.isNotEmpty() && value.isNotEmpty() && !key.contains(" ")) {
                                    fields.put(key, value)
                                }
                            }
                        }
                        if (fields.length() > 0) result.put("fields", fields)
                    } catch (e: Exception) {
                        result.put("error", e.message)
                    }
                } else {
                    result.put("error", "No data source: add url, mcp_tool+mcp_server, or use schedule-mcp-tool")
                }
            }
            "summary" -> {
                val allResults = readJsonArray(resultsFile)
                val params = obj.optJSONObject("params")
                val sourceTaskName = params?.optString("source_task_name", null)
                val filtered = if (sourceTaskName != null) {
                    filterByField(allResults, "taskName", sourceTaskName)
                } else allResults
                val byTask = mutableMapOf<String, MutableList<JSONObject>>()
                val fieldsWithTime = mutableListOf<Triple<String, JSONObject, String>>()
                for (i in 0 until filtered.length()) {
                    val record = filtered.getJSONObject(i)
                    val taskName = record.optString("taskName", "?")
                    byTask.getOrPut(taskName) { mutableListOf() }.add(record)
                    val fieldValues = record.optJSONObject("fields")
                    if (fieldValues != null) {
                        val rawTime = record.optString("timestamp", "")
                        val time = if (rawTime.length >= 16) rawTime.substring(11, 16) else rawTime
                        fieldsWithTime.add(Triple(taskName, fieldValues, time))
                    }
                }
                result.put("summary", buildString {
                    val sourceLabel = if (sourceTaskName != null) " (source: $sourceTaskName)" else ""
                    appendLine("Summary$sourceLabel: ${filtered.length()} results")
                    if (fieldsWithTime.isNotEmpty()) {
                        val grouped = mutableMapOf<String, MutableList<Pair<JSONObject, String>>>()
                        for ((taskName, fields, time) in fieldsWithTime) {
                            grouped.getOrPut(taskName) { mutableListOf() }.add(fields to time)
                        }
                        grouped.forEach { (taskName, measurements) ->
                            appendLine("  $taskName (${measurements.size}x):")
                            for ((fields, time) in measurements) {
                                val parts = fields.keySet().map { key ->
                                    val value = fields.optString(key, "")
                                    "$key $value"
                                }
                                appendLine("    $time: ${parts.joinToString(", ")}")
                            }
                        }
                    } else {
                        appendLine("  Unique tasks: ${byTask.size}")
                        if (filtered.length() > 0) {
                            val last = filtered.getJSONObject(filtered.length() - 1)
                            appendLine("  Last: ${last.optString("taskName", "?")} at ${last.optString("timestamp", "?")}")
                        }
                    }
                })
            }
        }

        if (type != "reminder") {
            obj.put("lastRunAt", now)
            obj.put("runCount", runCount)
            tasks[taskId] = obj
        }

        appendResult(result)
        saveTasks()
    }

    private fun createToolSpecs(): List<McpServerFeatures.SyncToolSpecification> {
        return listOf(
            scheduleTaskTool(),
            scheduleMcpToolTool(),
            listTasksTool(),
            cancelTaskTool(),
            getResultsTool(),
            getAggregatedTool()
        )
    }

    private fun scheduleTaskTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Task name"),
                "type" to mapOf(
                    "type" to "string",
                    "enum" to listOf("reminder", "collect", "summary"),
                    "description" to "reminder=one-shot, collect=periodic data, summary=periodic aggregation"
                ),
                "delay_minutes" to mapOf(
                    "type" to "integer", "description" to "Delay in minutes (for reminder)",
                    "default" to null
                ),
                "interval_minutes" to mapOf(
                    "type" to "integer", "description" to "Interval in minutes (for collect/summary)",
                    "default" to null
                ),
                "params" to mapOf(
                    "type" to "object", "description" to "Optional params. For collect: {\"url\":\"...\"} for HTTP data fetching. " +
                        "For summary: {\"source_task_name\":\"...\"} (required) to specify the collect task name whose data to aggregate. " +
                        "Use schedule-mcp-tool for MCP tool calls.",
                    "default" to null
                )
            ),
            "required" to listOf("name", "type")
        )

        val tool = McpSchema.Tool.builder("schedule-task", schema)
            .description("Create a scheduled task (reminder, periodic data collection, or periodic summary)")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: return@callHandler error("No arguments")
                val name = args["name"] as? String ?: return@callHandler error("Missing 'name'")
                val type = args["type"] as? String ?: return@callHandler error("Missing 'type'")
                if (type !in listOf("reminder", "collect", "summary"))
                    return@callHandler error("Invalid type: $type")

                val id = "task_${System.currentTimeMillis()}_${counter.incrementAndGet()}"
                val now = LocalDateTime.now().format(fmt)

                val delayMin = (args["delay_minutes"] as? Number)?.toInt() ?: -1
                val intervalMin = (args["interval_minutes"] as? Number)?.toInt() ?: -1
                val rawParams = args["params"]

                if (type == "summary") {
                    if (rawParams !is Map<*, *> || rawParams["source_task_name"] !is String) {
                        return@callHandler error("For summary tasks, params.source_task_name (the collect task name) is required")
                    }
                }

                if (type == "collect") {
                    if (rawParams !is Map<*, *> || rawParams["url"] !is String || (rawParams["url"] as String).isBlank()) {
                        return@callHandler error(
                            "For collect tasks, params must contain {\"url\":\"...\"}. " +
                            "Use schedule-mcp-tool for MCP tool calls (weather, rates, etc.)."
                        )
                    }
                }

                val paramsObj = if (rawParams is Map<*, *>) {
                    val paramsJson = JSONObject()
                    val metaKeys = setOf("mapType", "empty", "class")
                    rawParams.forEach { (key, value) ->
                        val ks = key.toString()
                        if (ks !in metaKeys) paramsJson.put(ks, value)
                    }
                    paramsJson
                } else null

                val obj = JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("type", type)
                    put("delayMinutes", if (delayMin > 0) delayMin else JSONObject.NULL)
                    put("intervalMinutes", if (intervalMin > 0) intervalMin else JSONObject.NULL)
                    put("status", "active")
                    put("params", paramsObj ?: JSONObject.NULL)
                    put("createdAt", now)
                    put("lastRunAt", JSONObject.NULL)
                    put("runCount", 0)
                }

                tasks[id] = obj
                appendTask(obj)
                scheduleTask(obj)

                success("""{"id":"$id","name":"$name","type":"$type","status":"active"}""")
            }
            .build()
    }

    private fun scheduleMcpToolTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Task name"),
                "mcp_tool" to mapOf("type" to "string", "description" to "Tool name to call (e.g. get-current-weather)"),
                "mcp_server" to mapOf("type" to "string", "description" to "Server main class (e.g. WeatherMcpServerKt)"),
                "interval_minutes" to mapOf("type" to "integer", "description" to "Interval in minutes"),
                "tool_args" to mapOf("type" to "string", "description" to "Tool arguments as key=value pairs (e.g. city=Москва, days=3)", "default" to null),
                "mcp_tool_args" to mapOf("type" to "object", "description" to "Additional tool arguments (JSON object)", "default" to null),
                "delay_minutes" to mapOf("type" to "integer", "description" to "Initial delay (default: interval)", "default" to null)
            ),
            "required" to listOf("name", "mcp_tool", "mcp_server", "interval_minutes")
        )

        val tool = McpSchema.Tool.builder("schedule-mcp-tool", schema)
            .description("Schedule periodic MCP tool calls")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: return@callHandler error("No arguments")
                val name = args["name"] as? String ?: return@callHandler error("Missing 'name'")
                val mcpTool = args["mcp_tool"] as? String ?: return@callHandler error("Missing 'mcp_tool'")
                val mcpServer = args["mcp_server"] as? String ?: return@callHandler error("Missing 'mcp_server'")
                val intervalMin = (args["interval_minutes"] as? Number)?.toInt() ?: return@callHandler error("Missing 'interval_minutes'")
                val delayMin = (args["delay_minutes"] as? Number)?.toInt() ?: intervalMin
                val rawArgs = args["mcp_tool_args"]
                val toolArgsStr = args["tool_args"] as? String

                val toolArgsObj = if (rawArgs is Map<*, *>) {
                    val parsedJson = JSONObject()
                    rawArgs.forEach { (key, value) -> parsedJson.put(key.toString(), value) }
                    parsedJson
                } else null

                var finalArgs = if (toolArgsStr != null) {
                    val mergedArgs = toolArgsObj ?: JSONObject()
                    for (part in toolArgsStr.split(",")) {
                        val separatorPos = part.indexOf("=")
                        if (separatorPos > 0) mergedArgs.put(part.substring(0, separatorPos).trim(), part.substring(separatorPos + 1).trim())
                    }
                    mergedArgs
                } else toolArgsObj

                val knownKeys = setOf("name", "mcp_tool", "mcp_server", "interval_minutes", "delay_minutes", "tool_args", "mcp_tool_args")
                for ((key, value) in args) {
                    if (key !in knownKeys && value is String) {
                        if (finalArgs == null) finalArgs = JSONObject()
                        finalArgs.put(key, value)
                    }
                }

                val id = "task_${System.currentTimeMillis()}_${counter.incrementAndGet()}"
                val now = LocalDateTime.now().format(fmt)

                val paramsObj = JSONObject().apply {
                    put("mcp_tool", mcpTool)
                    put("mcp_server", mcpServer)
                    if (finalArgs != null) put("mcp_tool_args", finalArgs)
                }

                val obj = JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("type", "collect")
                    put("delayMinutes", delayMin)
                    put("intervalMinutes", intervalMin)
                    put("status", "active")
                    put("params", paramsObj)
                    put("createdAt", now)
                    put("lastRunAt", JSONObject.NULL)
                    put("runCount", 0)
                }

                tasks[id] = obj
                appendTask(obj)
                scheduleTask(obj)

                success("""{"id":"$id","name":"$name","type":"collect","mcp_tool":"$mcpTool","status":"active"}""")
            }
            .build()
    }

    private fun listTasksTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filter_status" to mapOf(
                    "type" to "string", "description" to "Filter by status: active, completed, cancelled (optional)",
                    "default" to null
                )
            )
        )

        val tool = McpSchema.Tool.builder("list-tasks", schema)
            .description("List all scheduled tasks, optionally filtered by status")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: emptyMap()
                val filter = args["filter_status"] as? String

                val all = readJsonArray(tasksFile)
                val filtered = if (filter != null) {
                    JSONArray().apply {
                        for (i in 0 until all.length()) {
                            val filteredTask = all.getJSONObject(i)
                            if (filteredTask.optString("status") == filter) put(filteredTask)
                        }
                    }
                } else all

                val output = StringBuilder()
                output.appendLine("Tasks (${filtered.length()}):")
                for (i in 0 until filtered.length()) {
                    val task = filtered.getJSONObject(i)
                    output.appendLine("  [${task.getString("id")}] ${task.optString("name", "?")} | " +
                        "type: ${task.optString("type", "?")} | " +
                        "status: ${task.optString("status", "?")} | " +
                        "runs: ${task.optInt("runCount", 0)}")
                }
                success(output.toString())
            }
            .build()
    }

    private fun cancelTaskTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task_id" to mapOf("type" to "string", "description" to "Task ID to cancel")
            ),
            "required" to listOf("task_id")
        )

        val tool = McpSchema.Tool.builder("cancel-task", schema)
            .description("Cancel a scheduled task by its ID")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: return@callHandler error("No arguments")
                val taskId = args["task_id"] as? String ?: return@callHandler error("Missing 'task_id'")

                activeFutures[taskId]?.cancel(false)
                activeFutures.remove(taskId)

                val obj = tasks[taskId]
                if (obj != null) {
                    obj.put("status", "cancelled")
                    obj.put("cancelledAt", LocalDateTime.now().format(fmt))
                    saveTasks()
                    success("""{"id":"$taskId","status":"cancelled"}""")
                } else {
                    error("Task not found: $taskId")
                }
            }
            .build()
    }

    private fun getResultsTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task_id" to mapOf(
                    "type" to "string", "description" to "Filter by task ID (optional)",
                    "default" to null
                ),
                "limit" to mapOf(
                    "type" to "integer", "description" to "Max results to return (default: 20)",
                    "default" to 20
                )
            )
        )

        val tool = McpSchema.Tool.builder("get-results", schema)
            .description("Get execution results, optionally filtered by task ID")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: emptyMap()
                val taskId = args["task_id"] as? String
                val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 20

                val all = readJsonArray(resultsFile)
                val filtered = if (taskId != null) filterByField(all, "taskId", taskId) else all

                val total = filtered.length()
                val show = minOf(limit, total)

                val output = StringBuilder()
                output.appendLine("Results (showing $show of $total):")
                for (i in (total - show) until total) {
                    val record = filtered.getJSONObject(i)
                    output.appendLine("  #${record.optInt("runNumber", 0)} [${record.optString("timestamp", "?")}] " +
                        "${record.optString("taskName", "?")} (${record.optString("type", "?")})")
                    record.optString("error", null)?.let { output.appendLine("    Error: $it") }
                    record.optString("message", null)?.let { output.appendLine("    Message: $it") }
                    record.optString("data", null)?.let { output.appendLine("    Data: ${it.take(500)}") }
                    record.optString("summary", null)?.let { output.appendLine("    ${it.take(2000)}") }
                }
                success(output.toString())
            }
            .build()
    }

    private fun getAggregatedTool(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task_id" to mapOf(
                    "type" to "string", "description" to "Filter by task ID (optional)",
                    "default" to null
                )
            )
        )

        val tool = McpSchema.Tool.builder("get-aggregated", schema)
            .description("Get aggregated summary of all task executions")
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments() ?: emptyMap()
                val filterTaskId = args["task_id"] as? String

                val allTasks = readJsonArray(tasksFile)
                val allResults = readJsonArray(resultsFile)

                val activeCount = (0 until allTasks.length()).count { allTasks.getJSONObject(it).optString("status") == "active" }
                val completedCount = (0 until allTasks.length()).count { allTasks.getJSONObject(it).optString("status") == "completed" }

                val filteredResults = if (filterTaskId != null) filterByField(allResults, "taskId", filterTaskId) else allResults

                val byType = mutableMapOf<String, Int>()
                for (i in 0 until filteredResults.length()) {
                    val taskType = filteredResults.getJSONObject(i).optString("type", "unknown")
                    byType[taskType] = (byType[taskType] ?: 0) + 1
                }

                val lastResults = JSONArray()
                val start = maxOf(0, filteredResults.length() - 5)
                for (i in start until filteredResults.length()) {
                    lastResults.put(filteredResults.getJSONObject(i))
                }

                val output = StringBuilder()
                output.appendLine("---- Aggregated Summary ----")
                output.appendLine("Tasks: $activeCount active, $completedCount completed, ${allTasks.length()} total")
                output.appendLine("Total results: ${filteredResults.length()}")
                output.appendLine()
                output.appendLine("Results by type:")
                byType.forEach { (type, count) ->
                    output.appendLine("  $type: $count")
                }
                output.appendLine()
                if (lastResults.length() > 0) {
                    output.appendLine("Last ${lastResults.length()} results:")
                    for (i in 0 until lastResults.length()) {
                        val record = lastResults.getJSONObject(i)
                        output.appendLine("  [${record.optString("timestamp", "?")}] ${record.optString("taskName", "?")} (#${record.optInt("runNumber", 0)})")
                    }
                }
                success(output.toString())
            }
            .build()
    }

    @Synchronized
    private fun readJsonArray(file: File): JSONArray {
        return try {
            JSONArray(file.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    @Synchronized
    private fun appendTask(obj: JSONObject) {
        val existingTasks = readJsonArray(tasksFile)
        existingTasks.put(obj)
        tasksFile.writeText(existingTasks.toString(2))
    }

    @Synchronized
    private fun saveTasks() {
        val allTasks = JSONArray()
        tasks.values.forEach { allTasks.put(it) }
        tasksFile.writeText(allTasks.toString(2))
    }

    @Synchronized
    private fun appendResult(obj: JSONObject) {
        val existingResults = readJsonArray(resultsFile)
        existingResults.put(obj)
        resultsFile.writeText(existingResults.toString(2))
    }

    private fun filterByField(jsonArray: JSONArray, field: String, value: String): JSONArray {
        return JSONArray().apply {
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                if (item.optString(field) == value) put(item)
            }
        }
    }

    private fun success(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.TextContent.builder(text).build())
            .build()

    private fun error(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.TextContent.builder(text).build())
            .isError(true)
            .build()
}
