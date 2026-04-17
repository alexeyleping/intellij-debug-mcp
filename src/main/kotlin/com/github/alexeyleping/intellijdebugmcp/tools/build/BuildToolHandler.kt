package com.github.alexeyleping.intellijdebugmcp.tools.build

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BuildToolHandler(private val project: Project) {

    data class BuildMessage(
        val category: String,
        val file: String,
        val line: Int,
        val message: String
    )

    companion object {
        private val lastMessages = ConcurrentHashMap<Project, List<BuildMessage>>()
    }

    fun handle(tool: String, args: JsonObject): String = when (tool) {
        "build_project" -> {
            val rebuild = args["rebuild"]?.jsonPrimitive?.content.toBoolean()
            buildProject(rebuild)
        }
        "get_build_errors" -> getBuildErrors()
        else -> "Unknown tool: $tool"
    }

    private fun buildProject(rebuild: Boolean): String {
        val future = CompletableFuture<String>()

        ApplicationManager.getApplication().invokeLater {
            val manager = CompilerManager.getInstance(project)
            val notification = com.intellij.openapi.compiler.CompileStatusNotification {
                    aborted, errorCount, warningCount, context ->
                if (aborted) {
                    future.complete("Build aborted")
                    return@CompileStatusNotification
                }

                val errors = context.getMessages(CompilerMessageCategory.ERROR).map { msg ->
                    BuildMessage("ERROR", msg.virtualFile?.path ?: "unknown", getLine(msg), msg.message)
                }
                val warnings = context.getMessages(CompilerMessageCategory.WARNING).map { msg ->
                    BuildMessage("WARNING", msg.virtualFile?.path ?: "unknown", getLine(msg), msg.message)
                }
                lastMessages[project] = errors + warnings

                val status = if (errorCount == 0) "SUCCESS" else "FAILED"
                future.complete(buildString {
                    appendLine("Build $status: $errorCount error(s), $warningCount warning(s)")
                    if (errors.isNotEmpty()) {
                        appendLine("\nErrors:")
                        errors.forEach { appendLine("  ${it.file}:${it.line} — ${it.message}") }
                    }
                    if (warnings.isNotEmpty()) {
                        appendLine("\nWarnings:")
                        warnings.forEach { appendLine("  ${it.file}:${it.line} — ${it.message}") }
                    }
                }.trim())
            }

            if (rebuild) manager.rebuild(notification) else manager.make(notification)
        }

        return try {
            future.get(120, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "Timeout: build did not complete in 120 seconds"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getLine(msg: com.intellij.openapi.compiler.CompilerMessage): Int =
        try {
            (msg.javaClass.getMethod("getLine").invoke(msg) as? Long)?.toInt() ?: -1
        } catch (_: Exception) { -1 }

    private fun getBuildErrors(): String {
        val messages = lastMessages[project]
            ?: return "No build has been run yet. Use build_project first."
        if (messages.isEmpty()) return "No errors or warnings in last build."
        return messages.joinToString("\n") { "[${it.category}] ${it.file}:${it.line} — ${it.message}" }
    }
}
