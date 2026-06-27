package tools

import io.modelcontextprotocol.spec.McpSchema
import java.io.File

class SearchTool : AbstractPipelineTool(
    toolName = "search",
    toolDescription = "Search for text in local .txt files, returns matching lines with context",
    schemaProperties = mapOf(
        "query" to mapOf("type" to "string", "description" to "Search term or phrase to find in files"),
        "file_pattern" to mapOf("type" to "string", "description" to "Optional file glob pattern (default: *.txt)"),
        "max_results" to mapOf("type" to "integer", "description" to "Maximum matches to return (default: 20, 0 = unlimited)")
    ),
    requiredFields = listOf("query")
) {
    fun execute(query: String, filePattern: String = "*.txt", maxResults: Int = 20): String {
        return performSearch(query.lowercase(), filePattern, maxResults)
    }

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val query = (arguments["query"] as? String)?.lowercase()
            ?: return McpResult.error("Missing required argument 'query'")
        val filePattern = arguments["file_pattern"] as? String ?: "*.txt"
        val maxResults = (arguments["max_results"] as? Number)?.toInt() ?: 20
        return McpResult.success(performSearch(query, filePattern, maxResults))
    }

    private fun performSearch(query: String, filePattern: String, maxResults: Int = 20): String {
        val searchDir = File(".")
        val searchPattern = filePattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")

        val matchingFiles = searchDir.listFiles()
            ?.filter { it.isFile && it.name.matches(searchPattern.toRegex()) }
            ?.filter { it.length() < 3_000_000 }
            ?: emptyList()

        if (matchingFiles.isEmpty()) {
            val largeFileHint = if (searchDir.listFiles()?.any { it.isFile && it.name.matches(searchPattern.toRegex()) && it.length() >= 3_000_000 } == true) {
                " (files >3MB skipped)"
            } else ""
            return "No searchable files matching pattern '$filePattern'$largeFileHint"
        }

        val output = StringBuilder()
        var totalMatches = 0
        val hardLimit = if (maxResults > 0) maxResults else 50

        for (file in matchingFiles.sortedBy { it.name }) {
            if (totalMatches >= hardLimit) break

            try {
                val rawText = file.readText()
                val queryLower = query.lowercase()

                val normalizedText = if (rawText.startsWith("{\\rtf")) {
                    rawText
                        .replace(Regex("""\\[a-z]+[\d-]*"""), " ")
                        .replace(Regex("""\\'.{2}"""), " ")
                        .replace(Regex("""\{[^}]*}"""), " ")
                        .replace(Regex("""\s+"""), "\n")
                        .trim()
                } else {
                    rawText
                }

                if (!normalizedText.lowercase().contains(queryLower)) continue

                val lines = normalizedText.lines().filter { it.length > 3 }
                var matchCount = 0
                val contextWindow = 2
                for (lineIndex in lines.indices) {
                    if (totalMatches >= hardLimit) break
                    if (lines[lineIndex].lowercase().contains(queryLower)) {
                        matchCount++
                        totalMatches++

                        val contextStart = maxOf(0, lineIndex - contextWindow)
                        val contextEnd = minOf(lines.size - 1, lineIndex + contextWindow)

                        if (matchCount == 1) output.appendLine("--- ${file.name} ---")

                        for (ci in contextStart..contextEnd) {
                            val cl = lines[ci]
                            if (ci == lineIndex) output.appendLine("  >> ${cl.trim()}")
                            else { val t = cl.trim(); if (t.isNotEmpty()) output.appendLine("     $t") }
                        }
                        output.appendLine()
                    }
                }
            } catch (exception: Exception) {
                output.appendLine("  [Error reading ${file.name}: ${exception.message}]")
            }
        }

        return if (totalMatches == 0) {
            "No matches found for '$query' in files matching '$filePattern'"
        } else {
            "Search results for '$query': $totalMatches matches (files >3MB excluded)\n\n$output"
        }
    }
}
