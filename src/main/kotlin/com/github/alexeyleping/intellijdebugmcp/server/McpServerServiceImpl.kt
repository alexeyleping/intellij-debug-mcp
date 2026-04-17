package com.github.alexeyleping.intellijdebugmcp.server

import com.github.alexeyleping.intellijdebugmcp.tools.build.BuildToolHandler
import com.github.alexeyleping.intellijdebugmcp.tools.debug.DebugToolHandler
import com.github.alexeyleping.intellijdebugmcp.tools.tests.TestToolHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
class McpServerServiceImpl : McpServerService, Disposable {

    private val log = logger<McpServerServiceImpl>()
    private val port = 63820
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeProject = AtomicReference<Project?>(null)

    private fun resolveProject(): Project? {
        val current = activeProject.get()
        if (current != null && !current.isDisposed) return current
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    }

    override fun setActiveProject(project: Project) {
        activeProject.set(project)
        log.info("Active project set to: ${project.name}")
    }

    override fun clearActiveProject(project: Project) {
        activeProject.compareAndSet(project, null)
        val fallback = ProjectManager.getInstance().openProjects.firstOrNull { it != project && !it.isDisposed }
        if (fallback != null) activeProject.set(fallback)
        log.info("Active project cleared for: ${project.name}, fallback: ${fallback?.name}")
    }

    override fun start() {
        if (server != null) return
        log.info("Starting MCP server on port $port")

        server = embeddedServer(Netty, port = port) {
            routing {
                post("/mcp") {
                    val body = call.receiveText()
                    val response = handleJsonRpc(body)
                    call.respondText(response, contentType = io.ktor.http.ContentType.Application.Json)
                }
                get("/health") {
                    val project = resolveProject()
                    call.respondText("OK — active project: ${project?.name ?: "none"}")
                }
            }
        }

        scope.launch {
            server?.start(wait = false)
            log.info("MCP server started on port $port")
        }
    }

    override fun stop() {
        server?.stop(1000, 3000)
        server = null
        log.info("MCP server stopped")
    }

    override fun isRunning(): Boolean = server != null

    override fun getPort(): Int = port

    private fun handleJsonRpc(body: String): String {
        return try {
            val request = Json.parseToJsonElement(body).jsonObject
            val method = request["method"]?.jsonPrimitive?.content ?: ""
            val id = request["id"]

            when (method) {
                "initialize" -> buildResult(id, buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", buildJsonObject {
                        put("name", "intellij-debug-mcp")
                        put("version", "0.4.0")
                    })
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject {})
                    })
                })

                "tools/list" -> buildResult(id, buildJsonObject {
                    put("tools", buildJsonArray {
                        // Debug tools
                        add(toolDef("set_breakpoint", "Set a breakpoint at the given file and line",
                            mapOf("file" to "string", "line" to "integer")))
                        add(toolDef("remove_breakpoint", "Remove a breakpoint at the given file and line",
                            mapOf("file" to "string", "line" to "integer")))
                        add(toolDef("list_breakpoints", "List all active breakpoints", emptyMap()))
                        add(toolDef("get_session_state", "Get current debug session state", emptyMap()))
                        add(toolDef("resume", "Resume execution", emptyMap()))
                        add(toolDef("pause", "Pause execution", emptyMap()))
                        add(toolDef("step_over", "Step over current line", emptyMap()))
                        add(toolDef("step_into", "Step into method call", emptyMap()))
                        add(toolDef("step_out", "Step out of current method", emptyMap()))
                        add(toolDef("get_stack_frames", "Get full call stack with frame indices", emptyMap()))
                        add(toolDef("select_frame", "Switch to a specific stack frame by index (use get_stack_frames to see indices)",
                            mapOf("index" to "integer")))
                        add(toolDef("get_variables", "Get variables in current frame", emptyMap()))
                        add(toolDef("evaluate", "Evaluate an expression in current frame",
                            mapOf("expression" to "string")))
                        add(toolDef("stop_debug", "Stop the current debug session", emptyMap()))
                        add(toolDef("list_run_configs", "List all run configurations in the project", emptyMap()))
                        add(toolDef("start_debug", "Start a debug session for the given run configuration name",
                            mapOf("name" to "string")))
                        // Test tools
                        add(toolDef("run_tests",
                            "Run tests for the given class (and optionally a single method). Uses existing JUnit/TestNG run config.",
                            mapOf("className" to "string"),
                            mapOf("methodName" to "string")))
                        add(toolDef("get_test_results",
                            "Return results of the last run_tests call",
                            emptyMap()))
                        // Build tools
                        add(toolDef("build_project",
                            "Build the project (incremental Make). Pass rebuild=true for a full Rebuild.",
                            emptyMap(), mapOf("rebuild" to "boolean")))
                        add(toolDef("get_build_errors",
                            "Return errors and warnings from the last build_project call",
                            emptyMap()))
                    })
                })

                "tools/call" -> {
                    val params = request["params"]?.jsonObject
                    val toolName = params?.get("name")?.jsonPrimitive?.content ?: ""
                    val toolArgs = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())
                    val project = resolveProject()
                        ?: return buildError(id, -32603, "No open project found in IntelliJ")
                    val result = when (toolName) {
                        "run_tests", "get_test_results" ->
                            TestToolHandler(project).handle(toolName, toolArgs)
                        "build_project", "get_build_errors" ->
                            BuildToolHandler(project).handle(toolName, toolArgs)
                        else -> DebugToolHandler(project).handle(toolName, toolArgs)
                    }
                    buildResult(id, buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", result)
                            })
                        })
                    })
                }

                else -> buildError(id, -32601, "Method not found: $method")
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            buildError(JsonNull, -32700, "Invalid JSON")
        } catch (e: Exception) {
            log.error("Error handling JSON-RPC request", e)
            buildError(JsonNull, -32603, "Internal error")
        }
    }

    private fun toolDef(
        name: String,
        description: String,
        params: Map<String, String>,
        optionalParams: Map<String, String> = emptyMap()
    ): JsonObject {
        return buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    params.forEach { (k, v) -> put(k, buildJsonObject { put("type", v) }) }
                    optionalParams.forEach { (k, v) -> put(k, buildJsonObject { put("type", v) }) }
                })
                put("required", buildJsonArray { params.keys.forEach { add(it) } })
            })
        }
    }

    private fun buildResult(id: JsonElement?, result: JsonObject): String {
        return Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("result", result)
        })
    }

    private fun buildError(id: JsonElement?, code: Int, message: String): String {
        return Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        })
    }

    override fun dispose() {
        stop()
    }
}
