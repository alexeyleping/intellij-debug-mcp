package com.github.alexeyleping.intellijdebugmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File

object PathUtil {

    fun resolve(project: Project, path: String): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        if (isAbsolute(path)) return fs.findFileByPath(path) ?: findInMemory(path)

        val base = project.basePath ?: return fs.findFileByPath(path) ?: findInMemory(path)
        val target = File(base, path).normalize().path
        return fs.findFileByPath(target) ?: refreshAndFind(target) ?: findInMemory(target)
    }

    private fun refreshAndFind(path: String): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        if (ApplicationManager.getApplication().isReadAccessAllowed) {
            return fs.findFileByPath(path)
        }
        return fs.refreshAndFindFileByPath(path)
    }

    private fun findInMemory(path: String): VirtualFile? =
        VirtualFileManager.getInstance().findFileByUrl("temp://$path")

    fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || path.startsWith("\\") || Regex("^[A-Za-z]:[\\\\/].*").matches(path)

    fun relative(project: Project, path: String): String {
        val base = project.basePath ?: return path
        return path.removePrefix("$base/")
    }
}
