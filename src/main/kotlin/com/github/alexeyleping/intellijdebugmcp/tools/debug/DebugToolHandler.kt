package com.github.alexeyleping.intellijdebugmcp.tools.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XLineBreakpointImpl
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

class DebugToolHandler(private val project: Project) {

    companion object {
        private val BREAKPOINT_TYPE_EP =
            ExtensionPointName.create<XBreakpointType<*, *>>("com.intellij.xdebugger.breakpointType")
    }

    @Suppress("UNCHECKED_CAST")
    private fun addLineBreakpointUnchecked(
        manager: XBreakpointManager,
        type: XLineBreakpointType<*>,
        url: String,
        line: Int
    ) {
        val t = type as XLineBreakpointType<XBreakpointProperties<Any>>
        manager.addLineBreakpoint(t, url, line, null)
    }

    fun handle(tool: String, args: JsonObject): String {
        return when (tool) {
            "get_session_state" -> getSessionState()
            "resume" -> controlExecution { it.resume() }
            "pause" -> controlExecution { it.pause() }
            "step_over" -> controlExecution { it.stepOver(false) }
            "step_into" -> controlExecution { it.stepInto() }
            "step_out" -> controlExecution { it.stepOut() }
            "list_breakpoints" -> listBreakpoints()
            "get_stack_frames" -> getStackFrames()
            "select_frame" -> {
                val index = args["index"]?.jsonPrimitive?.intOrNull ?: return "Error: 'index' is required"
                selectFrame(index)
            }
            "get_variables" -> getVariables()
            "set_breakpoint" -> {
                val file = args["file"]?.jsonPrimitive?.content ?: return "Error: 'file' is required"
                val line = args["line"]?.jsonPrimitive?.intOrNull ?: return "Error: 'line' is required"
                setBreakpoint(file, line)
            }
            "remove_breakpoint" -> {
                val file = args["file"]?.jsonPrimitive?.content ?: return "Error: 'file' is required"
                val line = args["line"]?.jsonPrimitive?.intOrNull ?: return "Error: 'line' is required"
                removeBreakpoint(file, line)
            }
            "evaluate" -> {
                val expr = args["expression"]?.jsonPrimitive?.content ?: return "Error: 'expression' is required"
                evaluate(expr)
            }
            "stop_debug" -> stopDebug()
            "list_run_configs" -> listRunConfigs()
            "start_debug" -> {
                val name = args["name"]?.jsonPrimitive?.content ?: return "Error: 'name' is required"
                startDebug(name)
            }
            else -> "Unknown tool: $tool"
        }
    }

    private fun getSessionState(): String {
        val manager = XDebuggerManager.getInstance(project)
        val session = manager.currentSession ?: return "No active debug session"
        return buildString {
            appendLine("Session: ${session.sessionName}")
            appendLine("Paused: ${session.isPaused}")
            appendLine("Stopped: ${session.isStopped}")
            val frame = session.currentStackFrame
            if (frame != null) {
                appendLine("Current position: ${frame.sourcePosition?.file?.name}:${frame.sourcePosition?.line?.plus(1)}")
            }
        }.trim()
    }

    private fun controlExecution(action: (com.intellij.xdebugger.XDebugSession) -> Unit): String {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return "No active debug session"
        ApplicationManager.getApplication().invokeLater { action(session) }
        return "OK"
    }

    private fun listBreakpoints(): String {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val breakpoints = manager.allBreakpoints
        if (breakpoints.isEmpty()) return "No breakpoints set"
        return breakpoints.joinToString("\n") { bp ->
            when (bp) {
                is XLineBreakpointImpl<*> -> "${bp.presentableFilePath}:${bp.line + 1} [${if (bp.isEnabled) "enabled" else "disabled"}] type=${bp.type.javaClass.simpleName}"
                else -> bp.toString()
            }
        }
    }

