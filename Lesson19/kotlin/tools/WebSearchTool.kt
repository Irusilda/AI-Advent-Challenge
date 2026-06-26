package tools

import io.modelcontextprotocol.spec.McpSchema
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebSearchTool : AbstractPipelineTool(
    toolName = "web-search",
    toolDescription = "Search the internet via DuckDuckGo, returns page titles + snippets",
    schemaProperties = mapOf(
        "query" to mapOf("type" to "string", "description" to "Search query (supports any language)"),
        "max_results" to mapOf("type" to "integer", "description" to "Maximum results to return (default: 10, max: 20)")
    ),
    requiredFields = listOf("query")
) {
    fun execute(query: String, maxResults: Int = 10): String {
        return performWebSearch(query, maxResults)
    }

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val query = arguments["query"] as? String
            ?: return McpResult.error("Missing required argument 'query'")
        val maxResults = (arguments["max_results"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 10
        return McpResult.success(performWebSearch(query, maxResults))
    }

    private fun performWebSearch(query: String, maxResults: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")

        val html = connection.inputStream.bufferedReader().readText()

        data class WebSearchResult(val url: String, val title: String, val snippet: String)

        val results = mutableListOf<WebSearchResult>()
        val titleRegex = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
        val snippetRegex = Regex("""<a[^>]+class="result__snippet"[^>]*>([\s\S]*?)</a>""")

        val titleMatches = titleRegex.findAll(html).toList()
        val snippetMatches = snippetRegex.findAll(html).toList()

        for (i in 0 until minOf(titleMatches.size, snippetMatches.size, maxResults)) {
            val urlText = titleMatches[i].groupValues[1]
            val titleText = titleMatches[i].groupValues[2]
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""\s+"""), " ").trim()
                .unescapeHtml()
            val snippetText = snippetMatches[i].groupValues[1]
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""\s+"""), " ").trim()
                .unescapeHtml()
            results.add(WebSearchResult(urlText, titleText, snippetText))
        }

        if (results.isEmpty()) return "No web search results found for '$query'"

        val output = StringBuilder()
        output.appendLine("Web search results for '$query': ${results.size} results")
        output.appendLine()
        for ((index, result) in results.withIndex()) {
            output.appendLine("${index + 1}. ${result.title}")
            output.appendLine("   ${result.snippet.take(200)}")
            output.appendLine("   ${result.url}")
            output.appendLine()
        }
        return output.toString()
    }

    private fun String.unescapeHtml(): String = this
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
