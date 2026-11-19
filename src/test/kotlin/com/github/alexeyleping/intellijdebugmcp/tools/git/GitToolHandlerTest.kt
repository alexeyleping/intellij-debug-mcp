package com.github.alexeyleping.intellijdebugmcp.tools.git

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

class GitToolHandlerTest : BasePlatformTestCase() {

    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = File(project.basePath!!)
        root.mkdirs()
        git("init", "-q")
        git("config", "user.email", "mcp@example.com")
        git("config", "user.name", "MCP Test")
        File(root, "hello.txt").writeText("one\ntwo\n${System.nanoTime()}\n")
        File(root, "src").mkdirs()
        File(root, "src/App.java").writeText("class App {}\n")
        git("add", ".")
        git("-c", "user.email=mcp@example.com", "-c", "user.name=MCP Test", "commit", "-q", "-m", "initial commit")
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() != 0) {
            fail("git ${args.joinToString(" ")} failed:\n$output")
        }
    }

    private fun call(tool: String, vararg args: Pair<String, String>): String =
        GitToolHandler(project).handle(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    fun testGitLog() {
        val out = call("git_log", "maxCount" to "5")

        assertTrue(out, out.contains("initial commit"))
        assertTrue(out, out.contains("MCP Test"))
    }

    fun testGitLogForPath() {
        val out = call("git_log", "path" to "src/App.java", "maxCount" to "5")

        assertTrue(out, out.contains("initial commit"))
    }

    fun testGitBlame() {
        val out = call("git_blame", "path" to "hello.txt", "startLine" to "1", "endLine" to "2")

        assertTrue(out, out.contains("MCP Test"))
        assertTrue("В режиме диапазона должен печататься сам код:\n$out", out.contains("Code: one"))
        assertTrue(out, out.contains("Code: two"))
    }

    fun testGitDiffShowsChanges() {
        File(root, "hello.txt").writeText("one changed\ntwo\nthree\n")

        val out = call("git_diff", "path" to "hello.txt")

        assertTrue(out, out.contains("-one"))
        assertTrue(out, out.contains("+one changed"))
    }

    fun testGitDiffWithoutChanges() {
        assertEquals("No differences found", call("git_diff", "path" to "hello.txt"))
    }

    fun testGitBlameMissingFileReportsFailure() {
        val out = call("git_blame", "path" to "no-such-file.txt")

        assertTrue(out, out.contains("git blame failed"))
    }
}
