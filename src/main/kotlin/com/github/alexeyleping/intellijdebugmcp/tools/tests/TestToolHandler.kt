package com.github.alexeyleping.intellijdebugmcp.tools.tests

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
        private val log = logger<TestToolHandler>()

        private const val GRADLE_TIMEOUT_SECONDS = 900L
        private const val REUSED_CONFIG_TIMEOUT_SECONDS = 300L
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

        val existing = findMatchingSettings(className, methodName)
        if (existing != null) return runWithIdeRunner(existing, className, methodName)

        val gradleDir = project.basePath?.let { findGradleWrapper(File(it)) }
        if (gradleDir != null) return runWithGradle(className, methodName, gradleDir)

        return "No JUnit/TestNG run config found for '$className' and no Gradle wrapper in the project. " +
            "Create a run configuration in IntelliJ first."
    }

    private fun findMatchingSettings(className: String, methodName: String?): RunnerAndConfigurationSettings? {

        if (methodName != null) return null
        val runManager = RunManager.getInstance(project)
        val shortName = className.substringAfterLast('.')
        val junitOrTestNg = runManager.allSettings.filter { s ->
            val factory = s.configuration.factory?.name ?: ""
            factory.contains("JUnit", ignoreCase = true) || factory.contains("TestNG", ignoreCase = true)
        }
        return junitOrTestNg.firstOrNull { it.configuration.name.equals(shortName, ignoreCase = true) }
            ?: junitOrTestNg.firstOrNull { it.configuration.name.contains(shortName, ignoreCase = true) }
    }

    private fun runWithIdeRunner(
        existing: RunnerAndConfigurationSettings?,
        className: String,
        methodName: String?
    ): String {
        val future = CompletableFuture<String>()
        val collected = mutableListOf<TestResult>()

        ApplicationManager.getApplication().invokeLater {
            try {
                val runManager = RunManager.getInstance(project)
                val connection = project.messageBus.connect()
                connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
                    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                        connection.disconnect()
                        collectResults(testsRoot, collected)
                        lastResults[project] = collected
                        future.complete(formatResults(collected))
                    }
                })

                val settings = existing ?: buildMethodSettings(runManager, className, methodName ?: "")

                if (settings == null) {
                    connection.disconnect()
                    future.complete("No JUnit/TestNG run config found for '$className'. Create one in IntelliJ first.")
                    return@invokeLater
                }

                val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!
                val env = ExecutionEnvironmentBuilder.create(executor, settings).build()
                env.runner.execute(env)
            } catch (e: Exception) {
                future.complete("Error starting tests: ${e.message}")
            }
        }

        return awaitFuture(future, REUSED_CONFIG_TIMEOUT_SECONDS, "tests")
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

    private fun findGradleWrapper(projectDir: File): File? {
        val candidates = listOf(File(projectDir, "gradlew"), File(projectDir, "gradlew.bat"))
        return candidates.firstOrNull { it.isFile }?.let { projectDir }
    }

    internal fun detectModule(projectDir: File, className: String): String {
        val packagePath = className.substringBeforeLast('.', "").replace('.', '/')
        val simpleName = className.substringAfterLast('.')
        val sourceRoots = listOf("src/test/java", "src/test/kotlin", "src/test/groovy")
        val extensions = listOf("java", "kt", "groovy")
        val result = mutableListOf<File>()

        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            dir.listFiles()?.forEach { child ->
                if (!child.isDirectory) return@forEach
                if (child.name.startsWith(".") || child.name in setOf("build", "node_modules")) return@forEach
                for (root in sourceRoots) {
                    for (ext in extensions) {
                        if (File(child, "$root/$packagePath/$simpleName.$ext").isFile) result.add(child)
                    }
                }
                walk(child, depth + 1)
            }
        }
        walk(projectDir, 0)
        val found = result.maxByOrNull { it.path.length }
        return found?.relativeTo(projectDir)?.path?.replace('\\', '/') ?: ""
    }

    private fun isAndroidModule(dir: File): Boolean {
        if (File(dir, "local.properties").let { it.isFile && it.readText().contains("sdk.dir") }) return true
        listOf("build.gradle", "build.gradle.kts").forEach { name ->
            val f = File(dir, name)
            if (f.isFile && f.readText().contains("com.android")) return true
        }
        return false
    }

    private fun runWithGradle(className: String, methodName: String?, projectDir: File): String {
        val module = detectModule(projectDir, className)
        val moduleDir = if (module.isEmpty()) projectDir else File(projectDir, module)
        val taskName = if (isAndroidModule(moduleDir)) "testDebugUnitTest" else "test"
        val testTask = if (module.isEmpty()) taskName else ":$module:$taskName"

        val resultsDir = File(moduleDir, "build/test-results/$taskName")
        resultsDir.deleteRecursively()

        val cmd = mutableListOf(
            File(projectDir, "gradlew").absolutePath,
            testTask,
            "--tests", if (methodName != null) "$className.$methodName" else className,
            "--console=plain"
        )
        log.info("Running Gradle tests: ${cmd.joinToString(" ")}")

        val process = ProcessBuilder(cmd)
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(GRADLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "Timeout: Gradle tests did not finish in $GRADLE_TIMEOUT_SECONDS seconds"
        }

        val parsed = parseJUnitXml(resultsDir, className)
        if (parsed.isEmpty()) {
            return buildString {
                appendLine("Gradle task '$testTask' finished with exit code ${process.exitValue()}, " +
                    "but no JUnit XML results were found in ${resultsDir.path}.")
                appendLine("Output (last 40 lines):")
                output.lines().takeLast(40).forEach { appendLine("  $it") }
            }.trim()
        }

        lastResults[project] = parsed
        val summary = formatResults(parsed)
        return if (process.exitValue() == 0) summary
        else summary + "\n\n(Gradle exit code ${process.exitValue()})"
    }

    internal fun parseJUnitXml(resultsDir: File, className: String): List<TestResult> {
        if (!resultsDir.isDirectory) return emptyList()
        val wanted = "TEST-$className.xml"
        val files = resultsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".xml") &&
                (f.name == wanted || f.name.startsWith("TEST-${className.substringBeforeLast('.', "")}"))
        }?.toList() ?: return emptyList()
        val specific = files.filter { it.name == wanted }.ifEmpty { files }
        val results = mutableListOf<TestResult>()

        specific.forEach { file ->
            val xml = file.readText()
            Regex("<testcase\\s([^>]*?)(/>|>(.*?)</testcase>)", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .forEach { m ->
                    val attrs = m.groupValues[1]
                    val body = m.groupValues.getOrNull(3) ?: ""
                    val name = attr(attrs, "name") ?: "?"
                    val time = attr(attrs, "time")?.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
                    val failure = Regex("<(failure|error)\\s([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
                        .find(body)
                    val skipped = body.contains("<skipped")
                    val status = when {
                        failure != null && failure.groupValues[1] == "error" -> "ERROR"
                        failure != null -> "FAILED"
                        skipped -> "IGNORED"
                        else -> "PASSED"
                    }
                    val message = failure?.let { attr(it.groupValues[2], "message") }
                    val trace = failure?.groupValues?.get(3)?.let { unescapeXml(it) }?.trim()?.takeIf { it.isNotEmpty() }
                    results.add(TestResult(name, status, time, message, trace))
                }
        }
        return results
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.let { unescapeXml(it) }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun awaitFuture(future: CompletableFuture<String>, seconds: Long, what: String): String =
        try {
            future.get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "Timeout: $what did not complete in $seconds seconds"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }

    private fun formatResults(results: List<TestResult>): String {
        if (results.isEmpty()) return "No test results collected."
        val passed = results.count { it.status == "PASSED" }
        val failed = results.count { it.status == "FAILED" }
        val errors = results.count { it.status == "ERROR" }
        val ignored = results.count { it.status == "IGNORED" }
        return buildString {
            appendLine("Tests: ${results.size} total — $passed passed, $failed failed, $errors errors, $ignored ignored")
            val nonPassed = results.filter { it.status != "PASSED" && it.status != "IGNORED" }
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
