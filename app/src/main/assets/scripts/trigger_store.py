#!/usr/bin/env python3
"""
Phase 4: TriggerStore — JSON persistence for manual semantic triggers.

Provides CRUD operations for Trigger objects stored in a JSON file.
Triggers are matched against user messages and inject relevant RAG context.
"""

from __future__ import annotations

import json
import os
import shutil
import time
import uuid
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import List, Optional

# ---------------------------------------------------------------------------
# Path configuration
# ---------------------------------------------------------------------------

_OLD_TRIGGERS_PATH = Path("/data/user/0/com.mobilekinetic.agent/files/memory/triggers.json")
TRIGGERS_PATH = Path(os.environ.get(
    "TRIGGERS_PATH",
    "/data/user/0/com.mobilekinetic.agent/files/home/Memory/Triggers/triggers.json"
))


# ---------------------------------------------------------------------------
# Trigger dataclass
# ---------------------------------------------------------------------------

@dataclass
class Trigger:
    """A single manual semantic trigger entry."""
    id: str                              # unique slug, e.g. "ssh_credentials"
    keywords: List[str]                  # keywords/phrases that activate this trigger
    rag_query: str                       # query sent to RAG when this trigger fires
    rag_category: Optional[str]          # optional RAG category filter
    max_tokens: int                      # token budget for injected RAG result
    enabled: bool                        # whether this trigger is active
    created_at: float                    # Unix timestamp of creation
    source: str                          # who/what created it, e.g. "user", "agent"

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: dict) -> "Trigger":
        return cls(
            id=d["id"],
            keywords=d["keywords"],
            rag_query=d["rag_query"],
            rag_category=d.get("rag_category"),
            max_tokens=d.get("max_tokens", 300),
            enabled=d.get("enabled", True),
            created_at=d.get("created_at", time.time()),
            source=d.get("source", "unknown"),
        )


# ---------------------------------------------------------------------------
# TriggerStore — JSON-backed CRUD
# ---------------------------------------------------------------------------

class TriggerStore:
    """
    JSON-backed persistence layer for Trigger objects.

    Usage:
        store = TriggerStore()
        t = store.add("ssh_creds", ["ssh", "server", "remote"], "ssh credentials", source="user")
        triggers = store.list_enabled()
        store.update("ssh_creds", enabled=False)
        store.remove("ssh_creds")
    """

    def __init__(self, path: Path = TRIGGERS_PATH):
        self.path = path
        self._triggers: dict[str, Trigger] = {}
        # Migrate from old path if new path doesn't exist yet
        if not self.path.exists() and _OLD_TRIGGERS_PATH.exists():
            self.path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(str(_OLD_TRIGGERS_PATH), str(self.path))
        self.load()

    # ── Private ──────────────────────────────────────────────────────────────

    def load(self) -> None:
        """Load triggers from JSON file. Creates empty file if missing."""
        if not self.path.exists():
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.save()
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            self._triggers = {
                item["id"]: Trigger.from_dict(item)
                for item in raw.get("triggers", [])
            }
        except (json.JSONDecodeError, KeyError, TypeError):
            # Corrupt file — start fresh, don't crash
            self._triggers = {}
            self.save()

    def save(self) -> None:
        """Persist current in-memory state to disk."""
        payload = {
            "triggers": [t.to_dict() for t in self._triggers.values()]
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(payload, indent=2, ensure_ascii=False),
            encoding="utf-8"
        )

    # ── Public API ────────────────────────────────────────────────────────────

    def add(
        self,
        trigger_id: str,
        keywords: List[str],
        rag_query: str,
        rag_category: Optional[str] = None,
        max_tokens: int = 300,
        enabled: bool = True,
        source: str = "user",
    ) -> Trigger:
        """Create and persist a new trigger. Returns the created Trigger."""
        t = Trigger(
            id=trigger_id,
            keywords=keywords,
            rag_query=rag_query,
            rag_category=rag_category,
            max_tokens=max_tokens,
            enabled=enabled,
            created_at=time.time(),
            source=source,
        )
        self._triggers[trigger_id] = t
        self.save()
        return t

    def remove(self, trigger_id: str) -> bool:
        """Delete a trigger by ID. Returns True if it existed."""
        if trigger_id in self._triggers:
            del self._triggers[trigger_id]
            self.save()
            return True
        return False

    def update(self, trigger_id: str, **kwargs) -> Optional[Trigger]:
        """Partial update of a trigger's fields. Returns updated Trigger or None."""
        t = self._triggers.get(trigger_id)
        if t is None:
            return None
        for k, v in kwargs.items():
            if hasattr(t, k) and k != "id":
                setattr(t, k, v)
        self.save()
        return t

    def get(self, trigger_id: str) -> Optional[Trigger]:
        """Retrieve a trigger by ID."""
        return self._triggers.get(trigger_id)

    def list_enabled(self) -> List[Trigger]:
        """Return all enabled triggers."""
        return [t for t in self._triggers.values() if t.enabled]

    def list_all(self) -> List[Trigger]:
        """Return all triggers regardless of enabled state."""
        return list(self._triggers.values())
