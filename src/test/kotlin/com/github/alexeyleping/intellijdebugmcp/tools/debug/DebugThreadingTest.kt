package com.github.alexeyleping.intellijdebugmcp.tools.debug

import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DebugThreadingTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testSetBreakpointFromBackgroundThread() {
        val path = myFixture.addFileToProject(
            "src/com/example/Threaded.kt",
            """
            package com.example
            fun target() {
                val value = 42
                println(value)
            }
            """.trimIndent()
        ).virtualFile.path

        val future = CompletableFuture<String>()
        Thread {
            try {
                future.complete(
                    DebugToolHandler(project).handle(
                        "set_breakpoint",
                        JsonObject(mapOf("file" to JsonPrimitive(path), "line" to JsonPrimitive("3")))
                    )
                )
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }.apply { isDaemon = true }.start()

        val result = future.get(60, TimeUnit.SECONDS)

        assertTrue(result, result.contains("Breakpoint set"))
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
        assertEquals(1, breakpoints.size)
        assertNotNull(breakpoints.single().properties)
    }
}
