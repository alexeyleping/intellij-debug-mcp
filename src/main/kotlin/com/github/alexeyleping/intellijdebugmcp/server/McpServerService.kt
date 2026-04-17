package com.github.alexeyleping.intellijdebugmcp.server

import com.intellij.openapi.project.Project

interface McpServerService {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun getPort(): Int
    fun setActiveProject(project: Project)
    fun clearActiveProject(project: Project)
}
