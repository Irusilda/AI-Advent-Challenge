import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import java.time.Duration

data class ServerConfig(
    val mainClass: String,
    val name: String
)

data class ToolCallRecord(
    val order: Int,
    val toolName: String,
    val serverName: String,
    val arguments: Map<String, Any?>,
    val isError: Boolean,
    val result: String,
    val resultPreview: String
)

class McpClientManager {

    private data class ServerConnection(
        val config: ServerConfig,
        val client: McpSyncClient
    )

    private val connections = mutableListOf<ServerConnection>()
    private val toolIndex = mutableMapOf<String, ServerConnection>() // toolName -> connection
    private var initialized = false

    private val callHistory = mutableListOf<ToolCallRecord>()
    private var callCounter = 0

    fun connectAll(servers: List<ServerConfig>) {
        if (initialized) return
        val classpath = System.getProperty("java.class.path")

        for (cfg in servers) {
            try {
                val params = ServerParameters.builder("java")
                    .args("-cp", classpath, cfg.mainClass)
                    .build()

                val transport = StdioClientTransport(params, McpJsonDefaults.getMapper())
                val client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .initializationTimeout(Duration.ofSeconds(60))
                    .build()

                client.initialize()

                val tools = client.listTools().tools()
                for (tool in tools) {
                    toolIndex[tool.name()] = ServerConnection(cfg, client)
                }

                connections.add(ServerConnection(cfg, client))
                println("  Connected to ${cfg.name} (${tools.size} tools)")
            } catch (e: Exception) {
                System.err.println("  Failed to connect to ${cfg.name}: ${e.message}")
            }
        }

        initialized = true
        println(" MCP Client Manager: ${connections.size} servers, ${toolIndex.size} tools")
    }

    fun listAllTools(): List<McpSchema.Tool> {
        val tools = mutableListOf<McpSchema.Tool>()
        for (conn in connections) {
            try {
                tools.addAll(conn.client.listTools().tools())
            } catch (_: Exception) { }
        }
        return tools
    }

    /**
     * Возвращает инструменты, доступные агенту (исключая внутренние системные инструменты).
     * get-aggregated используется только для фонового поллинга в Main.kt и не должен вызываться агентом.
     */
    fun listAgentTools(): List<McpSchema.Tool> {
        val internalTools = setOf("get-aggregated")
        return listAllTools().filter { it.name() !in internalTools }
    }

    fun callTool(name: String, args: Map<String, Any?>): String {
        val conn = toolIndex[name]
            ?: run {
                val msg = "Error: Tool '$name' not found on any connected server"
                callHistory.add(ToolCallRecord(++callCounter, name, "?", args, true, msg, msg.take(80)))
                return msg
            }

        @Suppress("UNCHECKED_CAST")
        val request = McpSchema.CallToolRequest.builder(name)
            .arguments(args as Map<String, Any>)
            .build()

        val result = try {
            conn.client.callTool(request)
        } catch (e: Exception) {
            val msg = "Error: ${e.message}"
            callHistory.add(ToolCallRecord(++callCounter, name, conn.config.name, args, true, msg, msg.take(80)))
            return msg
        }
        val text = result.content()
            .filterIsInstance<McpSchema.TextContent>()
            .joinToString("\n") { it.text() }
        val isError = result.isError() || text.startsWith("Error:")
        callHistory.add(ToolCallRecord(++callCounter, name, conn.config.name, args, isError, text, text.take(80)))
        return text
    }

    fun getCallHistory(): List<ToolCallRecord> = callHistory.toList()

    fun clearCallHistory() {
        callHistory.clear()
        callCounter = 0
    }

    fun disconnectAll() {
        for (conn in connections) {
            try {
                conn.client.closeGracefully()
            } catch (_: Exception) { }
        }
        connections.clear()
        toolIndex.clear()
        initialized = false
        println("MCP Client Manager: all servers disconnected")
    }
}
