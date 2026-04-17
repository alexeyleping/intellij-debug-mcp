package com.github.alexeyleping.intellijdebugmcp.listeners

import com.github.alexeyleping.intellijdebugmcp.server.McpServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class ProjectOpenListener : ProjectManagerListener {

    private fun service(): McpServerService =
        ApplicationManager.getApplication().getService(McpServerService::class.java)

    override fun projectOpened(project: Project) {
        val svc = service()
        svc.start()
        svc.setActiveProject(project)
    }

    override fun projectClosing(project: Project) {
        service().clearActiveProject(project)
    }
}
