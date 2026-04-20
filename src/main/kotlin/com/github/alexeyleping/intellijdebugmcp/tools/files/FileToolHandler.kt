package com.github.alexeyleping.intellijdebugmcp.tools.files

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileToolHandler(private val project: Project) {

    fun handle(tool: String, args: JsonObject): String {
        return when (tool) {
        "read_file" -> {
            val path = args["path"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: path"
            val offset = args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 500
            readFile(path, offset, limit)
        }
        "list_files" -> listFiles(args["path"]?.jsonPrimitive?.content)
        "find_files" -> {
            val pattern = args["pattern"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: pattern"
            findFiles(pattern)
        }
        "search_in_files" -> {
            val query = args["query"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: query"
            searchInFiles(query, args["filePattern"]?.jsonPrimitive?.content)
        }
        "get_open_files" -> getOpenFiles()
        "open_file" -> {
            val path = args["path"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: path"
            openFile(path, args["line"]?.jsonPrimitive?.content?.toIntOrNull())
        }
        else -> "Unknown tool: $tool"
        }
    }

    private fun resolveVirtualFile(path: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }
        val base = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$base/$path")
    }

    private fun readFile(path: String, offset: Int, limit: Int): String {
        val vf = resolveVirtualFile(path) ?: return "File not found: $path"
        if (vf.isDirectory) return "Path is a directory: $path"
        return try {
            val lines = ReadAction.compute<List<String>, Exception> {
                vf.inputStream.bufferedReader().useLines { it.toList() }
            }
            val total = lines.size
            val slice = lines.drop(offset).take(limit)
            buildString {
                appendLine("File: ${vf.path} ($total lines total, showing ${offset + 1}–${offset + slice.size})")
                appendLine("---")
                slice.forEachIndexed { i, line -> appendLine("${offset + i + 1}\t$line") }
            }.trim()
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    private fun listFiles(path: String?): String {
        val dir = if (path != null) {
            resolveVirtualFile(path) ?: return "Path not found: $path"
        } else {
            val base = project.basePath ?: return "Project has no base path"
            LocalFileSystem.getInstance().findFileByPath(base) ?: return "Project root not found"
        }
        if (!dir.isDirectory) return "Not a directory: ${dir.path}"
        return ReadAction.compute<String, Exception> {
            val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            buildString {
                appendLine("Contents of ${dir.path}:")
                children.forEach { child ->
                    val tag = if (child.isDirectory) "DIR " else "FILE"
                    appendLine("  [$tag] ${child.name}")
                }
                appendLine("\n${children.count { it.isDirectory }} directories, ${children.count { !it.isDirectory }} files")
            }.trim()
        }
    }

    private fun findFiles(pattern: String): String {
        val base = project.basePath ?: return "Project has no base path"
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return "Project root not found"
        val regex = globToRegex(pattern)
        val matches = mutableListOf<String>()
        ReadAction.compute<Unit, Exception> { walkFiles(root, base, regex, matches) }
        if (matches.isEmpty()) return "No files found matching pattern: $pattern"
        return buildString {
            appendLine("Found ${matches.size} file(s) matching '$pattern':")
            matches.forEach { appendLine("  $it") }
        }.trim()
    }

    private fun walkFiles(dir: VirtualFile, basePath: String, regex: Regex, matches: MutableList<String>, maxResults: Int = 100) {
        if (matches.size >= maxResults) return
        if (dir.name.startsWith(".") && dir.isDirectory) return
        for (child in dir.children) {
            if (matches.size >= maxResults) return
            if (child.isDirectory) {
                walkFiles(child, basePath, regex, matches, maxResults)
            } else {
                val rel = child.path.removePrefix("$basePath/")
                if (regex.matches(child.name) || regex.matches(rel)) matches.add(rel)
            }
        }
    }

    private fun searchInFiles(query: String, filePattern: String?): String {
        val base = project.basePath ?: return "Project has no base path"
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return "Project root not found"
        val fileRegex = filePattern?.let { globToRegex(it) }
        val results = mutableListOf<String>()
        ReadAction.compute<Unit, Exception> { searchInDir(root, base, query.lowercase(), fileRegex, results) }
        if (results.isEmpty()) return "No matches found for: $query"
        return buildString {
            appendLine("Found ${results.size} match(es) for '$query':")
            results.forEach { appendLine(it) }
        }.trim()
    }

    private fun searchInDir(dir: VirtualFile, basePath: String, query: String, fileRegex: Regex?, results: MutableList<String>, maxResults: Int = 50) {
        if (results.size >= maxResults) return
        if (dir.name.startsWith(".") && dir.isDirectory) return
        for (child in dir.children) {
            if (results.size >= maxResults) return
            if (child.isDirectory) {
                searchInDir(child, basePath, query, fileRegex, results, maxResults)
            } else if (!child.fileType.isBinary && child.length < 1_000_000) {
                val rel = child.path.removePrefix("$basePath/")
                if (fileRegex != null && !fileRegex.matches(child.name) && !fileRegex.matches(rel)) continue
                try {
                    child.inputStream.bufferedReader().useLines { seq ->
                        seq.forEachIndexed { idx, line ->
                            if (results.size < maxResults && line.lowercase().contains(query)) {
                                results.add("  $rel:${idx + 1}: ${line.trim()}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun getOpenFiles(): String {
        return ReadAction.compute<String, Exception> {
            val files = FileEditorManager.getInstance(project).openFiles
            if (files.isEmpty()) return@compute "No files currently open."
            val base = project.basePath ?: ""
            buildString {
                appendLine("Open files (${files.size}):")
                files.forEach { vf ->
                    val rel = if (base.isNotEmpty()) vf.path.removePrefix("$base/") else vf.path
                    appendLine("  $rel")
                }
            }.trim()
        }
    }

    private fun openFile(path: String, line: Int?): String {
        val vf = resolveVirtualFile(path) ?: return "File not found: $path"
        if (vf.isDirectory) return "Cannot open a directory: $path"
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val descriptor = if (line != null) {
                    OpenFileDescriptor(project, vf, line - 1, 0)
                } else {
                    OpenFileDescriptor(project, vf)
                }
                descriptor.navigate(true)
                result = "Opened: ${vf.path}" + (line?.let { " at line $it" } ?: "")
            } catch (e: Exception) {
                result = "Error opening file: ${e.message}"
            }
        }
        return result
    }

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.' -> sb.append("\\.")
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