    private fun getStackFrames(): String {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return "No active debug session"
        if (!session.isPaused) return "Session is not paused"
        val executionStack = session.suspendContext?.activeExecutionStack
            ?: return "No execution stack available"

        val future = CompletableFuture<String>()
        val frames = Collections.synchronizedList(mutableListOf<String>())

        executionStack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                stackFrames.forEach { frame ->
                    val pos = frame.sourcePosition
                    val i = frames.size
                    frames.add("#$i ${executionStack.displayName} ${pos?.file?.name ?: "?"}:${(pos?.line ?: -1) + 1}")
                }
                if (last && !future.isDone)
                    future.complete(if (frames.isEmpty()) "No frames" else frames.joinToString("\n"))
            }
            override fun errorOccurred(errorMessage: String) { future.complete("Error: $errorMessage") }
        })

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) { "Timeout waiting for stack frames" }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun selectFrame(index: Int): String {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return "No active debug session"
        if (!session.isPaused) return "Session is not paused"
        val executionStack = session.suspendContext?.activeExecutionStack
            ?: return "No execution stack available"

        val future = CompletableFuture<String>()
        val frames = Collections.synchronizedList(mutableListOf<XStackFrame>())

        executionStack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                frames.addAll(stackFrames)
                if (last) {
                    if (index < 0 || index >= frames.size) {
                        future.complete("Frame index out of range: $index (total: ${frames.size})")
                        return
                    }
                    val selected = frames[index]
                    ApplicationManager.getApplication().invokeLater {
                        session.setCurrentStackFrame(executionStack, selected)
                    }
                    val pos = selected.sourcePosition
                    future.complete("Switched to frame #$index: ${pos?.file?.name ?: "?"}:${(pos?.line ?: -1) + 1}")
                }
            }
            override fun errorOccurred(errorMessage: String) { future.complete("Error: $errorMessage") }
        })

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) { "Timeout waiting for stack frames" }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun getVariables(): String {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return "No active debug session"
        if (!session.isPaused) return "Session is not paused"
        val frame = session.currentStackFrame ?: return "No stack frame available"

        val future = CompletableFuture<String>()
        val vars = Collections.synchronizedList(mutableListOf<String>())
        val pending = AtomicInteger(0)
        val lastReceived = AtomicBoolean(false)

        fun checkDone() {
            if (lastReceived.get() && pending.get() == 0 && !future.isDone)
                future.complete(if (vars.isEmpty()) "No variables in current frame" else vars.joinToString("\n"))
        }

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                if (last) lastReceived.set(true)
                if (children.size() == 0) { checkDone(); return }
                pending.addAndGet(children.size())
                for (i in 0 until children.size()) {
                    val name = children.getName(i)
                    children.getValue(i).computePresentation(
                        makeValueNode(name, vars) { pending.decrementAndGet(); checkDone() },
                        XValuePlace.TOOLTIP
                    )
                }
            }
            override fun tooManyChildren(remaining: Int) {}
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { future.complete("Error: $errorMessage") }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { future.complete("Error: $errorMessage") }
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
            override fun isObsolete() = future.isDone
        })

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "Timeout waiting for variables"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun setBreakpoint(filePath: String, line: Int): String {
        val url = VfsUtil.pathToUrl(filePath)
        val vFile = VirtualFileManager.getInstance().findFileByUrl(url)
            ?: return "File not found: $filePath"

        val line0 = line - 1
        val type = BREAKPOINT_TYPE_EP.extensionList
            .filterIsInstance<XLineBreakpointType<*>>()
            .firstOrNull { it.canPutAt(vFile, line0, project) }
            ?: return "No breakpoint type supports $filePath:$line"

        val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
        val existing = breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpointImpl<*>>()
            .any { it.fileUrl == url && it.line == line0 }
        if (existing) return "Breakpoint already exists at $filePath:$line"

        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                addLineBreakpointUnchecked(breakpointManager, type, url, line0)
            }
        }
        return "Breakpoint set at $filePath:$line [type=${type.javaClass.simpleName}]"
    }

    private fun removeBreakpoint(filePath: String, line: Int): String {
        val url = VfsUtil.pathToUrl(filePath)
        val line0 = line - 1

        val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
        val bp = breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpointImpl<*>>()
            .firstOrNull { it.fileUrl == url && it.line == line0 }
            ?: return "No breakpoint at $filePath:$line"

        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                breakpointManager.removeBreakpoint(bp)
            }
        }
        return "Breakpoint removed at $filePath:$line"
    }

    private fun evaluate(expression: String): String {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return "No active debug session"
        if (!session.isPaused) return "Session is not paused"
        val frame = session.currentStackFrame ?: return "No stack frame available"
        val evaluator = frame.evaluator ?: return "No evaluator available for current frame"

        val future = CompletableFuture<String>()
        val result = Collections.synchronizedList(mutableListOf<String>())

        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) {
                value.computePresentation(
                    makeValueNode(expression, result) { future.complete(result.firstOrNull() ?: "null") },
                    XValuePlace.TOOLTIP
                )
            }
            override fun errorOccurred(errorMessage: String) {
                future.complete("Error: $errorMessage")
            }
        }, frame.sourcePosition)

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "Timeout waiting for evaluation result"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun stopDebug(): String {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: return "No active debug session"
        ApplicationManager.getApplication().invokeLater { session.stop() }
        return "Debug session stopped: ${session.sessionName}"
    }

    private fun listRunConfigs(): String {
        val configs = RunManager.getInstance(project).allSettings
        if (configs.isEmpty()) return "No run configurations found"
        return configs.joinToString("\n") { "[${it.type.displayName}] ${it.name}" }
    }

    private fun startDebug(configName: String): String {
        val settings = RunManager.getInstance(project).allSettings
            .find { it.name == configName }
            ?: return "Run configuration not found: $configName. Use list_run_configs to see available configurations."
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }
        return "Starting debug session: ${settings.name}"
    }

    private fun makeValueNode(
        name: String,
        vars: MutableList<String>,
        onDone: () -> Unit
    ): XValueNode = object : XValueNode {
        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val sb = StringBuilder()
            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(value: String) { sb.append(value) }
                override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
                override fun renderStringValue(value: String) { sb.append("\"$value\"") }
                override fun renderStringValue(value: String, additionalChars: String?, maxLength: Int) { sb.append("\"$value\"") }
                override fun renderNumericValue(value: String) { sb.append(value) }
                override fun renderKeywordValue(value: String) { sb.append(value) }
                override fun renderComment(comment: String) {}
                override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
                override fun renderError(error: String) { sb.append("<error: $error>") }
            })
            val type = presentation.type
            vars.add("$name = $sb${if (type != null) " : $type" else ""}")
            onDone()
        }
        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
            vars.add("$name = $value${if (type != null) " : $type" else ""}")
            onDone()
        }
        override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) {}
        override fun isObsolete() = false
    }
}
