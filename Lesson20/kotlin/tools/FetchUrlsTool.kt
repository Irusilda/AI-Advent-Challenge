package tools

import io.modelcontextprotocol.spec.McpSchema
import org.jsoup.Jsoup
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FetchUrlsTool : AbstractPipelineTool(
    toolName = "fetch-url",
    toolDescription = "Fetch full text content from URLs found in text. Extracts URLs from search results or plain text.",
    schemaProperties = mapOf(
        "text" to mapOf("type" to "string", "description" to "Text containing URLs (search results or plain text)"),
        "max_pages" to mapOf("type" to "integer", "description" to "Maximum pages to fetch in parallel (default: 3)")
    ),
    requiredFields = listOf("text")
) {
    companion object {
        private val CONTENT_SELECTORS = listOf(
            "article",
            "main",
            "[class*=post-content]",
            "[class*=entry-content]",
            "[class*=article-body]",
            "[class*=article-content]",
            "[class*=story-body]",
            "[itemprop=articleBody]",
            "[class*=content]",
            "[class*=article]",
            "[class*=post]"
        )
    }

    fun execute(text: String, maxPages: Int = 3): String {
        return fetchUrls(text, maxPages)
    }

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val text = arguments["text"] as? String
            ?: return McpResult.error("Missing required argument 'text'")
        val maxPages = (arguments["max_pages"] as? Number)?.toInt()?.coerceIn(1, 5) ?: 3
        return McpResult.success(fetchUrls(text, maxPages))
    }

    private fun extractUrls(text: String, maxPages: Int): List<String> {
        val urlPattern = Regex("""https?://[^\s]+""")
        val urls = urlPattern.findAll(text).map { it.value }.toList()
        if (urls.isEmpty()) return emptyList()

        return urls.distinct().take(maxPages)
    }

    private fun fetchUrls(text: String, maxPages: Int): String {
        val urls = extractUrls(text, maxPages)
        if (urls.isEmpty()) return "No URLs found in the provided text"

        val executor = Executors.newFixedThreadPool(3)
        val tasks = urls.map { url ->
            Callable<String?> { fetchSingleUrl(url) }
        }

        val results = try {
            executor.invokeAll(tasks, 15, TimeUnit.SECONDS)
                .mapIndexedNotNull { index, future ->
                    try {
                        val content = future.get(15, TimeUnit.SECONDS)
                        if (content != null) urls[index] to content else null
                    } catch (_: Exception) { null }
                }
        } finally {
            executor.shutdown()
        }

        if (results.isEmpty()) {
            return "Could not fetch content from any of ${urls.size} URLs"
        }

        val output = StringBuilder()
        output.appendLine("📄 Fetched ${results.size} of ${urls.size} pages:")
        output.appendLine()
        for ((index, pair) in results.withIndex()) {
            val (url, content) = pair
            output.appendLine("--- Page ${index + 1} ($url) ---")
            output.appendLine(content.take(3000))
            output.appendLine()
        }
        return output.toString()
    }

    private fun fetchSingleUrl(urlString: String): String? {
        try {
            val doc = Jsoup.connect(urlString)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(8000)
                .followRedirects(true)
                .get()

            doc.select("script, style, nav, footer, header, aside, .sidebar, .menu, .comments, .advertisement, .ad, iframe, noscript").remove()

            val contentElement = CONTENT_SELECTORS.firstNotNullOfOrNull { selector ->
                val el = doc.selectFirst(selector)
                if (el != null && el.text().length > 200) el else null
            }

            val text = if (contentElement != null) {
                contentElement.select("script, style, nav, footer, header, aside, .sidebar, .menu").remove()
                contentElement.text()
            } else {
                doc.body().text()
            }

            if (text.length < 100) return null
            return text.take(3000)
        } catch (_: Exception) {
            return null
        }
    }
}