package com.github.alexeyleping.intellijdebugmcp.tools.tests

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TestToolHandler(private val project: Project) {

    data class TestResult(
        val name: String,
        val status: String,
        val duration: Long,
        val errorMessage: String?,
        val stackTrace: String?
    )

    companion object {
        private val lastResults = ConcurrentHashMap<Project, List<TestResult>>()
    }

    fun handle(tool: String, args: JsonObject): String {
        return when (tool) {
            "run_tests" -> {
                val className = args["className"]?.jsonPrimitive?.content
                    ?: return "Missing required parameter: className"
                val methodName = args["methodName"]?.jsonPrimitive?.content
                runTests(className, methodName)
            }
            "get_test_results" -> getTestResults()
            else -> "Unknown tool: $tool"
        }
    }

    private fun runTests(className: String, methodName: String?): String {
        val future = CompletableFuture<String>()
        val collected = mutableListOf<TestResult>()

        ApplicationManager.getApplication().invokeLater {
            try {
                val runManager = RunManager.getInstance(project)

                // Subscribe to SM runner events BEFORE starting — works regardless of console type
                val connection = project.messageBus.connect()
                connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
                    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                        connection.disconnect()
                        collectResults(testsRoot, collected)
                        lastResults[project] = collected
                        future.complete(formatResults(collected))
                    }
                })

                val settings = if (methodName != null) {
                    buildMethodSettings(runManager, className, methodName)
                } else {
                    findClassSettings(runManager, className)
                }

                if (settings == null) {
                    connection.disconnect()
                    val target = if (methodName != null) "$className#$methodName" else className
                    future.complete("No JUnit/TestNG run config found for '$target'. Create one in IntelliJ first.")
                    return@invokeLater
                }

                val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!
                val env = ExecutionEnvironmentBuilder.create(executor, settings).build()
                env.runner.execute(env)

            } catch (e: Exception) {
                future.complete("Error starting tests: ${e.message}")
            }
        }

        return try {
            future.get(120, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "Timeout: tests did not complete in 120 seconds"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun findClassSettings(runManager: RunManager, className: String) =
        runManager.allSettings.firstOrNull { s ->
            s.configuration.name.contains(className.substringAfterLast('.'), ignoreCase = true)
        } ?: runManager.allSettings.firstOrNull { s ->
            val factory = s.configuration.factory?.name ?: ""
            factory.contains("JUnit", ignoreCase = true) || factory.contains("TestNG", ignoreCase = true)
        }

    private fun buildMethodSettings(runManager: RunManager, className: String, methodName: String) =
        try {
            val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
            val settings = runManager.createConfiguration("$className#$methodName", factory)
            val cfg = settings.configuration as JUnitConfiguration
            cfg.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
            cfg.persistentData.MAIN_CLASS_NAME = className
            cfg.persistentData.METHOD_NAME = methodName
            cfg.setModule(cfg.validModules.firstOrNull())
            settings
        } catch (e: Exception) {
            null
        }

    private fun collectResults(node: SMTestProxy, collected: MutableList<TestResult>) {
        if (!node.isLeaf) {
            node.children.forEach { collectResults(it, collected) }
            return
        }
        val status = when {
            node.isPassed -> "PASSED"
            node.isIgnored -> "IGNORED"
            node.isDefect -> "FAILED"
            else -> "ERROR"
        }
        collected.add(TestResult(
            name = node.name,
            status = status,
            duration = node.duration ?: 0L,
            errorMessage = node.errorMessage,
            stackTrace = node.stacktrace
        ))
    }

    private fun formatResults(results: List<TestResult>): String {
        if (results.isEmpty()) return "No test results collected."
        val passed = results.count { it.status == "PASSED" }
        val failed = results.count { it.status == "FAILED" }
        val errors = results.count { it.status == "ERROR" }
        val ignored = results.count { it.status == "IGNORED" }
        return buildString {
            appendLine("Tests: ${results.size} total — $passed passed, $failed failed, $errors errors, $ignored ignored")
            val nonPassed = results.filter { it.status != "PASSED" }
            if (nonPassed.isNotEmpty()) {
                appendLine("\nFailed/Error tests:")
                nonPassed.forEach { t ->
                    appendLine("  [${t.status}] ${t.name} (${t.duration}ms)")
                    t.errorMessage?.let { appendLine("    Message: $it") }
                    t.stackTrace?.lines()?.take(5)?.forEach { l -> appendLine("    $l") }
                }
            }
            if (passed == results.size) appendLine("\nAll tests passed!")
        }.trim()
    }

    private fun getTestResults(): String {
        val results = lastResults[project]
            ?: return "No tests have been run yet. Use run_tests first."
        return formatResults(results)
    }
}
