package com.mobilekinetic.agent.data.gemma

import com.mobilekinetic.agent.data.vault.CatalogMeta

object GemmaPrompts {

    fun privacyClassification(text: String): String = """
        |Classify the following text for privacy sensitivity.
        |Respond with exactly one word: PASS, REDACT, or BLOCK.
        |
        |PASS: No sensitive personal information.
        |REDACT: Contains PII that should be masked (names, addresses, SSN, financial info).
        |BLOCK: Contains highly sensitive data that should not be forwarded at all.
        |
        |Text: $text
        |
        |Classification:""".trimMargin()

    fun credentialGatekeeper(
        context: String,
        credentialName: String,
        meta: CatalogMeta? = null
    ): String {
        val metaBlock = if (meta != null && meta.contexts.isNotEmpty()) {
            """
            |
            |Credential purpose: ${meta.desc}
            |Intended service: ${meta.service}
            |Allowed contexts: ${meta.contexts.joinToString(", ")}
            |
            |ALLOW if the stated context matches or is closely related to the allowed contexts.
            |DENY if the context does not match any allowed context.""".trimMargin()
        } else {
            """
            |
            |ALLOW if the context is a legitimate tool or service that would need this credential.
            |DENY if the context seems suspicious, vague, or unrelated to the credential's purpose.""".trimMargin()
        }

        return """
            |You are a security gatekeeper. A process is requesting access to the credential "$credentialName".
            |The stated context is: "$context"
            |$metaBlock
            |
            |Respond with exactly one word: ALLOW or DENY.
            |
            |Decision:""".trimMargin()
    }

    fun sessionSummary(transcript: String): String = """
        |Summarize the following conversation into key points.
        |Focus on: decisions made, tasks completed, errors encountered, and user preferences learned.
        |Keep the summary under 500 words.
        |
        |Conversation:
        |$transcript
        |
        |Summary:""".trimMargin()

    fun toolClassification(userMessage: String, availableTools: List<String>): String = """
        |Given the user message and available tools, determine which tool (if any) should be used.
        |Respond with the tool name or "NONE" if no tool is needed.
        |
        |Available tools: ${availableTools.joinToString(", ")}
        |
        |User message: $userMessage
        |
        |Tool:""".trimMargin()
}
