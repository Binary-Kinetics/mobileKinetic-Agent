#!/usr/bin/env python3
"""Memory lifecycle management -- SQLite CRUD, decay, backup, backfill."""

import asyncio
import glob
import json
import os
import shutil
import sqlite3
import time
import uuid
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, List

logger = logging.getLogger(__name__)

# === CONSTANTS ===
_OLD_DB_PATH = "/data/user/0/com.mobilekinetic.agent/files/memory/memory_index.db"
DEFAULT_DB_PATH = "/data/user/0/com.mobilekinetic.agent/files/home/Memory/RAG/memory_index.db"
os.makedirs(os.path.dirname(DEFAULT_DB_PATH), exist_ok=True)
SHORT_TERM_WEEKS = 3
MIDTERM_WEEKS = 18

# === DATA MODELS ===
@dataclass
class Memory:
    id: str                          # UUID
    rag_memory_id: str               # ID in RAG server
    retention_tier: str              # 'permanent', 'midterm', 'short-term'
    topic: Optional[str] = None
    conversation_type: Optional[str] = None
    source_session: Optional[str] = None
    created_at: str = ""             # ISO 8601
    expires_at: Optional[str] = None # ISO 8601, None for permanent
    summary: Optional[str] = None
    people: str = "[]"               # JSON array of names
    tags: str = "[]"                 # JSON array of tags

# === SCHEMA ===
SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS memories (
    id TEXT PRIMARY KEY,
    rag_memory_id TEXT NOT NULL,
    retention_tier TEXT NOT NULL,
    topic TEXT,
    conversation_type TEXT,
    source_session TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT,
    summary TEXT,
    people TEXT,
    tags TEXT
);
CREATE INDEX IF NOT EXISTS idx_retention ON memories(retention_tier);
CREATE INDEX IF NOT EXISTS idx_expires ON memories(expires_at);
CREATE INDEX IF NOT EXISTS idx_topic ON memories(topic);
CREATE INDEX IF NOT EXISTS idx_people ON memories(people);
CREATE INDEX IF NOT EXISTS idx_session ON memories(source_session);
CREATE INDEX IF NOT EXISTS idx_type ON memories(conversation_type);
CREATE INDEX IF NOT EXISTS idx_created ON memories(created_at);

CREATE TABLE IF NOT EXISTS trigger_observations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keyword TEXT NOT NULL,
    rag_query TEXT NOT NULL,
    session_id TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    UNIQUE(keyword, rag_query, session_id)
);
CREATE INDEX IF NOT EXISTS idx_trigger_obs ON trigger_observations(keyword, rag_query);

CREATE TABLE IF NOT EXISTS lifecycle_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

