package tools

import io.modelcontextprotocol.spec.McpSchema

class SummarizeTool : AbstractPipelineTool(
    toolName = "summarize",
    toolDescription = "Extractive summarization — extracts the most representative sentences from text",
    schemaProperties = mapOf(
        "text" to mapOf("type" to "string", "description" to "Text content to summarize"),
        "max_sentences" to mapOf("type" to "integer", "description" to "Maximum number of sentences in summary (default: 5)")
    ),
    requiredFields = listOf("text")
) {
    fun execute(text: String, maxSentences: Int = 5): String {
        return performSummarization(text, maxSentences)
    }

    override fun handle(arguments: Map<String, Any?>): McpSchema.CallToolResult {
        val text = arguments["text"] as? String
            ?: return McpResult.error("Missing required argument 'text'")
        val maxSentences = (arguments["max_sentences"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
        return McpResult.success(performSummarization(text, maxSentences))
    }

    private fun performSummarization(text: String, maxSentences: Int): String {
        val cleanText = text.trim()
        if (cleanText.length < 100) return cleanText

        val sentenceDelimiters = Regex("""(?<=[.!?])\s+""")
        val sentences = cleanText.split(sentenceDelimiters).map { it.trim() }.filter { it.length > 10 }

        if (sentences.size <= maxSentences) return cleanText

        val wordFrequencies = mutableMapOf<String, Int>()
        val stopWords = detectStopWords(cleanText)

        val words = cleanText.lowercase().split(Regex("""\W+"""))
        for (word in words) {
            if (word.length > 2 && word !in stopWords) {
                wordFrequencies[word] = (wordFrequencies[word] ?: 0) + 1
            }
        }

        data class SentenceScore(val index: Int, val score: Double, val text: String)

        val sentenceScores = sentences.mapIndexed { index, sentence ->
            val normalizedSentence = sentence.lowercase()
            val sentenceWords = normalizedSentence.split(Regex("""\W+"""))
            val significantWords = sentenceWords.filter { it.length > 2 && it !in stopWords }

            val frequencyScore = if (significantWords.isNotEmpty()) {
                significantWords.sumOf { word -> wordFrequencies[word] ?: 0 }.toDouble() / significantWords.size
            } else 0.0

            val positionBonus = if (index < sentences.size * 0.15) 3.0
            else if (index > sentences.size * 0.85) 1.0
            else 0.0

            val lengthBonus = if (sentence.length in 80..300) 1.0 else -1.0
            val firstSentenceBonus = if (index == 0 && sentence.length > 40) 2.0 else 0.0

            SentenceScore(index, frequencyScore + positionBonus + lengthBonus + firstSentenceBonus, sentence)
        }

        val selectedSentences = sentenceScores
            .sortedByDescending { it.score }
            .take(maxSentences)
            .sortedBy { it.index }
            .map { it.text }

        return selectedSentences.joinToString(" ")
    }
}
