import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import java.time.Duration

class McpClientManager {

    private var client: McpSyncClient? = null

    fun connect(): McpSyncClient {
        if (client != null) return client!!

        val classpath = System.getProperty("java.class.path")
        val params = ServerParameters.builder("java")
            .args("-cp", classpath, "WeatherMcpServerKt")
            .build()

        val transport = StdioClientTransport(params, McpJsonDefaults.getMapper())
        client = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(60))
            .initializationTimeout(Duration.ofSeconds(60))
            .build()
        client!!.initialize()
        println("✅ MCP Client connected (Stdio: WeatherMcpServer)")
        return client!!
    }

    fun callTool(name: String, args: Map<String, Any?>): String {
        val c = connect()
        @Suppress("UNCHECKED_CAST")
        val request = McpSchema.CallToolRequest.builder(name).arguments(args as Map<String, Any>).build()
        val result = c.callTool(request)
        return result.content()
            .filterIsInstance<McpSchema.TextContent>()
            .joinToString("\n") { it.text() }
    }

    fun disconnect() {
        client?.closeGracefully()
        client = null
    }
}
