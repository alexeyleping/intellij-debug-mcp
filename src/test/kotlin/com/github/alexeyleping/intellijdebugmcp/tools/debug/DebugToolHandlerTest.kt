package com.github.alexeyleping.intellijdebugmcp.tools.debug

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DebugToolHandlerTest : BasePlatformTestCase() {

    private val javaSource = """
        package com.example;
        public class BreakpointTarget {
            public void run() {
                int a = 1;
                System.out.println(a);
            }
        }
    """.trimIndent()

    private lateinit var filePath: String

    override fun setUp() {
        super.setUp()
        filePath = myFixture.addFileToProject("src/com/example/BreakpointTarget.java", javaSource)
            .virtualFile.path
    }

    override fun tearDown() {
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                    val manager = XDebuggerManager.getInstance(project).breakpointManager
                    manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                        .forEach { manager.removeBreakpoint(it) }
                }
            }
        } finally {
            super.tearDown()
        }
    }

    private fun handler() = DebugToolHandler(project)

    private fun call(tool: String, vararg args: Pair<String, String>): String =
        handler().handle(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    private fun lineBreakpoints(): List<XLineBreakpoint<*>> =
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()

    fun testSetBreakpointCreatesBreakpointWithProperties() {
        val result = call("set_breakpoint", "file" to filePath, "line" to "5")

        assertTrue(result, result.contains("Breakpoint set"))
        val bps = lineBreakpoints()
        assertEquals("Ожидался ровно один брейкпоинт, получено: $bps", 1, bps.size)

        val bp = bps.single()
        assertEquals(4, bp.line)
        assertTrue(bp.isEnabled)

        assertNotNull("properties не должны быть null — иначе NPE в JavaLineBreakpointType", bp.properties)
    }

    fun testDuplicateBreakpointIsRejected() {
        call("set_breakpoint", "file" to filePath, "line" to "5")
        val duplicate = call("set_breakpoint", "file" to filePath, "line" to "5")

        assertTrue(duplicate, duplicate.contains("already exists"))
        assertEquals(1, lineBreakpoints().size)
    }

    fun testListBreakpointsShowsLine() {
        call("set_breakpoint", "file" to filePath, "line" to "5")

        val out = call("list_breakpoints")

        assertTrue(out, out.contains("BreakpointTarget.java:5"))
        assertTrue(out, out.contains("enabled"))
    }

    fun testRemoveBreakpoint() {
        call("set_breakpoint", "file" to filePath, "line" to "5")

        val removed = call("remove_breakpoint", "file" to filePath, "line" to "5")

        assertTrue(removed, removed.contains("Breakpoint removed"))
        assertEquals(0, lineBreakpoints().size)
    }

    fun testRemoveMissingBreakpointReportsError() {
        val out = call("remove_breakpoint", "file" to filePath, "line" to "42")

        assertTrue(out, out.contains("No breakpoint at"))
    }

    fun testSetBreakpointInMissingFile() {
        val out = call("set_breakpoint", "file" to "/does/not/exist/Exist.java", "line" to "5")

        assertTrue(out, out.contains("File not found"))
    }

    fun testSetKotlinBreakpoint() {
        val kotlinPath = myFixture.addFileToProject(
            "src/com/example/Scratch.kt",
            """
            package com.example
            fun main() {
                val a = 1
                println(a)
            }
            """.trimIndent()
        ).virtualFile.path

        val result = call("set_breakpoint", "file" to kotlinPath, "line" to "3")

        assertTrue(result, result.contains("Breakpoint set"))
        val bp = lineBreakpoints().single()
        assertTrue("Ожидался Kotlin-тип брейкпоинта: ${bp.type}", bp.type.javaClass.simpleName.contains("Kotlin"))
        assertNotNull(bp.properties)
    }

    fun testSessionToolsWithoutSessionAreFriendly() {
        listOf(
            "get_session_state", "resume", "pause", "step_over", "step_into", "step_out",
            "get_stack_frames", "get_variables", "stop_debug"
        ).forEach { tool ->
            val out = call(tool)
            assertTrue("$tool → $out", out.contains("No active debug session"))
        }
        assertTrue(call("evaluate", "expression" to "1+1").contains("No active debug session"))
    }

    fun testMissingRequiredParameters() {
        assertTrue(call("set_breakpoint", "line" to "5").contains("'file' is required"))
        assertTrue(call("set_breakpoint", "file" to "a.java").contains("'line' is required"))
        assertTrue(call("evaluate").contains("'expression' is required"))
    }
}
