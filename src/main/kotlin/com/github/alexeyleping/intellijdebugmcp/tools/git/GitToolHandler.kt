package com.github.alexeyleping.intellijdebugmcp.tools.git

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GitToolHandler(private val project: Project) {

    fun handle(tool: String, args: JsonObject): String = when (tool) {
        "git_blame" -> {
            val path = args["path"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: path"
            val startLine = args["startLine"]?.jsonPrimitive?.content?.toIntOrNull()
            val endLine = args["endLine"]?.jsonPrimitive?.content?.toIntOrNull()
            gitBlame(path, startLine, endLine)
        }
        "git_log" -> {
            val path = args["path"]?.jsonPrimitive?.content
            val maxCount = args["maxCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            gitLog(path, maxCount)
        }
        "git_diff" -> {
            val path = args["path"]?.jsonPrimitive?.content
            val commit = args["commit"]?.jsonPrimitive?.content
            gitDiff(path, commit)
        }
        else -> "Unknown tool: $tool"
    }

    private fun runGit(vararg args: String): Pair<String, Int> {
        return try {
            val pb = ProcessBuilder("git", "-c", "color.ui=never", *args)
                .directory(File(project.basePath ?: "."))
                .redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            Pair(output, proc.waitFor())
        } catch (e: Exception) {
            Pair("git error: ${e.message}", 1)
        }
    }

    private fun gitBlame(path: String, startLine: Int?, endLine: Int?): String {
        val args = mutableListOf("blame", "--porcelain")
        if (startLine != null) {
            val end = endLine ?: startLine
            args.addAll(listOf("-L", "$startLine,$end"))
        }
        args.add(path)
        val (output, exitCode) = runGit(*args.toTypedArray())
        if (exitCode != 0) return "git blame failed:\n$output"

        val result = StringBuilder()
        val lines = output.lines()
        var i = 0
        while (i < lines.size) {
            val ln = lines[i]
            if (ln.length >= 41 && ln[0].isLetterOrDigit() && ln[40] == ' ') {
                val parts = ln.split(" ")
                if (parts.size >= 3) {
                    val hash = parts[0].take(8)
                    val finalLine = parts.getOrNull(2) ?: parts[1]
                    var author = ""; var date = ""; var summary = ""
                    var j = i + 1
                    while (j < lines.size && !lines[j].startsWith("\t")) {
                        when {
                            lines[j].startsWith("author ") -> author = lines[j].removePrefix("author ")
                            lines[j].startsWith("author-time ") -> {
                                val epoch = lines[j].removePrefix("author-time ").trim().toLongOrNull()
                                if (epoch != null) date = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(epoch * 1000))
                            }
                            lines[j].startsWith("summary ") -> summary = lines[j].removePrefix("summary ")
                        }
                        j++
                    }
                    val code = if (j < lines.size && lines[j].startsWith("\t")) lines[j].removePrefix("\t") else ""
                    result.appendLine("$finalLine: $hash | $date | $author | $summary")
                    if (startLine != null) result.appendLine("  Code: $code")
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return result.toString().trim().ifEmpty { "No blame data found" }
    }

    private fun gitLog(path: String?, maxCount: Int): String {
        val n = maxCount.coerceIn(1, 100)
        val args = buildList {
            add("log")
            add("--pretty=format:%H|%an|%ad|%s")
            add("--date=short")
            add("-n"); add("$n")
            if (path != null) { add("--"); add(path) }
        }
        val (output, exitCode) = runGit(*args.toTypedArray())
        if (exitCode != 0) return "git log failed:\n$output"
        if (output.isBlank()) return "No commits found${if (path != null) " for $path" else ""}"

        return buildString {
            appendLine("Recent ${n} commits${if (path != null) " for $path" else ""}:")
            output.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split("|", limit = 4)
                if (parts.size == 4) appendLine("  ${parts[0].take(8)} | ${parts[2]} | ${parts[1]} | ${parts[3]}")
            }
        }.trim()
    }

    private fun gitDiff(path: String?, commit: String?): String {
        val args = buildList {
            add("diff")
            if (commit != null) add(commit)
            if (path != null) { add("--"); add(path) }
        }
        val (output, exitCode) = runGit(*args.toTypedArray())
        if (exitCode != 0) return "git diff failed:\n$output"
        return output.ifBlank { "No differences found" }
    }
}
