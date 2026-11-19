package com.github.alexeyleping.intellijdebugmcp.tools.tests

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class GradleTestResultsTest : BasePlatformTestCase() {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.FooTest" tests="3" skipped="1" failures="1" errors="0" time="0.123">
          <testcase name="passes()" classname="com.example.FooTest" time="0.01"/>
          <testcase name="fails()" classname="com.example.FooTest" time="0.02">
            <failure message="expected: &lt;true&gt; but was: &lt;false&gt;" type="java.lang.AssertionError">at com.example.FooTest.fails(FooTest.java:10)
        at java.base/java.lang.reflect.Method.invoke(Method.java:580)</failure>
          </testcase>
          <testcase name="skipped()" classname="com.example.FooTest" time="0">
            <skipped/>
          </testcase>
        </testsuite>
    """.trimIndent()

    private fun newTempDir(): File = kotlin.io.path.createTempDirectory("mcp-test").toFile()

    private fun writeResults(dir: File, fileName: String, content: String) {
        dir.mkdirs()
        File(dir, fileName).writeText(content)
    }

    fun testParsesPassedFailedAndIgnored() {
        val dir = newTempDir()
        writeResults(dir, "TEST-com.example.FooTest.xml", xml)

        val results = TestToolHandler(project).parseJUnitXml(dir, "com.example.FooTest")

        assertEquals(3, results.size)
        assertEquals("PASSED", results.first { it.name == "passes()" }.status)
        assertEquals("FAILED", results.first { it.name == "fails()" }.status)
        assertEquals("IGNORED", results.first { it.name == "skipped()" }.status)

        val failed = results.first { it.name == "fails()" }
        assertTrue("Сообщение об ошибке должно попадать в результат: ${failed.errorMessage}",
            failed.errorMessage?.contains("expected: <true>") == true)
        assertTrue("Стек-трейс должен попадать в результат", failed.stackTrace?.contains("FooTest.fails") == true)
        assertEquals(20L, failed.duration)
    }

    fun testParsesErrorElement() {
        val dir = newTempDir()
        writeResults(
            dir, "TEST-com.example.ErrorTest.xml",
            """
            <testsuite name="com.example.ErrorTest" tests="1">
              <testcase name="boom()" classname="com.example.ErrorTest" time="0.1">
                <error message="NullPointerException" type="java.lang.NullPointerException">at x.y(z)</error>
              </testcase>
            </testsuite>
            """.trimIndent()
        )

        val results = TestToolHandler(project).parseJUnitXml(dir, "com.example.ErrorTest")

        assertEquals("ERROR", results.single().status)
        assertEquals("NullPointerException", results.single().errorMessage)
    }

    fun testReturnsEmptyWhenNoXml() {
        val dir = newTempDir()

        assertTrue(TestToolHandler(project).parseJUnitXml(dir, "com.example.FooTest").isEmpty())
        assertTrue(TestToolHandler(project).parseJUnitXml(File(dir, "missing"), "com.example.FooTest").isEmpty())
    }

    fun testDetectModuleFindsSubmodule() {
        val root = newTempDir()
        File(root, "module-a/src/test/java/com/example").mkdirs()
        File(root, "module-a/src/test/java/com/example/FooTest.java")
            .writeText("package com.example; public class FooTest {}")

        assertEquals("module-a", TestToolHandler(project).detectModule(root, "com.example.FooTest"))
    }

    fun testDetectModuleForRootProject() {
        val root = newTempDir()
        File(root, "src/test/kotlin/com/example").mkdirs()
        File(root, "src/test/kotlin/com/example/BarTest.kt")
            .writeText("package com.example\nclass BarTest")

        assertEquals("", TestToolHandler(project).detectModule(root, "com.example.BarTest"))
    }

    fun testDetectModulePrefersDeepestMatch() {
        val root = newTempDir()
        File(root, "services/api/src/test/java/com/example").mkdirs()
        File(root, "services/api/src/test/java/com/example/BazTest.java")
            .writeText("package com.example; public class BazTest {}")

        assertEquals("services/api", TestToolHandler(project).detectModule(root, "com.example.BazTest"))
    }
}
