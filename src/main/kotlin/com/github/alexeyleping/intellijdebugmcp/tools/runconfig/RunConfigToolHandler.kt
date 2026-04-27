package com.github.alexeyleping.intellijdebugmcp.tools.runconfig

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RunConfigToolHandler(private val project: Project) {

    fun handle(tool: String, args: JsonObject): String = when (tool) {
        "create_run_config" -> {
            val name = args["name"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: name"
            createRunConfig(name, args)
        }
        "update_run_config" -> {
            val name = args["name"]?.jsonPrimitive?.content
                ?: return "Missing required parameter: name"
            updateRunConfig(name, args)
        }
        else -> "Unknown tool: $tool"
    }

    private fun createRunConfig(name: String, args: JsonObject): String {
        val type = args["type"]?.jsonPrimitive?.content?.lowercase() ?: "application"
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            val runManager = RunManager.getInstance(project)
            val settings = when (type) {
                "junit", "test" -> {
                    val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
                    val s = runManager.createConfiguration(name, factory)
                    val cfg = s.configuration as JUnitConfiguration
                    args["testClass"]?.jsonPrimitive?.content?.let {
                        cfg.persistentData.MAIN_CLASS_NAME = it
                        cfg.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
                    }
                    args["vmOptions"]?.jsonPrimitive?.content?.let { cfg.vmParameters = it }
                    s
                }
                else -> {
                    val factory = ApplicationConfigurationType.getInstance().configurationFactories.first()
                    val s = runManager.createConfiguration(name, factory)
                    val cfg = s.configuration as ApplicationConfiguration
                    args["mainClass"]?.jsonPrimitive?.content?.let { cfg.mainClassName = it }
                    args["vmOptions"]?.jsonPrimitive?.content?.let { cfg.vmParameters = it }
                    args["programArgs"]?.jsonPrimitive?.content?.let { cfg.programParameters = it }
                    args["workingDir"]?.jsonPrimitive?.content?.let { cfg.workingDirectory = it }
                    args["envVars"]?.jsonObject?.let { env ->
                        cfg.envs = env.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                    }
                    s
                }
            }
            settings.storeInLocalWorkspace()
            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings
            result = "Created run configuration '$name' (type: $type)"
        }
        return result
    }

    private fun updateRunConfig(name: String, args: JsonObject): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.allSettings.find { it.name == name }
                ?: run { result = "Run configuration not found: $name"; return@invokeAndWait }

            when (val cfg = settings.configuration) {
                is ApplicationConfiguration -> {
                    args["mainClass"]?.jsonPrimitive?.content?.let { cfg.mainClassName = it }
                    args["vmOptions"]?.jsonPrimitive?.content?.let { cfg.vmParameters = it }
                    args["programArgs"]?.jsonPrimitive?.content?.let { cfg.programParameters = it }
                    args["workingDir"]?.jsonPrimitive?.content?.let { cfg.workingDirectory = it }
                    args["envVars"]?.jsonObject?.let { env ->
                        cfg.envs = env.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                    }
                    result = "Updated Application run configuration '$name'"
                }
                is JUnitConfiguration -> {
                    args["testClass"]?.jsonPrimitive?.content?.let {
                        cfg.persistentData.MAIN_CLASS_NAME = it
                        cfg.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
                    }
                    args["vmOptions"]?.jsonPrimitive?.content?.let { cfg.vmParameters = it }
                    result = "Updated JUnit run configuration '$name'"
                }
                else -> result = "Unsupported configuration type: ${cfg.javaClass.simpleName}"
            }
        }
        return result
    }
}
