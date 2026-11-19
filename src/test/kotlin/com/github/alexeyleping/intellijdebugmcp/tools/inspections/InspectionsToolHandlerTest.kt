package com.github.alexeyleping.intellijdebugmcp.tools.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class InspectionsToolHandlerTest : BasePlatformTestCase() {

    private fun call(vararg args: Pair<String, String>): String =
        InspectionsToolHandler(project).handle(
            "get_inspections",
            JsonObject(args.associate { it.first to JsonPrimitive(it.second) })
        )

    fun testReportsErrorsFromOpenFile() {
        val file = myFixture.configureByText(
            "Probe.java",
            "public class Probe { int broken() { return undefinedSymbol + 1; } }"
        )
        myFixture.doHighlighting()

        val out = call("path" to file.virtualFile.path, "severity" to "ERROR")

        assertTrue("Ожидалась ошибка компиляции в выводе:\n$out", out.contains("[ERROR]"))
        assertTrue(out, out.contains("undefinedSymbol"))
    }

    fun testMissingFile() {
        assertTrue(call("path" to "/no/such/File.java").contains("File not found"))
    }

    fun testNoProblemsForCleanFile() {
        val file = myFixture.configureByText("Clean.java", "public class Clean { int ok() { return 1; } }")
        myFixture.doHighlighting()

        val out = call("path" to file.virtualFile.path)

        assertTrue(out, out.contains("No problems found"))
    }
}
