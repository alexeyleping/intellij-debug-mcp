package com.github.alexeyleping.intellijdebugmcp.listeners

import com.github.alexeyleping.intellijdebugmcp.server.McpServerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class ProjectOpenListener : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        project.getService(McpServerService::class.java)?.start()
    }

    override fun projectClosing(project: Project) {
        project.getService(McpServerService::class.java)?.stop()
    }
}
