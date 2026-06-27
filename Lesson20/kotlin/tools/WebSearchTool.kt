package tools

import io.modelcontextprotocol.spec.McpSchema
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class WebSearchTool : AbstractPipelineTool(
    toolName = "web-search",
    toolDescription = "Search the internet, returns page titles + snippets. Supports any language.",
    schemaProperties = mapOf(
        "query" to mapOf("type" to "string", "description" to "Search query (supports any language)"),
        "max_results" to mapOf("type" to "integer", "description" to "Maximum results to return (default: 10, max: 20)")
    ),
    requiredFields = listOf("query")
) {
    private data class WebSearchResult(val url: String, val title: String, val snippet: String)

    fun execute(query: String, maxResults: Int = 10): String {
        return performWebSearch(query, maxResults)
    }

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val query = arguments["query"] as? String
            ?: return McpResult.error("Missing required argument 'query'")
        if (query.isBlank()) return McpResult.error("Search query cannot be empty")
        val maxResults = (arguments["max_results"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 10
        return McpResult.success(performWebSearch(query, maxResults))
    }

    private fun performWebSearch(query: String, maxResults: Int): String {
        if (query.isBlank()) return "Search query cannot be empty"

        // Упрощаем запрос: Mojeek плохо работает с длинными естественно-языковыми запросами
        val simplifiedQuery = simplifyQuery(query)

        // 1. Mojeek — основной поисковик (не блокирует парсинг)
        val mojeekResult = tryMojeek(simplifiedQuery, maxResults)
        if (mojeekResult != null) return mojeekResult

        // 2. DuckDuckGo API (Instant Answer API, не требует парсинга HTML)
        Thread.sleep(500) // задержка перед следующим поисковиком
        val ddgApiResult = tryDuckDuckGoApi(simplifiedQuery, maxResults)
        if (ddgApiResult != null) return ddgApiResult

        // 3. Google HTML (может сработать, может показать капчу)
        Thread.sleep(500)
        val googleResult = tryGoogleSearch(simplifiedQuery, maxResults)
        if (googleResult != null) return googleResult

        // 4. Если запрос на русском — пробуем упрощённый английский запрос
        if (query.any { it in 'а'..'я' || it in 'А'..'Я' }) {
            val englishQuery = query.replace(Regex("""[^\w\s]"""), " ")
                .split(Regex("""\s+"""))
                .filter { it.length > 2 }
                .take(5)
                .joinToString(" ")

            if (englishQuery.length < query.length && englishQuery.isNotBlank()) {
                Thread.sleep(500)
                val mojeekSimplified = tryMojeek(englishQuery, maxResults)
                if (mojeekSimplified != null) return mojeekSimplified
            }
        }

        return "No web search results found for '$query'"
    }

    /**
     * Упрощает длинный естественно-языковой запрос до коротких ключевых слов.
     * Mojeek лучше работает с 3-5 ключевыми словами, чем с длинными фразами.
     */
    private fun simplifyQuery(query: String): String {
        // Если запрос короткий (до 5 слов) — оставляем как есть
        val words = query.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size <= 5) return query

        // Удаляем стоп-слова и оставляем только значимые слова
        val stopWords = setOf(
            "what", "where", "when", "why", "how", "who", "which",
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "with",
            "and", "or", "but", "is", "are", "was", "were", "do", "does", "did",
            "have", "has", "had", "can", "could", "will", "would", "shall", "should",
            "may", "might", "must", "this", "that", "these", "those",
            "i", "you", "he", "she", "it", "we", "they",
            "me", "my", "your", "his", "her", "its", "our", "their",
            "not", "no", "nor", "so", "if", "then", "than", "as",
            "about", "into", "over", "after", "before", "between", "under",
            "very", "just", "also", "too", "some", "any", "each", "every",
            "all", "both", "few", "more", "most", "other", "such", "only",
            "own", "same", "new", "now", "here", "there", "well", "back",
            "up", "down", "out", "off", "away", "again", "ever", "never",
            "being", "been", "doing", "having", "getting", "going",
            "people", "thing", "things", "way", "ways",
            "что", "как", "где", "когда", "почему", "зачем", "кто", "который",
            "и", "в", "на", "с", "со", "к", "у", "о", "об", "от", "до",
            "по", "за", "из", "для", "при", "через", "без", "после",
            "это", "этот", "эта", "эти", "тот", "та", "те",
            "я", "ты", "он", "она", "оно", "мы", "вы", "они",
            "мой", "твой", "его", "её", "наш", "ваш", "их",
            "не", "ни", "да", "нет", "или", "но", "а", "если",
            "так", "тоже", "ещё", "уже", "вот", "тут", "там",
            "очень", "просто", "только", "можно", "нужно", "надо",
            "быть", "был", "была", "было", "были", "будет", "будут",
            "делать", "делает", "делают", "сделать",
            "житель", "жители", "человек", "люди"
        )

        val significantWords = words
            .map { it.lowercase().trim(',', '.', '!', '?', ':', ';', '"', '\'', '(', ')', '°', 'C') }
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(5)

        return if (significantWords.size >= 2) significantWords.joinToString(" ") else query
    }

    /**
     * Mojeek — независимая поисковая система, не блокирует парсинг.
     * https://www.mojeek.com/
     */
    private fun tryMojeek(query: String, maxResults: Int): String? {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://www.mojeek.com/search?q=$encoded")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")

            val html = connection.inputStream.bufferedReader().readText()

            // 403 — блокировка за частые запросы
            if (html.contains("403 - Forbidden", ignoreCase = true) || html.contains("automated queries", ignoreCase = true)) {
                return null
            }

            if (html.contains("captcha", ignoreCase = true) || html.length < 500) {
                return null
            }

            val results = mutableListOf<WebSearchResult>()

            // Mojeek: <h2><a class="title" title="URL" href="URL">TITLE</a></h2>
            // Пробуем два варианта порядка атрибутов
            var matches = try {
                Regex("""<h2[^>]*><a[^>]+class="title"[^>]+title="([^"]*)"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a></h2>""").findAll(html).toList()
            } catch (_: Exception) { emptyList() }

            if (matches.isEmpty()) {
                matches = try {
                    Regex("""<h2[^>]*><a[^>]+class="title"[^>]+href="([^"]+)"[^>]+title="([^"]*)"[^>]*>([\s\S]*?)</a></h2>""").findAll(html).toList()
                } catch (_: Exception) { emptyList() }
            }

            for (i in 0 until minOf(matches.size, maxResults)) {
                val urlStr = matches[i].groupValues[2]
                val titleText = matches[i].groupValues[3]
                    .replace(Regex("""<[^>]+>"""), " ")
                    .replace(Regex("""\s+"""), " ").trim()
                    .unescapeHtml()

                // Пытаемся найти сниппет (текст после заголовка)
                val snippetText = try {
                    val afterMatch = html.substringAfter(matches[i].value)
                    val snippetMatch = Regex("""<p[^>]*>(.{1,300}?)</p>""").find(afterMatch)
                    snippetMatch?.groupValues?.get(1)
                        ?.replace(Regex("""<[^>]+>"""), " ")
                        ?.replace(Regex("""\s+"""), " ")
                        ?.trim()
                        ?.unescapeHtml() ?: ""
                } catch (_: Exception) { "" }

                if (titleText.isNotBlank() && titleText.length > 3) {
                    results.add(WebSearchResult(urlStr, titleText, snippetText))
                }
            }

            if (results.isNotEmpty()) {
                return formatResults(query, results)
            }
        } catch (_: Exception) {
            // Fallback to next method
        }
        return null
    }

    /**
     * DuckDuckGo Instant Answer API — не требует парсинга HTML, возвращает JSON.
     * Может не находить результаты для некоторых запросов.
     */
    private fun tryDuckDuckGoApi(query: String, maxResults: Int): String? {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            connection.setRequestProperty("Accept", "application/json")

            val json = connection.inputStream.bufferedReader().readText()

            val abstractText = Regex(""""AbstractText"\s*:\s*"([^"]*)""").find(json)?.groupValues?.get(1)
                ?.unescapeJson() ?: ""
            val abstractSource = Regex(""""AbstractSource"\s*:\s*"([^"]*)""").find(json)?.groupValues?.get(1)
                ?.unescapeJson() ?: ""
            val abstractUrl = Regex(""""AbstractURL"\s*:\s*"([^"]*)""").find(json)?.groupValues?.get(1)
                ?.unescapeJson() ?: ""

            val relatedTopics = mutableListOf<Pair<String, String>>()
            val topicRegex = Regex(""""Text"\s*:\s*"([^"]*)""")
            val topicMatches = topicRegex.findAll(json).toList()
            for (i in 0 until minOf(topicMatches.size, maxResults)) {
                val text = topicMatches[i].groupValues[1].unescapeJson()
                val urlMatch = Regex("""\[\[([^\]]+)\]\[([^\]]+)\]\]""").find(text)
                if (urlMatch != null) {
                    val topicUrl = urlMatch.groupValues[2]
                    val topicText = text.replace(Regex("""\[\[[^\]]+\]\[[^\]]+\]\]"""), "").trim()
                    relatedTopics.add(topicUrl to topicText)
                } else {
                    relatedTopics.add("" to text)
                }
            }

            if (abstractText.isNotBlank() || relatedTopics.isNotEmpty()) {
                val output = StringBuilder()
                output.appendLine("Web search results for '$query': ${relatedTopics.size + 1} results")
                output.appendLine()

                if (abstractText.isNotBlank()) {
                    output.appendLine("1. $abstractText")
                    if (abstractSource.isNotBlank()) output.appendLine("   Source: $abstractSource")
                    if (abstractUrl.isNotBlank()) output.appendLine("   $abstractUrl")
                    output.appendLine()
                }

                for ((index, topic) in relatedTopics.withIndex()) {
                    val num = if (abstractText.isNotBlank()) index + 2 else index + 1
                    output.appendLine("$num. ${topic.second.take(200)}")
                    if (topic.first.isNotBlank()) output.appendLine("   ${topic.first}")
                    output.appendLine()
                }
                return output.toString()
            }
        } catch (_: Exception) {
            // Fallback to next method
        }
        return null
    }

    /**
     * Google Search HTML — может сработать, может показать капчу.
     */
    private fun tryGoogleSearch(query: String, maxResults: Int): String? {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://www.google.com/search?q=$encoded&hl=en")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")

            val html = connection.inputStream.bufferedReader().readText()

            if (html.contains("captcha", ignoreCase = true) || html.length < 500) {
                return null
            }

            val results = mutableListOf<WebSearchResult>()

            // Google: <h3>...</h3> + ссылки /url?q=... + сниппеты VwiC3b
            val titleRegex = Regex("""<h3[^>]*>(.{1,200}?)</h3>""")
            val linkRegex = Regex("""<a[^>]+href="(/url\?q=[^"&]+)[^"]*"[^>]*>""")
            val snippetRegex = Regex("""<div[^>]*class="[^"]*VwiC3b[^"]*"[^>]*>(.{1,500}?)</div>""")

            val titleMatches = titleRegex.findAll(html).toList()
            val linkMatches = linkRegex.findAll(html).toList()
            val snippetMatches = snippetRegex.findAll(html).toList()

            for (i in 0 until minOf(titleMatches.size, linkMatches.size, maxResults)) {
                val titleText = titleMatches[i].groupValues[1]
                    .replace(Regex("""<[^>]+>"""), " ")
                    .replace(Regex("""\s+"""), " ").trim()
                    .unescapeHtml()

                val urlRaw = linkMatches[i].groupValues[1]
                val realUrl = try {
                    val urlMatch = Regex("""q=([^&]+)""").find(urlRaw)
                    if (urlMatch != null) URLDecoder.decode(urlMatch.groupValues[1], "UTF-8") else urlRaw
                } catch (_: Exception) { urlRaw }

                val snippetText = if (i < snippetMatches.size) {
                    snippetMatches[i].groupValues[1]
                        .replace(Regex("""<[^>]+>"""), " ")
                        .replace(Regex("""\s+"""), " ").trim()
                        .unescapeHtml()
                } else ""

                if (titleText.isNotBlank() && titleText.length > 5) {
                    results.add(WebSearchResult(realUrl, titleText, snippetText))
                }
            }

            if (results.isNotEmpty()) {
                return formatResults(query, results)
            }
        } catch (_: Exception) {
            // Fallback to next method
        }
        return null
    }

    private fun formatResults(query: String, results: List<WebSearchResult>): String {
        val output = StringBuilder()
        output.appendLine("Web search results for '$query': ${results.size} results")
        output.appendLine()
        for ((index, result) in results.withIndex()) {
            output.appendLine("${index + 1}. ${result.title}")
            if (result.snippet.isNotBlank()) output.appendLine("   ${result.snippet.take(200)}")
            output.appendLine("   ${result.url}")
            output.appendLine()
        }
        return output.toString()
    }

    private fun String.unescapeJson(): String = this
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\/", "/")
        .replace("\\\\", "\\")

    private fun String.unescapeHtml(): String = this
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&#8211;", "–")
        .replace("&#8217;", "'")
        .replace("&#8230;", "…")
}
