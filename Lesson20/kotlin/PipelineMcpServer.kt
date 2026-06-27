import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import tools.*
import java.util.concurrent.CountDownLatch

fun main() {
    val server = PipelineMcpServer()
    server.start()
    server.await()
}

class PipelineMcpServer {
    private var server: McpSyncServer? = null
    private val latch = CountDownLatch(1)

    fun start(): McpSyncServer {
        val transport = StdioServerTransportProvider(McpJsonDefaults.getMapper())

        val searchTool = SearchTool()
        val webSearchTool = WebSearchTool()
        val fetchUrlsTool = FetchUrlsTool()
        val summarizeTool = SummarizeTool()
        val saveToFileTool = SaveToFileTool()
        val runPipelineTool = RunPipelineTool(searchTool, webSearchTool, fetchUrlsTool, summarizeTool, saveToFileTool)

        server = McpServer.sync(transport)
            .serverInfo("pipeline-server", "1.0.0")
            .tools(
                searchTool.specification(),
                webSearchTool.specification(),
                fetchUrlsTool.specification(),
                summarizeTool.specification(),
                saveToFileTool.specification(),
                runPipelineTool.specification()
            )
            .build()

        System.err.println("Pipeline MCP Server started (Stdio)")
        return server!!
    }

    fun await() {
        latch.await()
    }
}
