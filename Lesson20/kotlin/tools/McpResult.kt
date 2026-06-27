package tools

import io.modelcontextprotocol.spec.McpSchema

object McpResult {
    fun success(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.TextContent.builder(text).build())
            .build()

    fun error(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.TextContent.builder(text).build())
            .isError(true)
            .build()
}
