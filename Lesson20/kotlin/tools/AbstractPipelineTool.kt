package tools

import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema

abstract class AbstractPipelineTool(
    val toolName: String,
    val toolDescription: String,
    val schemaProperties: Map<String, Any?>,
    val requiredFields: List<String> = listOf("query")
) {
    fun specification(): McpServerFeatures.SyncToolSpecification {
        val schema = mapOf(
            "type" to "object",
            "properties" to schemaProperties,
            "required" to requiredFields
        )

        val tool = McpSchema.Tool.builder(toolName, schema)
            .description(toolDescription)
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val arguments = request.arguments() ?: return@callHandler McpResult.error("No arguments provided")
                try {
                    handle(arguments)
                } catch (exception: Exception) {
                    McpResult.error("${toolName} error: ${exception.message}")
                }
            }
            .build()
    }

    protected abstract fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult
}
