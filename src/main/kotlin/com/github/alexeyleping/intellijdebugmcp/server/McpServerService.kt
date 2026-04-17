package com.github.alexeyleping.intellijdebugmcp.server

interface McpServerService {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun getPort(): Int
}
