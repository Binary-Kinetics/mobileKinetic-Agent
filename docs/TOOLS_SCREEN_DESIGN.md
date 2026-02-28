# mK:a Tools Screen - Comprehensive Design Document

**Created**: 2026-02-25
**Author**: Claude (VS Code session)
**Status**: Design Phase - Ready for Implementation

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Data Model](#2-data-model)
3. [Data Sources](#3-data-sources)
4. [Auto-Registration](#4-auto-registration)
5. [UI Architecture](#5-ui-architecture)
6. [AI Info Feature](#6-ai-info-feature)
7. [Status Tracking](#7-status-tracking)
8. [Quick Actions](#8-quick-actions)
9. [Navigation Integration](#9-navigation-integration)
10. [Performance](#10-performance)
11. [File List](#11-file-list)

---

## 1. Executive Summary

### Current State

A `ToolsScreen.kt` already exists and is wired into the bottom navigation (Chat | Terminal | **Tools** | Settings). However, it is a **v1 skeleton** with limited functionality:

- Simple search via RAG semantic lookup (no local filtering)
- Basic card layout showing name, description, approval status, use count
- No filtering/sorting, no categories, no status indicators, no info button, no quick actions
- Data comes exclusively from Room `tools` table via `ToolMemory`

### Scale of the Tool Ecosystem

| Source | Tool Count | Notes |
|--------|-----------|-------|
| DeviceApiServer.kt (Ktor routes) | ~1,587 route registrations | Flat `routing {}` block, no self-describing endpoint |
| unified_device_mcp.py (`@_register`) | ~249 MCP tool definitions | Has `self._tools` dict, `list_tools()` handler |
| rag_seed.json (tool entries) | ~119 tool_name entries | Pipe-delimited format with params |
| toolDescriptions.json (Tasker) | 23 Tasker tool descriptions | MCP-schema format with inputSchema |
| Room `tools` table | Dynamic (runtime) | Tools registered by Claude during usage |

**Combined potential**: 200-400+ unique tools across all sources.

### Design Goal

Replace the v1 skeleton with a production-quality tool catalog that:
- Unifies all tool sources into a single browsable/searchable list
- Handles 400+ entries performantly
- Supports filtering by category, type, source, and status
- Provides AI-powered explanations via the "i" button
- Enables direct invocation of simple tools from the card
- Auto-discovers new tools as they are created

---

## 2. Data Model

### 2.1 Unified Tool Model (ToolEntry)

A new data class that serves as the UI model, unifying tools from all sources:

```kotlin
// File: data/tools/ToolEntry.kt

data class ToolEntry(
    val id: String,                          // Unique ID: "{source}:{name}" e.g. "mcp:device_sms_send"
    val name: String,                        // Human-readable name: "Send SMS"
    val technicalName: String,               // API name: "device_sms_send"
    val description: String,                 // What the tool does
    val category: ToolCategory,              // Enum category
    val type: ToolType,                      // Endpoint type (GET/POST/MCP/Tasker)
    val source: ToolSource,                  // Where this tool comes from
    val status: ToolStatus,                  // Working/broken/untested/offline
    val endpoint: String?,                   // HTTP path or MCP tool name
    val httpMethod: String?,                 // GET, POST, DELETE, etc.
    val inputSchema: String?,                // JSON Schema for parameters
    val hasRequiredParams: Boolean,          // Whether it needs params to invoke
    val isQuickActionCapable: Boolean,       // Can be invoked with no/minimal params
    val isUserApproved: Boolean,             // User has approved this tool
    val useCount: Int,                       // Times used
    val lastUsedAt: Long?,                   // Last usage timestamp
    val successRate: Float?,                 // Success % from usage history
    val icon: String?,                       // Emoji or icon identifier
    val tags: List<String>,                  // Additional searchable tags
)

enum class ToolCategory(val displayName: String, val icon: String) {
    COMMUNICATION("Communication", "phone"),     // SMS, MMS, call, contacts, share
    MEDIA("Media", "music_note"),                // TTS, music, photos, camera, audio
    DEVICE_CONTROL("Device Control", "tune"),     // Volume, brightness, torch, vibrate, DND
    SYSTEM("System", "settings"),                 // Battery, storage, sensors, screen, process
    NETWORK("Network", "wifi"),                   // WiFi, bluetooth, data usage, network info
    CALENDAR("Calendar", "calendar_today"),        // Calendar CRUD, alarms
    TASKS("Tasks", "task_alt"),                    // JTX Board tasks, Tasker tasks
    FILES("Files", "folder"),                      // File read/write/list, downloads, grep
    LOCATION("Location", "location_on"),           // GPS, GNSS
    HOME_AUTOMATION("Home", "home"),               // Home Assistant tools
    AI("AI / Analysis", "psychology"),             // RLM, RAG, Gemma
    SECURITY("Security", "lock"),                  // Vault, credentials, privacy
    SHELL("Shell", "terminal"),                    // Shell execute, command
    BROWSER("Browser", "language"),                // Browser open, prefetch
    TASKER("Tasker", "smart_toy"),                 // Tasker-specific integrations
    NOTIFICATION("Notification", "notifications"), // Notifications, toast, flash
    OTHER("Other", "extension"),                   // Uncategorized
}

enum class ToolType(val displayName: String) {
    KOTLIN_ENDPOINT("Kotlin API"),    // DeviceApiServer Ktor route
    MCP_TOOL("MCP Tool"),             // unified_device_mcp.py registered tool
    TASKER_TOOL("Tasker"),            // toolDescriptions.json Tasker integration
    CLAUDE_CREATED("Claude Tool"),    // Tools registered by Claude at runtime
    RAG_SEED("RAG Seed"),            // Seed data tools
}

enum class ToolSource(val displayName: String) {
    DEVICE_API("Device API"),          // DeviceApiServer.kt
    UNIFIED_MCP("Unified MCP"),        // unified_device_mcp.py
    TASKER("Tasker"),                   // toolDescriptions.json
    ROOM_DB("Runtime"),                 // Room tools table
    RAG_SEED("RAG Seed"),              // rag_seed.json
}

enum class ToolStatus(val displayName: String, val colorName: String) {
    ONLINE("Online", "green"),         // Confirmed working (recent success)
    OFFLINE("Offline", "red"),         // Confirmed broken (recent failure)
    DEGRADED("Degraded", "yellow"),    // Intermittent (mixed success/failure)
    UNTESTED("Untested", "gray"),      // Never invoked, status unknown
    UNAVAILABLE("Unavailable", "red"), // Source server not running
}
```

### 2.2 Extended Room Entity

The existing `ToolEntity` needs new columns:

```kotlin
// File: data/db/entity/ToolEntity.kt (MODIFIED)

@Entity(tableName = "tools")
data class ToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val executionType: String,
    val schemaJson: String,
    val isUserApproved: Boolean = false,
    val isBuiltIn: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // NEW COLUMNS:
    val category: String? = null,            // ToolCategory.name
    val type: String? = null,                // ToolType.name
    val source: String? = null,              // ToolSource.name
    val endpoint: String? = null,            // HTTP path or MCP name
    val httpMethod: String? = null,          // GET/POST/DELETE
    val hasRequiredParams: Boolean = true,   // Needs params?
    val tags: String? = null,                // Comma-separated tags
    val lastStatusCheck: Long? = null,       // When status was last verified
    val lastStatus: String? = null,          // ToolStatus.name
)
```

**Migration**: DB version 6 -> 7 with `ALTER TABLE tools ADD COLUMN ...` for each new column. Since `fallbackToDestructiveMigration(true)` is set, this could also be destructive (tools table is rebuild-able from sources).

---

## 3. Data Sources

### 3.1 Source: toolDescriptions.json (Tasker Tools)

**Location**: `app/src/main/assets/scripts/config/toolDescriptions.json`
**Count**: 23 tools
**Format**:
```json
{
    "tasker_name": "MCP Set Volume",
    "name": "tasker_set_volume",
    "description": "Sets the phone media volume level.",
    "inputSchema": {
        "type": "object",
        "properties": { "level": { "type": "number", ... } },
        "required": ["level"]
    }
}
```
**Mapping**:
- `id` = `"tasker:{name}"` (e.g., `"tasker:tasker_set_volume"`)
- `category` = Inferred from name (volume -> DEVICE_CONTROL, lamp -> HOME_AUTOMATION, etc.)
- `type` = `ToolType.TASKER_TOOL`
- `source` = `ToolSource.TASKER`
- `isQuickActionCapable` = `required` is empty or absent
- `hasRequiredParams` = `required` array is non-empty

### 3.2 Source: rag_seed.json (RAG Seed Tools)

**Location**: `app/src/main/assets/rag_seed.json`
**Count**: 119 tool entries (category = "tool")
**Format**:
```json
{
    "text": "[TOOL] | camera_photo | Snap a picture with device camera | camera_id:s? flash:s? quality:i? | file path to saved photo",
    "category": "tool",
    "metadata": { "domain": "TOOL", "tool_name": "camera_photo", "version": 1 }
}
```
**Pipe-delimited format**: `[TOOL] | {name} | {description} | {params} | {return_description}`
**Parameter notation**: `name:type?` = optional, `name:type!` = required, `(none)` = no params
**Mapping**:
- `id` = `"rag:{tool_name}"` (e.g., `"rag:camera_photo"`)
- Parse pipe-delimited text to extract name, description, params, return type
- `category` = Inferred from tool_name prefix (camera_ -> MEDIA, sms_ -> COMMUNICATION, etc.)
- `type` = `ToolType.RAG_SEED`
- `isQuickActionCapable` = all params are optional (`?` suffix) or `(none)`
- `hasRequiredParams` = any param has `!` suffix

### 3.3 Source: unified_device_mcp.py (MCP Tools)

**Location**: `app/src/main/assets/scripts/unified_device_mcp.py`
**Count**: ~249 tool registrations via `@_register()`
**Access**: MCP `tools/list` endpoint (when MCP server is running on-device)
**Format** (at registration):
```python
@_register("device_sms_send", "Send an SMS message", {
    "type": "object",
    "properties": { ... },
    "required": ["phone_number", "message"]
})
```
**Runtime catalog**: `self._tools` dict, exposed via `@server.list_tools()` as standard MCP `tools/list` JSON-RPC response.
**Mapping**:
- `id` = `"mcp:{tool_name}"` (e.g., `"mcp:device_sms_send"`)
- Category from prefix: `device_sms_` -> COMMUNICATION, `ha_` -> HOME_AUTOMATION, `rlm_` -> AI, etc.
- `type` = `ToolType.MCP_TOOL`
- `source` = `ToolSource.UNIFIED_MCP`
- Schema available from the `inputSchema` field

### 3.4 Source: DeviceApiServer.kt (Kotlin Endpoints)

**Location**: `device/api/DeviceApiServer.kt` (616KB, ~1587 route registrations)
**Count**: ~60-80 unique logical endpoints (many routes are variants/overloads)
**Format**: Ktor DSL `get("/path") { ... }` and `post("/path") { ... }` inside flat `routing { }` block
**No self-describing endpoint**: There is no `/tools` or `/list-tools` route.
**Category comments**: Section comments like `// ===== HARDWARE =====` exist but have no runtime effect.

**Strategy**: Since this server has no introspection endpoint, we must:
1. Build a static manifest at build-time (preferred) OR
2. Parse the Kotlin source for route registrations OR
3. Create a `/tools` endpoint that returns the registry

**Recommendation**: Add a `/tools` endpoint to DeviceApiServer.kt (see Section 4).

### 3.5 Source: Room `tools` Table (Runtime)

**Location**: `data/db/entity/ToolEntity` + `ToolDao`
**Count**: Dynamic (grows as Claude uses/creates tools)
**Format**: Already defined as `ToolEntity` data class
**Mapping**: Direct 1:1 mapping to `ToolEntry` UI model

### 3.6 Deduplication Strategy

Tools can appear in multiple sources (e.g., `device_sms_send` exists in both MCP and RAG seed). Dedup by:

1. **Priority order**: Room DB > MCP (live) > DeviceApiServer > Tasker > RAG Seed
2. **Matching key**: `technicalName` (normalized: lowercase, strip source prefix)
3. **Merge strategy**: Higher-priority source wins for conflicting fields; merge non-conflicting data (e.g., usage stats from Room, schema from MCP)

```kotlin
// Pseudocode for merge
fun mergeTools(sources: List<List<ToolEntry>>): List<ToolEntry> {
    val merged = LinkedHashMap<String, ToolEntry>() // keyed by technicalName
    for (source in sources) { // ordered by priority
        for (tool in source) {
            val key = tool.technicalName.lowercase()
            merged[key] = merged[key]?.mergeWith(tool) ?: tool
        }
    }
    return merged.values.toList()
}
```

---

## 4. Auto-Registration

### 4.1 Adding a `/tools` Endpoint to DeviceApiServer.kt

Since DeviceApiServer has no self-describing endpoint, we add one. This is the **single most important change** for the tools screen.

```kotlin
// Add inside the routing {} block of DeviceApiServer.kt

get("/tools") {
    val tools = listOf(
        mapOf(
            "name" to "battery_status",
            "endpoint" to "/battery",
            "method" to "GET",
            "description" to "Get battery level and charging state",
            "category" to "system",
            "params" to emptyMap<String, Any>()
        ),
        mapOf(
            "name" to "sms_send",
            "endpoint" to "/sms/send",
            "method" to "POST",
            "description" to "Send an SMS message",
            "category" to "communication",
            "params" to mapOf(
                "phone_number" to mapOf("type" to "string", "required" to true),
                "message" to mapOf("type" to "string", "required" to true)
            )
        ),
        // ... all other endpoints
    )
    call.respond(HttpStatusCode.OK, mapOf("tools" to tools, "count" to tools.size))
}
```

**Alternative (lower effort)**: Generate the tool manifest as a JSON asset at build time by parsing the Kotlin source. A Gradle task could extract route patterns from DeviceApiServer.kt.

### 4.2 MCP Tool Discovery (Runtime)

When the MCP server is running on-device, the app can query `tools/list` via the MCP protocol (stdio transport). The `UnifiedDeviceMCP.setup_tools()` already builds `self._tools`, and `@server.list_tools()` returns the full catalog.

**Implementation**:
```kotlin
// In ToolCatalogRepository.kt
suspend fun fetchMcpTools(): List<ToolEntry> {
    // Option A: HTTP endpoint if MCP exposes one
    // Option B: Read unified_device_mcp.py from assets, parse @_register lines
    // Option C: MCP stdio protocol query
}
```

**Recommended approach**: Parse the Python source from assets at startup (most reliable, no dependency on MCP server running).

### 4.3 Tasker Tool Discovery

Read `toolDescriptions.json` from assets at startup:

```kotlin
suspend fun fetchTaskerTools(context: Context): List<ToolEntry> {
    val json = context.assets.open("scripts/config/toolDescriptions.json")
        .bufferedReader().readText()
    val tools = Gson().fromJson(json, Array<TaskerToolDescription>::class.java)
    return tools.map { it.toToolEntry() }
}
```

### 4.4 RAG Seed Tool Discovery

Parse `rag_seed.json` and filter entries with `category == "tool"`:

```kotlin
suspend fun fetchRagSeedTools(context: Context): List<ToolEntry> {
    val json = context.assets.open("rag_seed.json").bufferedReader().readText()
    val seed = Gson().fromJson(json, RagSeedData::class.java)
    return seed.entries
        .filter { it.category == "tool" }
        .map { parseRagToolEntry(it) }
}

fun parseRagToolEntry(entry: RagSeedEntry): ToolEntry {
    // Parse pipe-delimited: "[TOOL] | name | description | params | return"
    val parts = entry.text.split("|").map { it.trim() }
    val name = parts.getOrNull(1) ?: "unknown"
    val description = parts.getOrNull(2) ?: ""
    val params = parts.getOrNull(3) ?: "(none)"
    val hasRequired = params.contains("!")
    // ...
}
```

### 4.5 Auto-Refresh Strategy

```
App Start
  |-> Load cached tools from Room (instant display)
  |-> Background: parse assets (toolDescriptions.json, rag_seed.json, unified_device_mcp.py)
  |-> Background: query DeviceApiServer /tools endpoint (if running)
  |-> Merge all sources, update Room cache
  |-> UI updates via Flow

Tool Registration Event (Claude creates a new tool)
  |-> ToolMemory.registerTool() already handles this
  |-> allTools Flow emits updated list
  |-> UI auto-updates

New MCP Build Deployed
  |-> On next app start, re-parse unified_device_mcp.py from assets
  |-> New tools auto-appear
```

---

## 5. UI Architecture

### 5.1 Screen Layout

```
+--------------------------------------------------+
| [<] TOOLS                              [Refresh]  |  TopAppBar
+--------------------------------------------------+
| [Search tools...                          ] [X]   |  Search bar
+--------------------------------------------------+
| [All] [Category v] [Type v] [Status v] [A-Z]     |  Filter row
+--------------------------------------------------+
|                                                    |
| COMMUNICATION (23)           [expand/collapse]     |  Category header (when grouped)
| +----------------------------------------------+  |
| | icon  Send SMS                    [*] [>] [i] |  |  Tool card
| |       device_sms_send  |  MCP Tool           |  |
| |       Online  |  Used 47x                    |  |
| +----------------------------------------------+  |
| +----------------------------------------------+  |
| | icon  List SMS                    [*] [>] [i] |  |
| |       device_sms_list  |  MCP Tool           |  |
| |       Online  |  Used 12x                    |  |
| +----------------------------------------------+  |
|                                                    |
| DEVICE CONTROL (31)                                |
| +----------------------------------------------+  |
| | icon  Set Volume                  [*] [>] [i] |  |
| ...                                                |
+--------------------------------------------------+
| [Chat] [Terminal] [*Tools*] [Settings] [Stop]     |  Bottom nav
+--------------------------------------------------+
```

### 5.2 Card Design (Compact)

Each tool card is designed for maximum scannability at ~72dp height:

```
+--------------------------------------------------+
| Row 1: [Icon] [Tool Name]          [Status] [i]  |
| Row 2: [tech_name] | [Type chip]   [Quick Act >] |
| Row 3: [Category chip] | Used Nx | Last: 2h ago  |
+--------------------------------------------------+
```

**Composable structure**:
```kotlin
@Composable
fun ToolCard(
    tool: ToolEntry,
    onInfoClick: (ToolEntry) -> Unit,
    onQuickAction: (ToolEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant // LcarsContainerGray
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Row 1: Icon + Name + Status dot + Info button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category icon (Material icon or emoji)
                Icon(/*...*/)
                Spacer(Modifier.width(8.dp))
                // Tool name
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = LcarsOrange,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Status dot
                StatusDot(tool.status)
                // Info button
                IconButton(
                    onClick = { onInfoClick(tool) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = LcarsBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Row 2: Technical name + Type chip + Quick action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.technicalName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LcarsTextSecondary,
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text(tool.type.displayName, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp)
                )
                if (tool.isQuickActionCapable) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onQuickAction(tool) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Run",
                            tint = LcarsGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Row 3: Category + usage stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = tool.category.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LcarsPurple
                )
                Text(
                    text = "Used ${tool.useCount}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = LcarsTextBody
                )
                tool.lastUsedAt?.let {
                    Text(
                        text = "Last: ${formatRelativeTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LcarsTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun StatusDot(status: ToolStatus) {
    val color = when (status) {
        ToolStatus.ONLINE -> LcarsGreen
        ToolStatus.OFFLINE -> LcarsRed
        ToolStatus.DEGRADED -> LcarsYellow
        ToolStatus.UNTESTED -> LcarsTextSecondary
        ToolStatus.UNAVAILABLE -> LcarsRed
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
```

### 5.3 Filter/Sort Implementation

**Filter Bar** using `FilterChip` in a `LazyRow` (matches existing patterns from MemoryInjectionSettingsScreen):

```kotlin
enum class ToolSortMode(val displayName: String) {
    DEFAULT("All"),          // No grouping, sorted by useCount desc
    ALPHABETICAL("A-Z"),     // Sorted by name
    CATEGORY("Category"),    // Grouped by ToolCategory
    TYPE("Type"),            // Grouped by ToolType
    STATUS("Status"),        // Grouped by ToolStatus
    RECENT("Recent"),        // Sorted by lastUsedAt desc
}
```

**Category filter dropdown**: When "Category" is selected, show a secondary LazyRow of category chips:
```
[All] [Communication] [Media] [Device Control] [System] [Network] ...
```

**Implementation** in ViewModel:
```kotlin
private val _sortMode = MutableStateFlow(ToolSortMode.DEFAULT)
private val _categoryFilter = MutableStateFlow<ToolCategory?>(null)
private val _statusFilter = MutableStateFlow<ToolStatus?>(null)

val displayTools: StateFlow<List<ToolDisplayItem>> = combine(
    allUnifiedTools,
    _searchQuery,
    _sortMode,
    _categoryFilter,
    _statusFilter
) { tools, query, sort, category, status ->
    tools
        .filter { matchesSearch(it, query) }
        .filter { category == null || it.category == category }
        .filter { status == null || it.status == status }
        .let { sortTools(it, sort) }
        .let { groupTools(it, sort) }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Where `ToolDisplayItem` is a sealed class:
```kotlin
sealed class ToolDisplayItem {
    data class Header(val title: String, val count: Int) : ToolDisplayItem()
    data class Tool(val entry: ToolEntry) : ToolDisplayItem()
}
```

### 5.4 Search Implementation

Dual-mode search for maximum responsiveness:

1. **Local filter** (instant): Substring match on name, technicalName, description, category, tags
2. **RAG semantic search** (debounced 300ms): Uses existing `ToolMemory.findTools()` for semantic matching

```kotlin
fun searchTools(query: String) {
    _searchQuery.value = query

    // Cancel previous debounced search
    searchJob?.cancel()

    if (query.length >= 2) {
        searchJob = viewModelScope.launch {
            delay(300) // Debounce
            val ragResults = toolMemory.findTools(query, topK = 20)
            _ragSearchResults.value = ragResults.map { it.id }.toSet()
        }
    }
}

private fun matchesSearch(tool: ToolEntry, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase()
    return tool.name.lowercase().contains(q) ||
           tool.technicalName.lowercase().contains(q) ||
           tool.description.lowercase().contains(q) ||
           tool.category.displayName.lowercase().contains(q) ||
           tool.tags.any { it.lowercase().contains(q) } ||
           _ragSearchResults.value.contains(tool.id)  // Include RAG matches
}
```

---

## 6. AI Info Feature

### 6.1 "i" Button Behavior

Tapping the info (i) icon on any tool card opens a **ModalBottomSheet** with AI-generated explanation.

```
+--------------------------------------------------+
| [drag handle]                                      |
+--------------------------------------------------+
|                                                    |
|  SEND SMS                                          |  Tool name
|  device_sms_send                                   |  Technical name
|                                                    |
|  --- AI Explanation ---                            |
|  This tool sends an SMS text message from your     |
|  Pixel Fold to any phone number. It uses the       |
|  Android SmsManager API via the Device API         |
|  Server (port 5563).                               |
|                                                    |
|  Parameters:                                       |
|  - phone_number (required): Recipient number       |
|  - message (required): Text content                |
|                                                    |
|  Category: Communication                           |
|  Type: MCP Tool                                    |
|  Endpoint: POST /sms/send                          |
|  Success Rate: 94% (47 uses)                       |
|                                                    |
|  [Run Tool]  [View History]  [Approve/Revoke]      |
+--------------------------------------------------+
```

### 6.2 AI Explanation Generation

The info sheet sends tool metadata to Claude (via the existing chat pipeline) to generate an explanation:

```kotlin
suspend fun generateToolExplanation(tool: ToolEntry): String {
    val prompt = buildString {
        append("Explain this tool in 2-3 sentences for a non-technical user:\n\n")
        append("Name: ${tool.name}\n")
        append("Technical name: ${tool.technicalName}\n")
        append("Description: ${tool.description}\n")
        append("Category: ${tool.category.displayName}\n")
        append("Type: ${tool.type.displayName}\n")
        tool.endpoint?.let { append("Endpoint: ${tool.httpMethod ?: "POST"} $it\n") }
        tool.inputSchema?.let { append("Parameters: $it\n") }
        append("\nExplain what this tool does, when it would be used, and any important notes.")
    }

    // Use existing Claude conversation infrastructure
    // Could be a lightweight call to on-device Gemma for speed
    return claudeService.generateQuickResponse(prompt)
}
```

**Caching**: Store generated explanations in Room (new column `aiExplanation: String?` on ToolEntity, or a separate `tool_explanations` table) to avoid re-generating.

### 6.3 Fallback for Offline

If Claude/Gemma is unavailable, show raw metadata in a structured format:
- Description from the tool definition
- Parameter list from inputSchema
- Usage statistics from Room

---

## 7. Status Tracking

### 7.1 Status Determination

Status is derived from multiple signals:

```kotlin
fun determineStatus(tool: ToolEntry): ToolStatus {
    // 1. Check if source server is reachable
    if (!isSourceAvailable(tool.source)) return ToolStatus.UNAVAILABLE

    // 2. Check recent usage history
    val recentUsages = toolDao.getUsageHistory(tool.id, limit = 5)
    if (recentUsages.isEmpty()) return ToolStatus.UNTESTED

    val recentSuccessRate = recentUsages.count { it.isSuccess } / recentUsages.size.toFloat()
    return when {
        recentSuccessRate >= 0.8f -> ToolStatus.ONLINE
        recentSuccessRate >= 0.3f -> ToolStatus.DEGRADED
        else -> ToolStatus.OFFLINE
    }
}
```

### 7.2 Source Availability Check

```kotlin
suspend fun isSourceAvailable(source: ToolSource): Boolean {
    return try {
        when (source) {
            ToolSource.DEVICE_API -> {
                // Check DeviceApiServer health
                httpClient.get("http://localhost:5563/health").status.isSuccess()
            }
            ToolSource.UNIFIED_MCP -> {
                // MCP server is in-process, check if process is alive
                // Or: check if unified_device_mcp.py process PID file exists
                File(homeDir, ".mcp_server.pid").exists()
            }
            ToolSource.TASKER -> {
                // Tasker is always "available" if installed
                packageManager.getPackageInfo("net.dinglisch.android.taskerm", 0) != null
            }
            else -> true
        }
    } catch (e: Exception) { false }
}
```

### 7.3 Background Status Refresh

A periodic job (every 5 minutes when Tools screen is visible) pings sources and updates status:

```kotlin
// In ToolsViewModel
private fun startStatusRefresh() {
    viewModelScope.launch {
        while (isActive) {
            catalogRepository.refreshToolStatuses()
            delay(5.minutes)
        }
    }
}
```

### 7.4 Integration with ToolUsageEntity

The existing `tool_usage` table already tracks `isSuccess` and `errorMessage`. The status system reads from this table:

- `getSuccessCount(toolId)` and `getFailureCount(toolId)` already exist in `ToolDao`
- `getUsageHistory(toolId, limit = 5)` gives recent trend
- Success rate = `successCount / (successCount + failureCount)`

---

## 8. Quick Actions

### 8.1 Quick Action Eligibility

A tool is quick-action-capable if:
1. It has NO required parameters (e.g., `device_battery_info`, `device_clipboard_get`)
2. OR it only requires simple parameters that can be filled from a single text field

```kotlin
val isQuickActionCapable: Boolean
    get() {
        if (inputSchema == null) return true
        val schema = Gson().fromJson(inputSchema, JsonObject::class.java)
        val required = schema.getAsJsonArray("required")
        return required == null || required.size() == 0
    }
```

### 8.2 No-Param Quick Action (Tap to Run)

For tools with no required params (battery status, clipboard get, location, etc.):

```kotlin
suspend fun executeQuickAction(tool: ToolEntry): QuickActionResult {
    return when (tool.source) {
        ToolSource.DEVICE_API -> {
            val response = httpClient.request("http://localhost:5563${tool.endpoint}") {
                method = HttpMethod.parse(tool.httpMethod ?: "GET")
            }
            QuickActionResult.Success(response.bodyAsText())
        }
        ToolSource.UNIFIED_MCP -> {
            // Invoke MCP tool with empty params
            mcpClient.callTool(tool.technicalName, emptyMap())
        }
        ToolSource.TASKER -> {
            // Send Tasker intent
            val intent = Intent("net.dinglisch.android.taskerm.ACTION_TASK")
            intent.putExtra("task_name", tool.technicalName.removePrefix("tasker_"))
            context.sendBroadcast(intent)
            QuickActionResult.Success("Tasker task triggered")
        }
        else -> QuickActionResult.Error("Cannot execute this tool type")
    }
}
```

**Result display**: Show in a Snackbar or Toast:
```
[check] Battery: 87%, charging
```

### 8.3 Parameterized Quick Action (Dialog)

For tools with parameters, show a dialog with auto-generated form fields:

```kotlin
@Composable
fun QuickActionDialog(
    tool: ToolEntry,
    onDismiss: () -> Unit,
    onExecute: (Map<String, Any>) -> Unit
) {
    val schema = remember { parseSchema(tool.inputSchema) }
    val params = remember { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run: ${tool.name}", color = LcarsOrange) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                schema.properties.forEach { (name, prop) ->
                    OutlinedTextField(
                        value = params[name] ?: "",
                        onValueChange = { params[name] = it },
                        label = { Text(name + if (schema.required.contains(name)) " *" else "") },
                        placeholder = { Text(prop.description ?: "") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExecute(params.toMap()) }) {
                Text("Execute", color = LcarsGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
```

---

## 9. Navigation Integration

### 9.1 Current State (Already Wired)

The Tools screen is **already fully integrated** into navigation:

**Destination.kt** (line 54-60):
```kotlin
Tools(
    selectedIcon = Icons.Filled.Build,
    unselectedIcon = Icons.Outlined.Build,
    label = "Tools",
    contentDescription = "Tool management",
    route = Route.Tools
)
```

**MobileKineticApp.kt** (line 111):
```kotlin
Destination.Tools -> ToolsScreen()
```

### 9.2 No Navigation Changes Needed

The existing bottom nav (Chat | Terminal | Tools | Settings | Stop) already includes the Tools tab. The only change is **replacing the contents of ToolsScreen.kt** with the new implementation.

### 9.3 Sub-Navigation (Tool Detail)

If a tool detail screen is desired (for complex tools or usage history), add a sub-route:

```kotlin
// In Route sealed class (Destination.kt)
@Serializable data class ToolDetail(val toolId: String) : Route()
```

However, given the bottom sheet approach for the "i" button, a separate detail screen may not be needed initially.

---

## 10. Performance

### 10.1 LazyColumn Optimization for 400+ Items

```kotlin
LazyColumn(
    state = lazyListState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)  // Compact spacing
) {
    items(
        items = displayItems,
        key = { item ->
            when (item) {
                is ToolDisplayItem.Header -> "header_${item.title}"
                is ToolDisplayItem.Tool -> item.entry.id
            }
        },
        contentType = { item ->
            when (item) {
                is ToolDisplayItem.Header -> "header"
                is ToolDisplayItem.Tool -> "tool"
            }
        }
    ) { item ->
        when (item) {
            is ToolDisplayItem.Header -> CategoryHeader(item)
            is ToolDisplayItem.Tool -> ToolCard(item.entry, ...)
        }
    }
}
```

**Key optimizations**:
- `key = { ... }` - Stable keys for efficient recomposition (mandatory for 400+ items)
- `contentType = { ... }` - Enables LazyColumn to reuse composables of the same type
- Compact card height (~72dp) means ~8-9 cards visible on Pixel Fold without scrolling
- No nested `LazyColumn` - flat list with sticky headers

### 10.2 Search Debouncing

```kotlin
private var searchJob: Job? = null

fun onSearchQueryChanged(query: String) {
    _searchQuery.value = query // Instant local filter

    searchJob?.cancel()
    if (query.length >= 2) {
        searchJob = viewModelScope.launch {
            delay(300) // Debounce RAG search
            val ragHits = toolMemory.findTools(query, topK = 20)
            _ragSearchHitIds.value = ragHits.map { it.id }.toSet()
        }
    } else {
        _ragSearchHitIds.value = emptySet()
    }
}
```

### 10.3 Category Indexing

Pre-compute category groupings when the tool list changes:

```kotlin
private val _categoryIndex = MutableStateFlow<Map<ToolCategory, List<ToolEntry>>>(emptyMap())

init {
    viewModelScope.launch {
        allUnifiedTools.collect { tools ->
            _categoryIndex.value = tools.groupBy { it.category }
        }
    }
}
```

### 10.4 Initial Load Strategy

```
Frame 0: Show cached tools from Room (instant)
Frame 1-2: Parse asset files in background
Frame 3+: Merge, deduplicate, update UI
```

```kotlin
init {
    // Immediate: load from Room cache
    viewModelScope.launch {
        val cached = toolDao.getAllToolsCached() // suspend, not Flow
        _allUnifiedTools.value = cached.map { it.toToolEntry() }
    }

    // Background: refresh from all sources
    viewModelScope.launch(Dispatchers.IO) {
        val merged = catalogRepository.refreshFullCatalog()
        _allUnifiedTools.value = merged
    }
}
```

### 10.5 Memory Considerations

- 400 `ToolEntry` objects at ~500 bytes each = ~200KB (negligible)
- LazyColumn only composes visible items (~10 at a time)
- Avoid holding `inputSchema` JSON in memory for all tools; load on-demand for info/quick action
- Cache AI explanations in Room, not in-memory

---

## 11. File List

### New Files

| File | Description |
|------|-------------|
| `data/tools/ToolEntry.kt` | Unified tool data model, enums (ToolCategory, ToolType, ToolSource, ToolStatus) |
| `data/tools/ToolCatalogRepository.kt` | Aggregates tools from all sources, merges, deduplicates, caches in Room |
| `data/tools/ToolSourceParsers.kt` | Parsers for each source: Tasker JSON, RAG seed, MCP Python, DeviceApi manifest |
| `data/tools/ToolDisplayItem.kt` | Sealed class for LazyColumn items (Header, Tool) |
| `ui/screens/ToolsScreen.kt` | **REPLACE** existing v1 skeleton with full implementation |
| `ui/screens/components/ToolCard.kt` | Compact tool card composable |
| `ui/screens/components/StatusDot.kt` | Status indicator composable |
| `ui/screens/components/ToolFilterBar.kt` | Search bar + filter chips row |
| `ui/screens/components/ToolInfoSheet.kt` | Modal bottom sheet for AI tool explanation |
| `ui/screens/components/QuickActionDialog.kt` | Parameter input dialog for quick actions |

### Modified Files

| File | Changes |
|------|---------|
| `ui/viewmodel/ToolsViewModel.kt` | Complete rewrite: multi-source loading, filtering, sorting, search, quick actions, status |
| `data/db/entity/ToolEntity.kt` | Add new columns: category, type, source, endpoint, httpMethod, tags, lastStatus, etc. |
| `data/db/dao/ToolDao.kt` | Add queries: getAllToolsCached(), getToolsByCategory(), getToolsByStatus(), search by name/desc |
| `data/db/MobileKineticDatabase.kt` | Bump version 6 -> 7 (migration for new ToolEntity columns) |
| `data/rag/ToolMemory.kt` | Add methods: registerFromCatalog(), bulkUpsert() for catalog sync |
| `di/AppModule.kt` | Provide ToolCatalogRepository singleton |
| `device/api/DeviceApiServer.kt` | Add `GET /tools` self-describing endpoint |

### Unchanged Files (Reference Only)

| File | Why Referenced |
|------|----------------|
| `ui/navigation/Destination.kt` | Already has Tools route - no changes needed |
| `ui/MobileKineticApp.kt` | Already wires ToolsScreen() - no changes needed |
| `ui/theme/Color.kt` | LCARS colors used in cards (LcarsOrange, LcarsGreen, etc.) |
| `ui/theme/Theme.kt` | Theme setup already correct |
| `assets/scripts/config/toolDescriptions.json` | Read-only source (23 Tasker tools) |
| `assets/rag_seed.json` | Read-only source (119 tool entries) |
| `assets/scripts/unified_device_mcp.py` | Read-only source (249 MCP tools) |

---

## Appendix A: Category Inference Rules

Map tool technical names to categories by prefix:

```kotlin
fun inferCategory(technicalName: String): ToolCategory {
    val name = technicalName.lowercase()
    return when {
        // Communication
        name.contains("sms") || name.contains("mms") || name.contains("call") ||
        name.contains("contact") || name.contains("share") -> ToolCategory.COMMUNICATION

        // Media
        name.contains("camera") || name.contains("photo") || name.contains("video") ||
        name.contains("audio") || name.contains("music") || name.contains("tts") ||
        name.contains("media") || name.contains("record") -> ToolCategory.MEDIA

        // Device Control
        name.contains("volume") || name.contains("brightness") || name.contains("torch") ||
        name.contains("flashlight") || name.contains("vibrate") || name.contains("dnd") -> ToolCategory.DEVICE_CONTROL

        // System
        name.contains("battery") || name.contains("storage") || name.contains("sensor") ||
        name.contains("screen") || name.contains("process") || name.contains("system") ||
        name.contains("device_info") -> ToolCategory.SYSTEM

        // Network
        name.contains("wifi") || name.contains("bluetooth") || name.contains("network") ||
        name.contains("data_usage") || name.contains("gnss") -> ToolCategory.NETWORK

        // Calendar
        name.contains("calendar") || name.contains("alarm") -> ToolCategory.CALENDAR

        // Tasks
        name.contains("task") && !name.contains("tasker") -> ToolCategory.TASKS

        // Files
        name.contains("file") || name.contains("download") || name.contains("grep") ||
        name.contains("clipboard") -> ToolCategory.FILES

        // Location
        name.contains("location") || name.contains("gps") -> ToolCategory.LOCATION

        // Home Automation
        name.contains("ha_") || name.contains("lamp") || name.contains("home") -> ToolCategory.HOME_AUTOMATION

        // AI
        name.contains("rlm") || name.contains("rag") || name.contains("gemma") ||
        name.contains("embedding") || name.contains("analyze") -> ToolCategory.AI

        // Security
        name.contains("vault") || name.contains("credential") || name.contains("privacy") ||
        name.contains("encrypt") -> ToolCategory.SECURITY

        // Shell
        name.contains("shell") || name.contains("command") || name.contains("execute") -> ToolCategory.SHELL

        // Browser
        name.contains("browser") || name.contains("browse") || name.contains("url") -> ToolCategory.BROWSER

        // Tasker
        name.startsWith("tasker_") || name.contains("tasker") -> ToolCategory.TASKER

        // Notification
        name.contains("notification") || name.contains("toast") || name.contains("flash_text") -> ToolCategory.NOTIFICATION

        else -> ToolCategory.OTHER
    }
}
```

---

## Appendix B: Tool Count Projections

| Source | Current | Projected (6 months) |
|--------|---------|---------------------|
| DeviceApiServer Ktor routes | ~60-80 unique endpoints | ~100+ |
| unified_device_mcp.py | ~249 registered tools | ~300+ |
| toolDescriptions.json (Tasker) | 23 | 30-40 |
| rag_seed.json | 119 | 150+ |
| Runtime (Claude-created) | Variable | 50-100 |
| **Deduplicated total** | **~300-350** | **~400-500** |

The UI must handle 500+ items without lag, which the LazyColumn with keys/contentType approach achieves easily.

---

## Appendix C: Implementation Priority

| Phase | Scope | Effort |
|-------|-------|--------|
| **Phase 1** | Replace ToolsScreen with search + filter + compact cards using Room data only | 2-3 days |
| **Phase 2** | Add ToolCatalogRepository: parse all 4 asset sources, merge, cache in Room | 2-3 days |
| **Phase 3** | Add status tracking from usage history, status dots on cards | 1 day |
| **Phase 4** | Add "i" button with AI explanation bottom sheet | 1-2 days |
| **Phase 5** | Add quick actions (no-param execute, parameterized dialog) | 2 days |
| **Phase 6** | Add `/tools` endpoint to DeviceApiServer for live discovery | 1 day |
| **Phase 7** | Polish: animations, scroll-to-top FAB, empty states per filter, error handling | 1-2 days |

**Total estimated effort**: 10-14 days

---

*End of design document.*
