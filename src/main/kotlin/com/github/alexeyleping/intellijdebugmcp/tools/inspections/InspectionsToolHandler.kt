package com.github.alexeyleping.intellijdebugmcp.tools.inspections

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class InspectionsToolHandler(private val project: Project) {

    fun handle(tool: String, args: JsonObject): String {
        if (DumbService.isDumb(project)) return "IDE is indexing, please retry in a moment"
        return when (tool) {
            "get_inspections" -> {
                val path = args["path"]?.jsonPrimitive?.content
                val severity = args["severity"]?.jsonPrimitive?.content?.uppercase()
                getInspections(path, severity)
            }
            else -> "Unknown tool: $tool"
        }
    }

    @Suppress("UnstableApiUsage")
    private fun getInspections(path: String?, severityFilter: String?): String {
        return ReadAction.compute<String, Exception> {
            val base = project.basePath ?: ""

            val files = if (path != null) {
                val vf = LocalFileSystem.getInstance().findFileByPath(path)
                    ?: LocalFileSystem.getInstance().findFileByPath("$base/$path")
                    ?: return@compute "File not found: $path"
                listOf(vf)
            } else {
                FileEditorManager.getInstance(project).openFiles.toList()
            }

            if (files.isEmpty()) {
                return@compute "No open files to inspect. Open a file in the editor or specify 'path'."
            }

            data class Problem(val file: String, val line: Int, val severity: String, val message: String)
            val problems = mutableListOf<Problem>()

            files.forEach { vf ->
                val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@forEach
                val markupModel = DocumentMarkupModel.forDocument(document, project, false) ?: return@forEach
                val relPath = vf.path.let { p -> if (base.isNotEmpty()) p.removePrefix("$base/") else p }

                markupModel.allHighlighters.forEach highlighter@{ highlighter ->
                    val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: return@highlighter
                    if (info.description.isNullOrBlank()) return@highlighter

                    val sev = info.severity
                    val sevStr = when {
                        sev >= HighlightSeverity.ERROR -> "ERROR"
                        sev >= HighlightSeverity.WARNING -> "WARNING"
                        sev >= HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
                        else -> return@highlighter
                    }

                    if (severityFilter != null && sevStr != severityFilter) return@highlighter

                    val line = document.getLineNumber(highlighter.startOffset) + 1
                    val msg = info.description.replace(Regex("<[^>]+>"), "")
                    problems.add(Problem(relPath, line, sevStr, msg))
                }
            }

            if (problems.isEmpty()) {
                return@compute "No problems found${if (severityFilter != null) " with severity=$severityFilter" else ""}. " +
                    "Results reflect the current IDE daemon analysis — ensure files are open and analysis is complete."
            }

            buildString {
                appendLine("Found ${problems.size} problem(s):")
                problems.sortedWith(compareBy({ it.file }, { it.line })).forEach { p ->
                    appendLine("  ${p.file}:${p.line} [${p.severity}] ${p.message}")
                }
            }.trim()
        }
    }
}
