package com.github.alexeyleping.intellijdebugmcp.tools.psi

import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PsiToolHandlerTest : BasePlatformTestCase() {

    private lateinit var clientFile: com.intellij.psi.PsiFile

    private fun call(tool: String, vararg args: Pair<String, String>): String =
        PsiToolHandler(project).handle(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "src/com/example/Service.java",
            """
            package com.example;
            public class Service {
                private int counter = 0;
                public int increment(int by) {
                    counter += by;
                    return counter;
                }
            }
            """.trimIndent()
        )
        clientFile = myFixture.addFileToProject(
            "src/com/example/Client.java",
            """
            package com.example;
            public class Client {
                void use(Service service) {
                    service.increment(2);
                }
            }
            """.trimIndent()
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun warmUpReferenceIndex() {
        myFixture.configureFromExistingVirtualFile(clientFile.virtualFile)
        myFixture.doHighlighting()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testFindClassByFqn() {
        val out = call("find_class", "name" to "com.example.Service")

        assertTrue(out, out.contains("Class: com.example.Service"))
        assertTrue(out, out.contains("counter"))
        assertTrue(out, out.contains("increment(int by)"))
    }

    fun testFindClassByShortName() {
        val out = call("find_class", "name" to "Service")

        assertTrue(out, out.contains("com.example.Service"))
    }

    fun testFindClassMissing() {
        assertEquals("Class not found: NopeNope", call("find_class", "name" to "NopeNope"))
    }

    fun testGetFileStructure() {
        val path = myFixture.findFileInTempDir("src/com/example/Service.java").path
        val out = call("get_file_structure", "path" to path)

        assertTrue(out, out.contains("Structure of"))
        assertTrue(out, out.contains("class Service"))
        assertTrue(out, out.contains("field  int counter"))
        assertTrue(out, out.contains("method int increment(int by)"))
    }

    fun testGetFileStructureMissingFile() {
        val out = call("get_file_structure", "path" to "/no/such/File.java")

        assertTrue(out, out.contains("File not found"))
    }

    fun testFindUsagesOfUnknownMember() {
        val out = call("find_usages", "name" to "missing", "className" to "com.example.Service")

        assertTrue(out, out.contains("Member 'missing' not found"))
    }
}
