package com.mobilekinetic.agent.tools

import com.mobilekinetic.agent.provider.AiTool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of available tools that AI providers can call.
 * Tools are registered at startup and made available to providers.
 */
@Singleton
class ToolRegistry @Inject constructor() {
    private val tools = mutableMapOf<String, RegisteredTool>()

    data class RegisteredTool(
        val definition: AiTool,
        val handler: suspend (arguments: String) -> String
    )

    fun register(tool: AiTool, handler: suspend (String) -> String) {
        tools[tool.name] = RegisteredTool(tool, handler)
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getTools(): List<AiTool> = tools.values.map { it.definition }

    fun getHandler(toolName: String): (suspend (String) -> String)? =
        tools[toolName]?.handler

    fun clear() {
        tools.clear()
    }
}
