package tools

import io.modelcontextprotocol.spec.McpSchema
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RunPipelineTool(
    private val searchTool: SearchTool,
    private val webSearchTool: WebSearchTool,
    private val fetchUrlsTool: FetchUrlsTool,
    private val summarizeTool: SummarizeTool,
    private val saveToFileTool: SaveToFileTool,
    private val dataDir: File = File("data")
) : AbstractPipelineTool(
    toolName = "run-pipeline",
    toolDescription = "Full pipeline: search/summarize/saveToFile. Chains all three steps. Supports local files and web search.",
    schemaProperties = mapOf(
        "query" to mapOf("type" to "string", "description" to "Search query — passed to the search tool"),
        "source" to mapOf("type" to "string", "description" to "Data source: 'local' for file search, 'web' for internet search (default: local)"),
        "file_pattern" to mapOf("type" to "string", "description" to "Optional file pattern for search (default: *.txt)"),
        "output_filename" to mapOf("type" to "string", "description" to "Optional output filename (default: auto-generated)"),
        "max_sentences" to mapOf("type" to "integer", "description" to "Max sentences for summarization (default: 5)"),
        "max_search_results" to mapOf("type" to "integer", "description" to "Max matches from search (default: 15)")
    ),
    requiredFields = listOf("query")
) {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val query = arguments["query"] as? String
            ?: return McpResult.error("Missing required argument 'query'")
        val source = arguments["source"] as? String ?: "local"
        val filePattern = arguments["file_pattern"] as? String ?: "*.txt"
        val outputFilename = arguments["output_filename"] as? String
            ?: "pipeline_${LocalDateTime.now().format(timestampFormat)}.txt"
        val maxSentences = (arguments["max_sentences"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
        val maxSearchResults = (arguments["max_search_results"] as? Number)?.toInt() ?: 15

        return McpResult.success(executePipeline(query, source, filePattern, outputFilename, maxSentences, maxSearchResults))
    }

    fun executePipeline(
        query: String,
        source: String = "local",
        filePattern: String = "*.txt",
        outputFilename: String = "pipeline_${System.currentTimeMillis()}.txt",
        maxSentences: Int = 5,
        maxSearchResults: Int = 15
    ): String {
        val report = StringBuilder()
        val separator = "─".repeat(60)

        val isWeb = source.equals("web", ignoreCase = true)
        val sourceLabel = if (isWeb) "Internet (DuckDuckGo)" else "Local files ($filePattern)"

        val chain = if (isWeb) "web-search → fetch-url → summarize → saveToFile" else "search → summarize → saveToFile"
        report.appendLine("Pipeline: $chain")
        report.appendLine(separator)

        val totalSteps = if (isWeb) 4 else 3

        report.appendLine("Step 1/$totalSteps: ${if (isWeb) "WEB SEARCH" else "FILE SEARCH"}")
        report.appendLine("  Query: \"$query\"")
        report.appendLine("  Source: $sourceLabel")
        val searchResult = if (isWeb) {
            webSearchTool.execute(query, maxSearchResults)
        } else {
            searchTool.execute(query, filePattern, maxSearchResults)
        }
        report.appendLine("  Result: ${searchResult.take(100)}...")
        report.appendLine()

        if (searchResult.startsWith("No")) {
            report.appendLine("Pipeline stopped: search found nothing.")
            report.appendLine(searchResult)
            return report.toString()
        }

        var summarizeInput = searchResult

        if (isWeb) {
            report.appendLine("Step 2/$totalSteps: FETCH URLS")
            report.appendLine("  Max pages: 3")
            val pageContent = fetchUrlsTool.execute(searchResult, 3)
            report.appendLine("  Fetched: ${pageContent.take(100)}...")
            report.appendLine()
            if (pageContent.startsWith("No") || pageContent.startsWith("Could not")) {
                report.appendLine("Pipeline warning: no pages fetched, using search snippets for summary.")
            }
            summarizeInput = pageContent
        }

        val stepNum = if (isWeb) 3 else 2

        println("DEBUG: Step $stepNum - ${if (isWeb) "WEB SEARCH" else "FILE SEARCH"}")
        println("DEBUG: Search result length: ${searchResult.length}")

        report.appendLine("Step $stepNum/$totalSteps: SUMMARIZE")
        report.appendLine("  Max sentences: $maxSentences")
        val summary = summarizeTool.execute(summarizeInput, maxSentences)
        report.appendLine("  Summary length: ${summary.length} characters")
        report.appendLine()

        val stepNumSave = if (isWeb) 4 else 3
        report.appendLine("Step $stepNumSave/$totalSteps: SAVE TO FILE")
        report.appendLine("  Filename: $outputFilename")
        val saveResult = saveToFileTool.execute(summary, outputFilename, "pipeline_output")
        report.appendLine("  $saveResult")
        report.appendLine()

        report.appendLine(separator)
        report.appendLine("PIPELINE COMPLETE")
        report.appendLine()
        report.appendLine("--- SOURCE DATA (first 500 chars) ---")
        report.appendLine(searchResult.take(500) + if (searchResult.length > 500) "..." else "")
        report.appendLine()
        report.appendLine("--- SUMMARY ---")
        report.appendLine(summary)

        return report.toString()
    }
}