# === MEMORY INDEX ===
class MemoryIndex:
    def __init__(self, db_path: str = DEFAULT_DB_PATH):
        self.db_path = db_path
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        # Migrate from old path if new path doesn't exist yet
        if not os.path.exists(db_path) and os.path.exists(_OLD_DB_PATH):
            logger.info("[MemoryIndex] Migrating DB from %s to %s", _OLD_DB_PATH, db_path)
            shutil.copy2(_OLD_DB_PATH, db_path)
        self.conn = sqlite3.connect(db_path)
        self.conn.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self):
        cursor = self.conn.cursor()
        cursor.executescript(SCHEMA_SQL)
        self.conn.commit()
        # Set schema version
        cursor.execute(
            "INSERT OR REPLACE INTO lifecycle_metadata (key, value) VALUES (?, ?)",
            ("schema_version", "1")
        )
        self.conn.commit()

    def insert(self, memory: Memory) -> str:
        if not memory.id:
            memory.id = str(uuid.uuid4())
        if not memory.created_at:
            memory.created_at = datetime.utcnow().isoformat() + "Z"
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO memories (id, rag_memory_id, retention_tier, topic,
                conversation_type, source_session, created_at, expires_at,
                summary, people, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (memory.id, memory.rag_memory_id, memory.retention_tier,
              memory.topic, memory.conversation_type, memory.source_session,
              memory.created_at, memory.expires_at, memory.summary,
              memory.people, memory.tags))
        self.conn.commit()
        return memory.id

    def get(self, memory_id: str) -> Optional[Memory]:
        cursor = self.conn.cursor()
        cursor.execute("SELECT * FROM memories WHERE id = ?", (memory_id,))
        row = cursor.fetchone()
        if not row:
            return None
        return self._row_to_memory(row)

    def query(self, tier: str = None, topic: str = None, person: str = None,
              conv_type: str = None, limit: int = 50) -> List[Memory]:
        conditions = []
        params = []
        if tier:
            conditions.append("retention_tier = ?")
            params.append(tier)
        if topic:
            conditions.append("topic = ?")
            params.append(topic)
        if person:
            conditions.append("people LIKE ?")
            params.append(f'%"{person}"%')
        if conv_type:
            conditions.append("conversation_type = ?")
            params.append(conv_type)
        where = " AND ".join(conditions) if conditions else "1=1"
        cursor = self.conn.cursor()
        cursor.execute(
            f"SELECT * FROM memories WHERE {where} ORDER BY created_at DESC LIMIT ?",
            params + [limit]
        )
        return [self._row_to_memory(row) for row in cursor.fetchall()]

    def get_expired(self, tier: str) -> List[Memory]:
        now = datetime.utcnow().isoformat() + "Z"
        cursor = self.conn.cursor()
        cursor.execute(
            "SELECT * FROM memories WHERE retention_tier = ? AND expires_at IS NOT NULL AND expires_at < ?",
            (tier, now)
        )
        return [self._row_to_memory(row) for row in cursor.fetchall()]

    def delete(self, memory_id: str) -> bool:
        cursor = self.conn.cursor()
        cursor.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
        self.conn.commit()
        return cursor.rowcount > 0

    def update_tier(self, memory_id: str, new_tier: str, new_expires_at: str = None):
        cursor = self.conn.cursor()
        cursor.execute(
            "UPDATE memories SET retention_tier = ?, expires_at = ? WHERE id = ?",
            (new_tier, new_expires_at, memory_id)
        )
        self.conn.commit()

    def get_lifecycle_meta(self, key: str) -> Optional[str]:
        cursor = self.conn.cursor()
        cursor.execute("SELECT value FROM lifecycle_metadata WHERE key = ?", (key,))
        row = cursor.fetchone()
        return row[0] if row else None

    def set_lifecycle_meta(self, key: str, value: str):
        cursor = self.conn.cursor()
        cursor.execute(
            "INSERT OR REPLACE INTO lifecycle_metadata (key, value) VALUES (?, ?)",
            (key, value)
        )
        self.conn.commit()

    def close(self):
        self.conn.close()

    def _row_to_memory(self, row) -> Memory:
        return Memory(
            id=row["id"],
            rag_memory_id=row["rag_memory_id"],
            retention_tier=row["retention_tier"],
            topic=row["topic"],
            conversation_type=row["conversation_type"],
            source_session=row["source_session"],
            created_at=row["created_at"],
            expires_at=row["expires_at"],
            summary=row["summary"],
            people=row["people"],
            tags=row["tags"],
        )


# === BACKUP MANAGER ===
class BackupManager:
    """Backs up memory_index.db to a local directory with rotation.

    NOTE: os.statvfs is NOT used here — it does not work on Android.
    Size verification is done by comparing file sizes directly.
    """

    DEFAULT_BACKUP_DIR = "/data/user/0/com.mobilekinetic.agent/files/home/Memory/Backups/"

    def __init__(
        self,
        db_path: str = DEFAULT_DB_PATH,
        backup_dir: str = DEFAULT_BACKUP_DIR,
        max_copies: int = 8,
    ):
        self.db_path = Path(db_path)
        self.backup_dir = Path(backup_dir)
        self.max_copies = max_copies

    def backup(self) -> dict:
        """Copy memory_index.db to backup_dir with a timestamp filename.

        Returns a status dict: {"success": bool, "path": str|None, "error": str|None}
        """
        result: dict = {"success": False, "path": None, "error": None}
        try:
            if self._check_lockfile():
                result["error"] = "Wipe in progress — backup aborted"
                logger.warning("[BackupManager] %s", result["error"])
                return result

            if not self.db_path.exists():
                result["error"] = f"Source DB not found: {self.db_path}"
                logger.error("[BackupManager] %s", result["error"])
                return result

            self.backup_dir.mkdir(parents=True, exist_ok=True)

            timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
            dest = self.backup_dir / f"memory_index_{timestamp}.db"

            shutil.copy2(str(self.db_path), str(dest))

            # Size verification: dest must be within 50% of source size
            src_size = self.db_path.stat().st_size
            dest_size = dest.stat().st_size
            if src_size > 0 and dest_size < src_size * 0.5:
                dest.unlink(missing_ok=True)
                result["error"] = (
                    f"Backup size mismatch: source={src_size} dest={dest_size}"
                )
                logger.error("[BackupManager] %s", result["error"])
                return result

            self._rotate_backups()

            result["success"] = True
            result["path"] = str(dest)
            logger.info("[BackupManager] Backup written to %s", dest)

        except Exception as exc:
            result["error"] = str(exc)
            logger.exception("[BackupManager] Backup failed: %s", exc)

        return result

    def _check_lockfile(self) -> bool:
        """Return True if a .wipe_in_progress lockfile exists in the db directory."""
        lock = self.db_path.parent / ".wipe_in_progress"
        return lock.exists()

    def _rotate_backups(self) -> None:
        """Delete oldest backups so only max_copies remain."""
        backups = sorted(self.backup_dir.glob("memory_index_*.db"), key=lambda p: p.name)
        excess = len(backups) - self.max_copies
        if excess > 0:
            for old_backup in backups[:excess]:
                try:
                    old_backup.unlink(missing_ok=True)
                    logger.debug("[BackupManager] Rotated out old backup: %s", old_backup)
                except Exception as exc:
                    logger.warning("[BackupManager] Could not delete %s: %s", old_backup, exc)


