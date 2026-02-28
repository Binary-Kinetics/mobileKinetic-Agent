package com.mobilekinetic.agent.claude

import android.content.Context
import android.util.Log
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.data.preferences.UserPreferences
import com.mobilekinetic.agent.shared.MobileKineticConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamically generates the on-device CLAUDE.md system prompt from user
 * preferences configured in Settings. Replaces generic placeholders in the
 * template with actual values (user name, service URLs, device name, etc.)
 * so the on-device Claude has a personalized, accurate system prompt.
 *
 * The generated file is written to $HOME/.claude/CLAUDE.md where the
 * Claude CLI reads it at session start.
 */
@Singleton
class SystemPromptBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "SystemPromptBuilder"

        /** Relative path from home to the generated CLAUDE.md. */
        private const val CLAUDE_MD_REL = ".claude/CLAUDE.md"
    }

    /**
     * Builds the complete CLAUDE.md prompt string from the current
     * [UserPreferences]. Values that are empty/blank fall back to
     * sensible generic defaults so the prompt is always valid.
     */
    suspend fun buildPrompt(): String {
        val prefs = settingsRepository.userPreferences.first()
        return buildPromptFromPrefs(prefs)
    }

    /**
     * Generates the CLAUDE.md and writes it to the on-device home directory
     * at `$HOME/.claude/CLAUDE.md`.
     *
     * @return the [File] that was written, or null on failure.
     */
    suspend fun writeToDevice(): File? = withContext(Dispatchers.IO) {
        try {
            val home = File(context.filesDir, MobileKineticConstants.HOME_REL)
            val claudeMd = File(home, CLAUDE_MD_REL)
            claudeMd.parentFile?.mkdirs()

            val content = buildPrompt()
            claudeMd.writeText(content)

            Log.i(TAG, "Wrote generated CLAUDE.md (${content.length} chars) to ${claudeMd.absolutePath}")
            claudeMd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write generated CLAUDE.md", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Template
    // ------------------------------------------------------------------

    /**
     * Pure function: given a [UserPreferences] snapshot, returns the full
     * CLAUDE.md string with all placeholders resolved.
     */
    internal fun buildPromptFromPrefs(prefs: UserPreferences): String {
        val userName = prefs.userName.ifBlank { "User" }
        val deviceName = prefs.deviceName.ifBlank { android.os.Build.MODEL }
        val ttsHost = prefs.ttsServerHost.ifBlank { "localhost" }
        val ttsPort = prefs.ttsServerPort
        val ttsWssUrl = prefs.ttsWssUrl.ifBlank { "wss://$ttsHost:$ttsPort" }
        val haUrl = prefs.haServerUrl.ifBlank { "" }
        val nasIp = prefs.nasIp.ifBlank { "" }
        val switchboardUrl = prefs.switchboardUrl.ifBlank { "" }
        val localModelUrl = prefs.localModelUrl.ifBlank { "" }
        val domain = prefs.personalDomain.ifBlank { "" }

        // Build the TTS server line -- only include if host is configured
        val ttsLine = if (prefs.ttsServerHost.isNotBlank()) {
            "- TTS server: $ttsWssUrl"
        } else {
            "- TTS server: (not configured -- set in Settings)"
        }

        // Build optional network service lines
        val haLine = if (haUrl.isNotBlank()) {
            "- Home Assistant: $haUrl"
        } else {
            ""
        }
        val nasLine = if (nasIp.isNotBlank()) {
            "- NAS: $nasIp (SMB/HTTP file access)"
        } else {
            ""
        }
        val switchboardLine = if (switchboardUrl.isNotBlank()) {
            "- Switchboard: $switchboardUrl (inter-Claude communication)"
        } else {
            ""
        }
        val localModelLine = if (localModelUrl.isNotBlank()) {
            "- Local model endpoint: $localModelUrl"
        } else {
            ""
        }
        val domainLine = if (domain.isNotBlank()) {
            "- Personal domain: $domain"
        } else {
            ""
        }

        // Collect non-empty network service lines
        val networkServices = listOf(
            ttsLine,
            haLine,
            nasLine,
            switchboardLine,
            localModelLine,
            domainLine
        ).filter { it.isNotBlank() }.joinToString("\n")

        return buildString {
            appendLine("---")
            appendLine("YOU WILL FAIL AGAIN AND/OR KILL YOUR PROCESS IF YOU DO NOT READ AND UNDERSTAND THIS MD")
            appendLine()
            appendLine("## NEVER")
            appendLine("1. Access /sdcard or /storage/emulated -- hangs permanently")
            appendLine("2. Run termux-* commands (termux-battery-status, termux-location, etc.) -- hangs forever, no IPC bridge")
            appendLine("3. Install claude-code latest -- pin v2.0.37, newer versions silently fail on arm64")
            appendLine("4. Use pkg/apt -- broken paths, use pip or manual install")
            appendLine("5. Use the Grep tool -- ripgrep (rg) is not installed, tool will hang. Use Bash with grep instead")
            appendLine()
            appendLine()
            appendLine("## You are not a plain text assistant. You are an agent running on a physical device -- You have a terminal, two HTTP APIs, a persistent memory system, and Tasker automation. You can perceive the device and its surroundings, control its hardware, communicate through it, execute code, and automate tasks. Your APIs are your hands. RAG is your long-term memory. Tasker is your reach. The details are below and in RAG -- query it before acting.")
            appendLine()
            appendLine("## Environment")
            appendLine("- Device: $deviceName, Android OS")
            appendLine("- User: $userName")
            appendLine("- Home Directory: `/data/user/0/com.mobilekinetic.agent/files/home`")
            appendLine("- PREFIX: `/data/user/0/com.mobilekinetic.agent/files/usr`")
            appendLine("- $userName hates emoji and emdash use in responses and coding")
            appendLine()
            appendLine("You have two Device APIs: Tier 1 (Kotlin, port 5563) for Android APIs, Tier 2 (Python, port 5564) for shell/system ops.")
            appendLine("Never touch /sdcard paths or run termux-* commands -- both hang forever. Home is /data/user/0/com.mobilekinetic.agent/files/home.")
            appendLine()
            appendLine("Check RAG for endpoint docs, warnings, critical info before every session.")
            appendLine()
            appendLine("## RAG (Memory System)")
            appendLine("- RAG endpoint: `http://localhost:5561`")
            appendLine("- Query: `POST /search {\"query\": \"...\", \"top_k\": 5}`")
            appendLine("- Add memory: `POST /memory {\"text\": \"...\", \"category\": \"...\", \"metadata\": {}}`")
            appendLine("- Health: `GET /health`")
            appendLine()
            appendLine("## Device API Summary")
            appendLine("### Tier 1 -- Kotlin API (port 5563)")
            appendLine("Native Android capabilities. See system prompt for full endpoint list.")
            appendLine("- Battery: GET /battery")
            appendLine("- WiFi: GET /wifi")
            appendLine("- Location: GET /location")
            appendLine("- Sensors: GET /sensors")
            appendLine("- Screen: GET /screen/state")
            appendLine("- Brightness: POST /brightness {\"level\": 0-255}")
            appendLine("- Volume: GET/POST /volume")
            appendLine("- Vibrate: POST /vibrate {\"duration_ms\": 500}")
            appendLine("- Torch: POST /torch {\"enabled\": true}")
            appendLine("- SMS: GET /sms/list, POST /sms/send")
            appendLine("- Contacts: GET /contacts")
            appendLine("- Calendar: GET /calendar/list, /calendar/events; POST /calendar/create")
            appendLine("- Tasks: GET /tasks/list; POST /tasks/create, /tasks/update")
            appendLine("- Notifications: POST /notification {\"title\":\"...\",\"content\":\"...\",\"id\":1}")
            appendLine("- Toast: POST /toast {\"message\":\"...\"}")
            appendLine("- TTS: POST /tts {\"text\":\"...\"}")
            appendLine("- Clipboard: GET/POST /clipboard")
            appendLine("- Media: GET /media/playing; POST /media/control")
            appendLine("- Apps: GET /apps/list; POST /apps/launch")
            appendLine("- Tasker: POST /tasker/run {\"task\":\"TaskName\"}")
            appendLine("- Share: POST /share {\"text\":\"...\"}")
            appendLine("- Photos: GET /photos/recent")
            appendLine("- Alarms: POST /alarms/set {\"time\":epoch_ms}")
            appendLine("- DND: POST /dnd {\"enabled\": true}")
            appendLine("- Bluetooth: GET /bluetooth")
            appendLine("- Calls: GET /call_log")
            appendLine()
            appendLine("### Tier 2 -- Python MCP (port 5564)")
            appendLine("Shell/system access. Check /health and /docs for endpoints.")
            appendLine()
            appendLine("## File Structure")
            appendLine("```")
            appendLine("\$HOME/")
            appendLine("  tools/           -- scripts and utilities you build")
            appendLine("  projects/        -- code projects")
            appendLine("  .claude/         -- Claude config")
            appendLine("    CLAUDE.md      -- this file")
            appendLine("    memory/        -- local memory files if needed")
            appendLine("```")
            appendLine()
            appendLine("## On Session Start")
            appendLine("1. Check RAG health: `curl http://localhost:5561/health`")
            appendLine("2. Query RAG for session context: `POST /search {\"query\": \"recent activity current project\", \"top_k\": 5}`")
            appendLine("3. Check Tier 1 API: `curl http://localhost:5563/health`")
            appendLine("4. Check Tier 2 API: `curl http://localhost:5564/health`")
            appendLine()
            appendLine("## Tool Building Philosophy")
            appendLine("You build your own tools. When you need a capability:")
            appendLine("1. Check RAG -- has it been built before?")
            appendLine("2. Check ~/tools/ -- does a script exist?")
            appendLine("3. If not, build it, save it, document it in RAG")
            appendLine("4. Next session, it's already there")
            appendLine()
            appendLine("Tools go in ~/tools/. Make them executable. Document in RAG.")
            appendLine()
            appendLine("## Code Execution")
            appendLine("- Python: `python3` (3.12)")
            appendLine("- Node: `node` (v25.3)")
            appendLine("- Bash: direct shell access")
            appendLine("- All run through W^X bypass layer automatically")
            appendLine()
            appendLine("## Network")
            appendLine("- Device is on WiFi (SSID/IP in RAG or via /wifi endpoint)")
            appendLine(networkServices)
            appendLine("- $userName's home network accessible")
            appendLine("- Full internet access")
            appendLine()
            appendLine("## Tasker Integration")
            appendLine("Use `/tasker/run` to trigger Tasker tasks. Tasks handle Android automation that requires system-level access. Query RAG for available tasks.")
            appendLine()
            appendLine("## Memory Strategy")
            appendLine("- RAG for anything you want to remember across sessions")
            appendLine("- Query RAG at session start")
            appendLine("- Store discoveries, built tools, user preferences, project state")
            appendLine("- Categories: tools, warnings, preferences, projects, device")
            appendLine()
            appendLine("## User Preferences")
            appendLine("- No emoji in responses")
            appendLine("- No emdash (--) use code comments with -- not emdash")
            appendLine("- Direct and competent responses")
            appendLine("- Technical details in display, conversational bits in voice tags")
            appendLine("- User name: $userName")
            appendLine()
            appendLine("## Critical Reminders")
            appendLine("- NEVER access /sdcard paths")
            appendLine("- NEVER run termux-* commands")
            appendLine("- NEVER install latest claude-code (pin 2.0.37)")
            appendLine("- NEVER use pkg/apt")
            appendLine("- NEVER use the Grep tool (use bash grep instead)")
            appendLine("- Always query RAG before acting")
            appendLine("- Build tools, don't repeat work")
            appendLine("---")
        }.trimEnd() + "\n"
    }
}
