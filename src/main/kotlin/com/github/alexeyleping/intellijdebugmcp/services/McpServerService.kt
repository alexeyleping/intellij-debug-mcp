package com.github.alexeyleping.intellijdebugmcp.services

import com.intellij.openapi.project.Project

interface McpServerService {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun getPort(): Int
}
