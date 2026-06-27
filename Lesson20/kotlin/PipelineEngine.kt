import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class PipelineEngine(private val mcpClientManager: McpClientManager) {

    data class PipelineStepResult(
        val stepName: String,
        val toolName: String,
        val output: String,
        val isError: Boolean = false
    )

    data class PipelineResult(
        val query: String,
        val steps: List<PipelineStepResult>,
        val finalOutput: String?,
        val isComplete: Boolean
    ) {
        fun formattedReport(): String {
            val report = StringBuilder()
            val separator = "─".repeat(60)
            report.appendLine(separator)
            report.appendLine("  PIPELINE EXECUTION REPORT")
            report.appendLine("  Query: \"$query\"")
            report.appendLine(separator)

            for ((stepIndex, step) in steps.withIndex()) {
                val statusIcon = if (step.isError) "✗" else "✓"
                report.appendLine()
                report.appendLine("  Step ${stepIndex + 1}/${steps.size} [$statusIcon] ${step.stepName}")
                report.appendLine("  Tool: ${step.toolName}")

                if (step.isError) {
                    report.appendLine("  Error: ${step.output}")
                } else {
                    val preview = step.output.take(200)
                    val lines = preview.lines()
                    for (line in lines) {
                        report.appendLine("  | $line")
                    }
                    if (step.output.length > 200) {
                        report.appendLine("  | ... (${step.output.length} characters total)")
                    }
                }
            }

            report.appendLine()
            report.appendLine(separator)
            if (isComplete) {
                report.appendLine("  STATUS: PIPELINE COMPLETE")
            } else {
                report.appendLine("  STATUS: PIPELINE FAILED")
            }
            report.appendLine(separator)

            return report.toString()
        }
    }

    fun executePipeline(
        query: String,
        filePattern: String = "*.txt",
        outputFilename: String? = null
    ): PipelineResult {
        val steps = if (outputFilename != null) {
            listOf(
                PipelineStep("Search for \"$query\"", "search", mapOf("query" to query, "file_pattern" to filePattern, "max_results" to 10)),
                PipelineStep("Summarize", "summarize", mapOf("text" to "{{previous_output}}", "max_sentences" to 5)),
                PipelineStep("Save", "saveToFile", mapOf("filename" to outputFilename, "content" to "{{previous_output}}", "directory" to "pipeline_output"))
            )
        } else {
            searchSummarizeSavePipeline(query, filePattern)
        }
        return executeCustomPipeline(steps, query)
    }

    fun executeCustomPipeline(steps: List<PipelineStep>, query: String? = null): PipelineResult {
        val stepResults = mutableListOf<PipelineStepResult>()
        var accumulatedInput = ""

        for ((stepIndex, step) in steps.withIndex()) {
            val arguments = mutableMapOf<String, Any?>()

            for ((key, value) in step.arguments) {
                if (value == "{{previous_output}}") {
                    arguments[key] = accumulatedInput
                } else {
                    arguments[key] = value
                }
            }

            val result = try {
                runBlocking {
                    withTimeout(30.seconds) {
                        mcpClientManager.callTool(step.toolName, arguments)
                    }
                }
            } catch (timeoutException: Exception) {
                stepResults.add(PipelineStepResult(
                    stepName = step.description,
                    toolName = step.toolName,
                    output = "Timeout: ${timeoutException.message}",
                    isError = true
                ))
                break
            }

            val isError = result.startsWith("Error:")

            stepResults.add(PipelineStepResult(
                stepName = step.description,
                toolName = step.toolName,
                output = result,
                isError = isError
            ))

            if (isError) break
            accumulatedInput = result
        }

        val resultQuery = query ?: steps.firstOrNull()?.description ?: "custom pipeline"
        return PipelineResult(
            query = resultQuery,
            steps = stepResults,
            finalOutput = if (stepResults.none { it.isError }) accumulatedInput else null,
            isComplete = stepResults.none { it.isError }
        )
    }

    data class PipelineStep(
        val description: String,
        val toolName: String,
        val arguments: Map<String, Any?>
    )

    fun executeWebPipeline(
        query: String,
        outputFilename: String? = null
    ): PipelineResult {
        val steps = if (outputFilename != null) {
            listOf(
                PipelineStep("Web search for \"$query\"", "web-search", mapOf("query" to query, "max_results" to 10)),
                PipelineStep("Fetch pages", "fetch-url", mapOf("text" to "{{previous_output}}", "max_pages" to 3)),
                PipelineStep("Summarize", "summarize", mapOf("text" to "{{previous_output}}", "max_sentences" to 5)),
                PipelineStep("Save", "saveToFile", mapOf("filename" to outputFilename, "content" to "{{previous_output}}", "directory" to "pipeline_output"))
            )
        } else {
            webSearchPipeline(query)
        }
        return executeCustomPipeline(steps, query)
    }

    companion object {
        fun searchSummarizeSavePipeline(
            query: String,
            filePattern: String = "*.txt",
            maxResults: Int = 10
        ): List<PipelineStep> {
            return listOf(
                PipelineStep(
                    description = "Search for \"$query\" in files",
                    toolName = "search",
                    arguments = mapOf("query" to query, "file_pattern" to filePattern, "max_results" to maxResults)
                ),
                PipelineStep(
                    description = "Summarize results",
                    toolName = "summarize",
                    arguments = mapOf("text" to "{{previous_output}}", "max_sentences" to 5)
                ),
                PipelineStep(
                    description = "Save summary",
                    toolName = "saveToFile",
                    arguments = mapOf(
                        "filename" to "pipeline_${query.take(20).sanitizeFilename()}.txt",
                        "content" to "{{previous_output}}",
                        "directory" to "pipeline_output"
                    )
                )
            )
        }

        fun webSearchPipeline(
            query: String,
            maxResults: Int = 10
        ): List<PipelineStep> {
            return listOf(
                PipelineStep(
                    description = "Search web for \"$query\"",
                    toolName = "web-search",
                    arguments = mapOf("query" to query, "max_results" to maxResults)
                ),
                PipelineStep(
                    description = "Fetch pages",
                    toolName = "fetch-url",
                    arguments = mapOf("text" to "{{previous_output}}", "max_pages" to 3)
                ),
                PipelineStep(
                    description = "Summarize results",
                    toolName = "summarize",
                    arguments = mapOf("text" to "{{previous_output}}", "max_sentences" to 5)
                ),
                PipelineStep(
                    description = "Save summary",
                    toolName = "saveToFile",
                    arguments = mapOf(
                        "filename" to "web_${query.take(20).sanitizeFilename()}.txt",
                        "content" to "{{previous_output}}",
                        "directory" to "pipeline_output"
                    )
                )
            )
        }
    }
}

private fun String.sanitizeFilename(): String = this.replace(Regex("""[\s/\\<>:"|?*]"""), "_")

