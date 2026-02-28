package com.mobilekinetic.agent.provider

/**
 * Definition of a tool that an AI provider can call.
 */
data class AiTool(
    val name: String,
    val description: String,
    val inputSchema: String  // JSON Schema string
)

/**
 * Field type for provider configuration forms.
 */
enum class FieldType {
    TEXT, PASSWORD, URL, NUMBER, SELECT
}

/**
 * Describes a configuration field for a provider.
 * Used by the settings UI to render dynamic config forms.
 */
data class ProviderConfigField(
    val key: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val defaultValue: String = "",
    val placeholder: String = "",
    val options: List<String> = emptyList()  // For SELECT type
)
