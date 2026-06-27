package tools

import io.modelcontextprotocol.spec.McpSchema
import java.io.File

class SaveToFileTool(
    private val dataDir: File = File("data")
) : AbstractPipelineTool(
    toolName = "saveToFile",
    toolDescription = "Save content to a file inside data/pipeline_output/ directory",
    schemaProperties = mapOf(
        "filename" to mapOf("type" to "string", "description" to "File name (e.g. 'result.txt' or 'report.md')"),
        "content" to mapOf("type" to "string", "description" to "Content to write to the file"),
        "directory" to mapOf("type" to "string", "description" to "Optional subdirectory inside data/ (default: pipeline_output)")
    ),
    requiredFields = listOf("filename", "content")
) {
    fun execute(content: String, filename: String, subdirectory: String = "pipeline_output"): String {
        return saveContentToFile(filename, content, subdirectory)
    }

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val filename = arguments["filename"] as? String
            ?: return McpResult.error("Missing required argument 'filename'")
        val content = arguments["content"] as? String
            ?: return McpResult.error("Missing required argument 'content'")
        val subdirectory = arguments["directory"] as? String ?: "pipeline_output"
        return McpResult.success(saveContentToFile(filename, content, subdirectory))
    }

    private fun saveContentToFile(filename: String, content: String, subdirectory: String): String {
        val outputDir = File(dataDir, subdirectory).also { it.mkdirs() }

        var outputFile = File(outputDir, filename)
        var counter = 1
        while (outputFile.exists()) {
            val nameWithoutExtension = outputFile.nameWithoutExtension
            val extension = outputFile.extension
            outputFile = File(outputDir, "${nameWithoutExtension}_$counter.$extension")
            counter++
        }

        outputFile.writeText(content)
        return "Saved to ${outputFile.absolutePath} (${content.length} characters)"
    }
}
