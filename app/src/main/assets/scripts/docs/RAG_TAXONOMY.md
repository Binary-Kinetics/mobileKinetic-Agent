# mK:a RAG Taxonomy

## Purpose
Structured categorization system for efficient memory retrieval and organization. Prevents information overload and enables targeted searches.

## Category Structure

### 1. `system/*` - Core System Information
- **system/environment** - Paths, Android specifics, Termux setup
- **system/capabilities** - What mK:a can/cannot do
- **system/configuration** - Config files, settings, preferences

### 2. `infrastructure/*` - Services and Servers
- **infrastructure/mcp** - MCP servers, configurations, protocols
- **infrastructure/rag** - RAG system itself, indexing, maintenance
- **infrastructure/sensors** - Sensor endpoints, data streams
- **infrastructure/apis** - API endpoints, services, webhooks

### 3. `code/*` - Code Snippets and Patterns
- **code/python** - Python code examples, libraries, patterns
- **code/shell** - Shell script patterns, bash commands
- **code/android** - Android-specific code, Termux tricks

### 4. `knowledge/*` - Learned Information
- **knowledge/solutions** - Problem solutions and fixes
- **knowledge/procedures** - Step-by-step how-to guides
- **knowledge/troubleshooting** - Debug info, error resolution

### 5. `state/*` - Current State Tracking
- **state/active-projects** - Ongoing work, current tasks
- **state/errors** - Recent errors encountered
- **state/changes** - Recent modifications, what changed

### 6. `reference/*` - Quick Reference
- **reference/commands** - Command syntax, examples
- **reference/paths** - Important file paths, locations
- **reference/endpoints** - URLs, ports, service addresses

## Usage

### Adding Memory with Category
```bash
curl -X POST http://127.0.0.1:5562/memory \
  -H "Content-Type: application/json" \
  -d '{"text":"Your memory text","category":"system/environment"}'
```

### Searching with Category Filter (via MCP)
Use `rag_search` with category parameter:
```json
{
  "query": "sensor endpoint",
  "top_k": 5,
  "category": "infrastructure/sensors"
}
```

### Deleting Memory (via MCP)
Use `rag_delete_memory` with memory ID:
```json
{
  "memory_id": "uuid-here"
}
```

## Benefits
1. **Precise retrieval** - Category filters narrow search scope
2. **Better context** - Related information grouped together
3. **Easier maintenance** - Delete/update specific categories
4. **Prevents pollution** - Structured organization prevents chaos
5. **Child agent stability** - Less noise = better results = no freezing
