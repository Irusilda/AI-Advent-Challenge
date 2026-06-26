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

class McpClientManager {

    private data class ServerConnection(
        val config: ServerConfig,
        val client: McpSyncClient
    )

    private val connections = mutableListOf<ServerConnection>()
    private val toolIndex = mutableMapOf<String, ServerConnection>() // toolName -> connection
    private var initialized = false

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

    fun callTool(name: String, args: Map<String, Any?>): String {
        val conn = toolIndex[name]
            ?: return "Error: Tool '$name' not found on any connected server"

        @Suppress("UNCHECKED_CAST")
        val request = McpSchema.CallToolRequest.builder(name)
            .arguments(args as Map<String, Any>)
            .build()

        val result = conn.client.callTool(request)
        return result.content()
            .filterIsInstance<McpSchema.TextContent>()
            .joinToString("\n") { it.text() }
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
