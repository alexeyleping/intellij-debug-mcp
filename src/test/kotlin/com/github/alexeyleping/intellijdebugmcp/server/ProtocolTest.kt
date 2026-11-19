package com.github.alexeyleping.intellijdebugmcp.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProtocolTest : BasePlatformTestCase() {

    private fun service() = McpServerServiceImpl()

    private fun call(body: String): JsonObject =
        Json.parseToJsonElement(service().handleJsonRpc(body)).jsonObject

    private fun toolNames(): List<String> {
        val response = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        return response["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    fun testInitializeReturnsServerInfo() {
        val response = call(
            """{"jsonrpc":"2.0","id":7,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        )
        assertEquals("2.0", response["jsonrpc"]!!.jsonPrimitive.content)

        val result = response["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)

        val info = result["serverInfo"]!!.jsonObject
        assertEquals("intellij-debug-mcp", info["name"]!!.jsonPrimitive.content)
        assertFalse(
            "Версия сервера не должна быть захардкожена старым значением",
            info["version"]!!.jsonPrimitive.content == "0.9.0"
        )
    }

    fun testToolsListContainsEveryGroup() {
        val names = toolNames()

        listOf(
            "set_breakpoint", "remove_breakpoint", "list_breakpoints", "start_debug", "stop_debug",
            "get_session_state", "resume", "pause", "step_over", "step_into", "step_out",
            "get_stack_frames", "select_frame", "get_variables", "evaluate", "list_run_configs"
        ).forEach { assertTrue("Missing debug tool: $it", it in names) }

        listOf("build_project", "get_build_errors", "run_tests", "get_test_results").forEach {
            assertTrue("Missing tool: $it", it in names)
        }

        listOf(
            "read_file", "list_files", "find_files", "search_in_files", "get_open_files", "open_file",
            "find_class", "find_usages", "get_file_structure", "get_inspections",
            "git_blame", "git_log", "git_diff", "create_run_config", "update_run_config"
        ).forEach { assertTrue("Missing tool: $it", it in names) }
    }

    fun testToolsListHasNoDuplicates() {
        val names = toolNames()
        assertEquals("Duplicate tool names in tools/list: $names", names.size, names.toSet().size)
    }

    fun testEveryToolHasObjectInputSchema() {
        val response = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray

        tools.forEach { tool ->
            val t = tool.jsonObject
            assertTrue("Tool without description: ${t["name"]}", t["description"] != null)
            val schema = t["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertNotNull("Tool without properties: ${t["name"]}", schema["properties"])
            assertNotNull("Tool without required list: ${t["name"]}", schema["required"])
        }
    }

    fun testUnknownMethodErrorEchoesRequestId() {
        val response = call("""{"jsonrpc":"2.0","id":"abc-42","method":"nope"}""")

        assertEquals("abc-42", response["id"]!!.jsonPrimitive.content)
        val error = response["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.content.toInt())
    }

    fun testInvalidJsonReturnsParseError() {

        val response = call("""{"jsonrpc":"2.0","id":5,"method":"tools/list",,}""")

        assertNull(response["id"])
        assertEquals(-32700, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    fun testInvalidParamsEchoesRequestId() {

        val response = call("""{"jsonrpc":"2.0","id":"boom-1","method":"tools/call","params":[]}""")

        assertEquals("boom-1", response["id"]!!.jsonPrimitive.content)
        assertEquals(-32602, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    fun testHealthAndHttpAreNotAffectedByProtocolChanges() {

        val raw = service().handleJsonRpc("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        val parsed = Json.parseToJsonElement(raw).jsonObject
        assertTrue(parsed.containsKey("result"))
        assertFalse(parsed.containsKey("error"))
    }
}