# === PHASE 12: JSONL ARCHIVE BACKFILL ===

# Default search paths for JSONL archives.  Paths are intentionally broad:
# Android device paths, rooted variants, and common desktop locations so the
# class works both on-device and in unit tests on a PC.
DEFAULT_JSONL_SEARCH_PATHS: List[str] = [
    # Android on-device paths (ClaudeCode app conversation archives)
    "/data/data/com.mobilekinetic.agent/files/conversations",
    "/data/user/0/com.mobilekinetic.agent/files/conversations",
    # Android external storage / sdcard variants
    "/sdcard/Android/data/com.mobilekinetic.agent/files/conversations",
    # Desktop / server paths (CI, developer workstations)
    os.path.expanduser("~/.claude/projects"),
    os.path.expanduser("~/claude/projects"),
    os.path.expanduser("~/conversations"),
]

_BACKFILL_CURSOR_KEY = "backfill_processed_files"


class BackfillManager:
    """Scans configurable paths for .jsonl conversation archives and processes
    each unprocessed file through the Phase-6 conversation_processor pipeline.

    Cursor tracking uses the MemoryIndex lifecycle_metadata table so progress
    survives app restarts.  The cursor is stored as a **JSON array** of
    absolute file path strings — never as a comma-separated string, which
    would break for paths that contain commas.

    Usage::

        from memory_lifecycle import MemoryIndex, BackfillManager
        index = MemoryIndex()
        mgr = BackfillManager(memory_index=index)
        asyncio.run(mgr.run_backfill(api_key="sk-..."))
    """

    def __init__(
        self,
        memory_index: "MemoryIndex",
        jsonl_search_paths: Optional[List[str]] = None,
    ) -> None:
        """Initialise the backfill manager.

        Args:
            memory_index: An open MemoryIndex instance used for cursor
                persistence via ``get_lifecycle_meta`` / ``set_lifecycle_meta``.
            jsonl_search_paths: Optional list of directories (or glob patterns)
                to scan for ``.jsonl`` archive files.  Falls back to
                ``DEFAULT_JSONL_SEARCH_PATHS`` when *None*.
        """
        self.memory_index = memory_index
        self.jsonl_search_paths: List[str] = (
            jsonl_search_paths
            if jsonl_search_paths is not None
            else list(DEFAULT_JSONL_SEARCH_PATHS)
        )

    # ------------------------------------------------------------------
    # Archive discovery
    # ------------------------------------------------------------------

    def scan_archives(self) -> List[str]:
        """Find all ``.jsonl`` files under *jsonl_search_paths* that have not
        yet been processed.

        Returns:
            Sorted list of absolute path strings for unprocessed archives.
        """
        already_done: List[str] = self.get_cursor()

        found: List[str] = []
        for search_path in self.jsonl_search_paths:
            expanded = os.path.expanduser(os.path.expandvars(search_path))
            # Support both directory paths and explicit glob patterns.
            if any(ch in expanded for ch in ("*", "?", "[")):
                candidates = glob.glob(expanded, recursive=True)
            else:
                candidates = glob.glob(
                    os.path.join(expanded, "**", "*.jsonl"), recursive=True
                )
            for candidate in candidates:
                abs_path = os.path.abspath(candidate)
                if abs_path not in already_done and abs_path not in found:
                    found.append(abs_path)

        found.sort()
        logger.debug(
            "[BackfillManager] scan_archives: found %d unprocessed archives", len(found)
        )
        return found

    # ------------------------------------------------------------------
    # Cursor persistence  (JSON array, NOT comma-separated)
    # ------------------------------------------------------------------

    def get_cursor(self) -> List[str]:
        """Read the list of already-processed archive paths from
        ``lifecycle_metadata``.

        Returns:
            A list of absolute path strings.  Returns an empty list when no
            cursor has been saved yet.
        """
        raw: Optional[str] = self.memory_index.get_lifecycle_meta(_BACKFILL_CURSOR_KEY)
        if not raw:
            return []
        try:
            value = json.loads(raw)
            if isinstance(value, list):
                return [str(p) for p in value]
            # Gracefully handle legacy single-value strings (should not occur).
            logger.warning(
                "[BackfillManager] Unexpected cursor format — expected JSON array, got: %r",
                raw,
            )
            return []
        except (json.JSONDecodeError, TypeError) as exc:
            logger.error(
                "[BackfillManager] Failed to parse cursor JSON: %s — resetting cursor", exc
            )
            return []

    def set_cursor(self, processed_files: List[str]) -> None:
        """Persist *processed_files* as a JSON array in ``lifecycle_metadata``.

        Args:
            processed_files: Complete list of absolute paths that have been
                processed so far.  Stored as a JSON array so that paths
                containing commas are handled correctly.
        """
        serialised = json.dumps([str(p) for p in processed_files])
        self.memory_index.set_lifecycle_meta(_BACKFILL_CURSOR_KEY, serialised)
        logger.debug(
            "[BackfillManager] set_cursor: %d paths persisted", len(processed_files)
        )

    # ------------------------------------------------------------------
    # Backfill execution
    # ------------------------------------------------------------------

    async def run_backfill(self, api_key: str) -> dict:
        """Process every unprocessed JSONL archive through the Phase-6
        ``conversation_processor.process_session()`` pipeline.

        Processed memories are routed to ``SessionMemoryRepository`` via the
        RAG HTTP server's ``POST /session/memories`` endpoint at ``:5562``.
        This routing is handled transparently by ``process_session()`` — no
        additional HTTP calls are required here.

        The cursor is updated after each successful file so that a crash or
        restart only re-processes the file that was in-flight at the time.

        Args:
            api_key: Anthropic API key forwarded to ``process_session()``.

        Returns:
            Summary dict with keys ``processed``, ``skipped``, ``errors``,
            and ``status`` (``"complete"`` or ``"error"``).
        """
        # Import here to avoid circular imports at module load time; Phase 6
        # conversation_processor lives in the same assets/scripts directory.
        try:
            import conversation_processor  # type: ignore[import]
        except ImportError as exc:
            logger.error(
                "[BackfillManager] Could not import conversation_processor: %s", exc
            )
            return {"status": "error", "error": str(exc), "processed": 0, "skipped": 0, "errors": 1}

        archives = self.scan_archives()
        already_done: List[str] = self.get_cursor()

        processed = 0
        skipped = 0
        errors = 0

        for archive_path in archives:
            if archive_path in already_done:
                skipped += 1
                continue

            logger.info("[BackfillManager] Processing archive: %s", archive_path)
            try:
                await conversation_processor.process_session(
                    jsonl_path=archive_path,
                    api_key=api_key,
                )
                already_done.append(archive_path)
                self.set_cursor(already_done)
                processed += 1
                logger.info(
                    "[BackfillManager] Processed %s (total so far: %d)", archive_path, processed
                )
            except Exception as exc:
                errors += 1
                logger.error(
                    "[BackfillManager] Error processing %s: %s", archive_path, exc
                )

        summary = {
            "status": "complete",
            "processed": processed,
            "skipped": skipped,
            "errors": errors,
        }
        logger.info("[BackfillManager] Backfill complete: %s", summary)
        return summary
