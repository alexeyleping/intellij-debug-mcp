package com.github.alexeyleping.intellijdebugmcp.tools.files

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

class FileToolHandlerTest : BasePlatformTestCase() {

    private fun handler() = FileToolHandler(project)

    private fun call(tool: String, vararg args: Pair<String, String>): String =
        handler().handle(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    private fun write(relativePath: String, content: String = ""): VirtualFile {
        val file = File(project.basePath!!, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)
            ?: error("Cannot find created file: ${file.path}")
    }

    fun testProjectBasePathIsRealDirectory() {
        assertTrue(
            "project.basePath должен указывать на существующую папку: ${project.basePath}",
            File(project.basePath!!).isDirectory
        )
    }

    fun testListFilesDotResolvesToProjectRoot() {
        write("root-marker.txt", "marker")

        val out = call("list_files", "path" to ".")

        assertTrue("Корень проекта должен содержать созданный файл:\n$out", out.contains("root-marker.txt"))
        assertFalse("Каталог установки IDE не должен попадать в вывод:\n$out", out.contains("idea.sh"))
    }

    fun testReadFileRelativePath() {
        write("docs/readme.txt", "line1\nline2\nline3")

        val out = call("read_file", "path" to "docs/readme.txt", "offset" to "1", "limit" to "1")

        assertTrue(out, out.contains("line2"))
        assertFalse("offset должен отрезать первую строку:\n$out", out.contains("line1"))
        assertFalse("limit должен отрезать третью строку:\n$out", out.contains("line3"))
    }

    fun testReadFileAbsolutePath() {
        val vf = write("abs.txt", "absolute-content")

        val out = call("read_file", "path" to vf.path)

        assertTrue(out, out.contains("absolute-content"))
    }

    fun testReadFileMissing() {
        assertEquals("File not found: nope.txt", call("read_file", "path" to "nope.txt"))
    }

    fun testFindFilesSkipsGeneratedDirectories() {
        write("docs/keep.md", "keep")
        write("node_modules/pkg/README.md", "noise")
        write("build/generated.md", "noise")

        val out = call("find_files", "pattern" to "*.md")

        assertTrue("Ожидался docs/keep.md:\n$out", out.contains("docs/keep.md"))
        assertFalse("node_modules не должен обходиться:\n$out", out.contains("node_modules"))
        assertFalse("build не должен обходиться:\n$out", out.contains("build/generated.md"))
    }

    fun testSearchInFiles() {
        write("docs/search.txt", "first\nNEEDLE here\nthird")

        val out = call("search_in_files", "query" to "needle")

        assertTrue(out, out.contains("docs/search.txt:2"))
    }

    fun testSearchInFilesWithFilePattern() {
        write("a.txt", "needle")
        write("b.java", "needle")

        val out = call("search_in_files", "query" to "needle", "filePattern" to "*.java")

        assertTrue(out, out.contains("b.java:1"))
        assertFalse("Фильтр *.java не должен пропускать .txt:\n$out", out.contains("a.txt"))
    }

    fun testOpenFileAndGetOpenFiles() {
        write("src/opened.txt", "content")

        val openResult = call("open_file", "path" to "src/opened.txt", "line" to "1")
        assertTrue(openResult, openResult.contains("Opened"))

        val openFiles = call("get_open_files")
        assertTrue(openFiles, openFiles.contains("src/opened.txt"))
    }

    fun testOpenDirectoryRejected() {
        File(project.basePath!!, "somedir").mkdirs()
        LocalFileSystem.getInstance().refreshAndFindFileByPath(File(project.basePath!!, "somedir").path)

        val out = call("open_file", "path" to "somedir")

        assertTrue(out, out.contains("Cannot open a directory"))
    }
}
