package com.github.alexeyleping.intellijdebugmcp.tools.runconfig

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RunConfigToolHandlerTest : BasePlatformTestCase() {

    private fun call(tool: String, vararg args: Pair<String, String>): String =
        RunConfigToolHandler(project).handle(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    fun testCreateApplicationRunConfig() {
        val out = call(
            "create_run_config",
            "name" to "mcp-app",
            "type" to "application",
            "mainClass" to "com.example.Main",
            "programArgs" to "--flag"
        )

        assertTrue(out, out.contains("Created run configuration 'mcp-app'"))

        val settings = RunManager.getInstance(project).allSettings.first { it.name == "mcp-app" }
        val cfg = settings.configuration as ApplicationConfiguration
        assertEquals("com.example.Main", cfg.mainClassName)
        assertEquals("--flag", cfg.programParameters)
        assertNotNull("Конфигурация без модуля не запускается", cfg.configurationModule?.module)
    }

    fun testUpdateApplicationRunConfig() {
        call("create_run_config", "name" to "mcp-app2", "mainClass" to "com.example.Main")

        val out = call("update_run_config", "name" to "mcp-app2", "mainClass" to "com.example.Other", "vmOptions" to "-Xmx128m")

        assertTrue(out, out.contains("Updated Application run configuration"))
        val settings = RunManager.getInstance(project).allSettings.first { it.name == "mcp-app2" }
        val cfg = settings.configuration as ApplicationConfiguration
        assertEquals("com.example.Other", cfg.mainClassName)
        assertEquals("-Xmx128m", cfg.vmParameters)
    }

    fun testDeleteRunConfig() {
        call("create_run_config", "name" to "mcp-to-delete", "mainClass" to "com.example.Main")

        val out = call("delete_run_config", "name" to "mcp-to-delete")

        assertTrue(out, out.contains("Deleted run configuration"))
        assertNull(RunManager.getInstance(project).allSettings.firstOrNull { it.name == "mcp-to-delete" })
    }

    fun testDeleteMissingRunConfig() {
        assertTrue(call("delete_run_config", "name" to "nope").contains("Run configuration not found"))
    }

    fun testUpdateMissingRunConfig() {
        val out = call("update_run_config", "name" to "no-such-config", "mainClass" to "x.Y")

        assertTrue(out, out.contains("Run configuration not found"))
    }

    fun testCreateWithoutName() {
        assertEquals("Missing required parameter: name", call("create_run_config", "mainClass" to "x.Y"))
    }

    fun testCreatedConfigIsListedByDebugTool() {
        call("create_run_config", "name" to "mcp-listed", "mainClass" to "com.example.Main")

        val out = com.github.alexeyleping.intellijdebugmcp.tools.debug.DebugToolHandler(project)
            .handle("list_run_configs", JsonObject(emptyMap()))

        assertTrue(out, out.contains("mcp-listed"))
    }
}
