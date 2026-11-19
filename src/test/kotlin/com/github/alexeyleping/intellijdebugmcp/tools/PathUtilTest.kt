package com.github.alexeyleping.intellijdebugmcp.tools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class PathUtilTest : BasePlatformTestCase() {

    private fun write(relativePath: String, content: String = "x"): File {
        val file = File(project.basePath!!, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    fun testResolvesRelativePath() {
        write("docs/file.txt")

        val vf = PathUtil.resolve(project, "docs/file.txt")

        assertNotNull(vf)
        assertTrue(vf!!.path.endsWith("docs/file.txt"))
    }

    fun testResolvesDotToProjectRoot() {
        val vf = PathUtil.resolve(project, ".")

        assertNotNull(vf)
        assertEquals(File(project.basePath!!).canonicalPath, File(vf!!.path).canonicalPath)
    }

    fun testResolvesParentSegments() {
        write("a/b/deep.txt")

        val vf = PathUtil.resolve(project, "a/b/../b/deep.txt")

        assertNotNull(vf)
        assertTrue(vf!!.path.endsWith("a/b/deep.txt"))
    }

    fun testRefreshesFileMissingInVfsCache() {
        write("fresh/nested/new.txt", "content")

        val fromDisk = PathUtil.resolve(project, "fresh/nested/new.txt")

        assertNotNull("Файл на диске должен резолвиться даже без предварительного refresh", fromDisk)
        val outsideReadAction = PathUtil.resolve(project, "fresh/nested/new.txt")
        assertNotNull(outsideReadAction)
    }

    fun testMissingFileReturnsNull() {
        assertNull(PathUtil.resolve(project, "no/such/file.txt"))
    }

    fun testAbsolutePathIsNotPrefixedWithProjectRoot() {
        val file = write("abs-here.txt")

        val vf = PathUtil.resolve(project, file.absolutePath)

        assertNotNull(vf)
        assertEquals(file.canonicalPath, File(vf!!.path).canonicalPath)
    }

    fun testRelativeHelperStripsProjectRoot() {
        val base = project.basePath!!

        assertEquals("src/A.kt", PathUtil.relative(project, "$base/src/A.kt"))
        assertEquals("/other/A.kt", PathUtil.relative(project, "/other/A.kt"))
    }
}
