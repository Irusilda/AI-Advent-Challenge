import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import java.time.Duration

fun main() {
    val params = ServerParameters.builder("npx")
        .args("-y", "@modelcontextprotocol/server-everything")
        .build()

    val transport = StdioClientTransport(params, McpJsonDefaults.getMapper())
    val client = McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(30))
        .initializationTimeout(Duration.ofSeconds(60))
        .build()

    try {
        println("🔄 Establishing MCP connection...")
        client.initialize()
        println("✅ Connection established!\n")

        val toolsResult = client.listTools()
        val tools = toolsResult.tools()
        println("📋 Available tools (${tools.size}):")
        tools.forEachIndexed { i, tool ->
            println("  ${i + 1}. ${tool.name()}")
            tool.description()?.let { println("     Description: $it") }
            println()
        }
    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
        e.printStackTrace()
    } finally {
        client.closeGracefully()
        println("🔌 Connection closed")
    }
}
