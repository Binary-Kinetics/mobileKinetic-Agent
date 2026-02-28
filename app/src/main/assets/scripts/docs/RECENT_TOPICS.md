# Recent Topics - Conversation Diary

A running log of recent discussions and work sessions to help pick up where we left off.

## Table of Contents
1. [2025-02-03: Calendar/Task API Discovery & System Prompt Clarifications](#2025-02-03-calendartask-api-discovery--system-prompt-clarifications)

---

## 2025-02-03: Calendar/Task API Discovery & System Prompt Clarifications

**Date**: 2025-02-03
**Session Focus**: System configuration, API endpoint discovery, watchdog issues

### Key Discussions

1. **System Prompt Location**
   - Identified that system prompt is currently hardcoded
   - CLAUDE.md exists at `/data/user/0/com.mobilekinetic.agent/files/home/CLAUDE.md` and should be the authoritative source
   - Plan: Update mK:a app to load system prompt from CLAUDE.md instead of hardcoding

2. **RAG Access Rules Clarified**
   - Updated CLAUDE.md to clarify: Main agents CAN use curl directly to port 5562
   - Child agents spawned via Task tool MUST use MCP tools only (direct curl freezes them)
   - This is why mcp_rag_server.py exists

3. **Grep Tool Issue**
   - Discovered Grep tool doesn't work - ripgrep (rg) not installed on device
   - Added to CLAUDE.md NEVER list and Critical Warnings
   - Stored in RAG for future reference
   - Solution: Use Bash with standard grep instead

4. **Watchdog Timeouts & MCP Grep Solution**
   - Multiple watchdog timeouts in session (8 total: PIDs 14736, 24968, 27251, 4817, 8523, 10534, 13387, 21240)
   - Task agents auto-spawning in background and hanging
   - Cause: Child agents trying to use Grep tool which requires ripgrep (not installed)
   - **Solution Created**: Built `mcp_grep_server.py` to intercept Grep calls and translate to standard grep
   - MCP server supports all Grep parameters, has 30s timeout protection
   - Needs to be configured in orchestrator's mcpServers config to activate
   - **Agent Crash**: After 8th timeout (PID 21240), main agent crashed/froze. Space bar turned into enter key on the operator's device. Agent was unresponsive for several minutes.

5. **Calendar and Task API Discovery**
   - **Calendar READ**: `GET http://localhost:5563/calendar/events` - WORKS
     - Returns events with id, title, description, start_time (epoch ms), end_time, location, calendar_id, all_day flag
     - Tested successfully, saw Valentine's Day, Valentine dinner, Presidents' Day
   - **Tasks READ**: `GET http://localhost:5563/tasks/list` - WORKS
     - Returns Android task list (currently empty)
   - **Write Operations**: Not yet implemented
     - `POST /tasks/add` returns 404
     - Need to add POST/PUT/DELETE endpoints for creating/modifying calendar events and tasks
   - **Tasker Integration**: `GET http://localhost:5563/tasker/tasks` exists but returns "Tasker does not expose task list via API"

6. **Battery Status**
   - Battery endpoint working: `GET http://localhost:5563/battery`
   - Returns percentage, charging status, temperature, voltage, health, technology

### Action Items
- [ ] Update mK:a app to load system prompt from CLAUDE.md
- [ ] Configure mcp_grep_server.py in orchestrator's mcpServers config
- [ ] Investigate agent crash/freeze issue (8 watchdog timeouts caused main agent to become unresponsive)
- [ ] Add calendar/task write endpoints (POST/PUT/DELETE)
- [ ] Explore Tasker task execution endpoints
- [ ] Consider building MCP tools for calendar/task access
- [ ] Expose agent tools menu to user as quick-access items

### Files Created/Modified
- `/data/user/0/com.mobilekinetic.agent/files/home/CLAUDE.md` - Updated RAG access rules, added Grep tool warning
- `/data/user/0/com.mobilekinetic.agent/files/home/mcp_grep_server.py` - Created MCP server to wrap standard grep for child agents
- `/data/user/0/com.mobilekinetic.agent/files/home/RECENT_TOPICS.md` - Created conversation diary file

### RAG Entries Added
- RAG access clarification for main vs child agents
- Grep tool warning (ripgrep not installed)
- Calendar/Task endpoint documentation
- Tasker integration status
- Watchdog timeout pattern (multiple hangs)

---

*Last Updated: 2025-02-03*
