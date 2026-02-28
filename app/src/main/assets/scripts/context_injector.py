#!/usr/bin/env python3
"""Context injection system for mK:a orchestrator."""

import asyncio
import json
import logging
import os
import re
import sqlite3
import time
from collections import defaultdict
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import httpx
from mka_config import (
    MEMORY_DB_PATH, RAG_URL, VAULT_URL,
)
from trigger_store import TriggerStore, Trigger

logger = logging.getLogger(__name__)

# === TOKEN BUDGET MANAGER ===
class TokenBudgetManager:
    TIER_BUDGETS = {0: 800, 1: 400, 2: 400, 3: 400}
    TOTAL_BUDGET = 2000

    def allocate(self, tier: int, content: str) -> str:
        budget = self.TIER_BUDGETS.get(tier, 0)
        estimated_tokens = len(content) // 4
        if estimated_tokens <= budget:
            return content
        max_chars = budget * 4
        truncated = content[:max_chars]
        last_period = truncated.rfind('.')
        if last_period > max_chars * 0.7:
            truncated = truncated[:last_period + 1]
        return truncated + "\n[...truncated to fit token budget]"

# === MEMORY TAGGING PROTOCOL (static text) ===
MEMORY_TAGGING_PROTOCOL = """## Memory Tagging Protocol
When you encounter information worth preserving across sessions, emit a tag:
[MEMORY_TAG: tier, metadata, "summary"]

Tiers:
- permanent: People, promises, system facts, dates -- things that should never expire
- midterm: Topic summaries, project status -- relevant for weeks/months
- short-term: Session details, specific commands tried -- relevant for days
- disposable: Scaffolding, retries, temporary debugging -- do not store

Examples:
[MEMORY_TAG: permanent, {people: ["example_contact"], topic: "contacts"}, "Example contact relationship and location"]
[MEMORY_TAG: midterm, {topic: "mobilekinetic_tts"}, "TTS race condition fixed with AtomicBoolean guard in KokoroTtsService"]
[MEMORY_TAG: short-term, {topic: "debugging"}, "Port 5563 conflict resolved by killing stale adb forward"]
"""

# === PRIORITY INJECTOR ===
class PriorityInjector:
    def __init__(self, rag_url: str = RAG_URL, tier1_interval: int = 5, vault_client=None):
        self.rag_url = rag_url
        self.tier1_interval = tier1_interval
        self.budget = TokenBudgetManager()
        self.http_client = httpx.AsyncClient(timeout=10.0)
        # Phase 4: SemanticTriggerEngine wired in
        self.trigger_engine = SemanticTriggerEngine()
        # Phase 13: TriggerLearner — auto-learns triggers from conversation patterns
        # Shares the same TriggerStore instance as SemanticTriggerEngine so that
        # auto-learned triggers are immediately available for matching.
        self.trigger_learner = TriggerLearner(
            trigger_store=self.trigger_engine._store,
            db_path=MEMORY_DB_PATH,
        )
        self.vault_client = vault_client

    async def inject(self, message: str, exchange_count: int) -> str:
        tiers = {}
        try:
            tiers[0] = await self._build_tier0()
        except Exception as e:
            logger.warning(f"Tier 0 injection failed: {e}")
            tiers[0] = ""
        try:
            tiers[1] = await self._build_tier1(exchange_count)
        except Exception as e:
            logger.warning(f"Tier 1 injection failed: {e}")
            tiers[1] = ""
        # Phase 4: Tier 2 — semantic trigger injection
        try:
            tiers[2] = await self._build_tier2(message)
        except Exception as e:
            logger.warning(f"Tier 2 injection failed: {e}")
            tiers[2] = ""
        # Phase 5: Tier 3 — session context (first exchange only)
        try:
            if exchange_count == 1:
                tiers[3] = await self._build_session_context()
            else:
                tiers[3] = ""
        except Exception as e:
            logger.warning(f"Tier 3 injection failed: {e}")
            tiers[3] = ""
        injection_block = self._format_injection(tiers)
        if injection_block:
            return injection_block + "\n\n" + message
        return message

    async def _build_tier0(self) -> str:
        sections = []
        # Fetch permanent user preferences (RAG category: "user")
        prefs = await self._query_rag("user preferences device constraints", category="user")
        if prefs:
            sections.append("## User Preferences\n" + prefs)
        # Fetch active warnings
        warnings = await self._query_rag("active warnings critical gotchas", category="warning")
        if warnings:
            sections.append("## Active Warnings\n" + warnings)
        # Phase 9: Vault credential catalog (dynamic when unlocked, static fallback)
        try:
            vault_resp = await self.http_client.get(
                f"{VAULT_URL}/vault/catalog", timeout=3.0
            )
            if vault_resp.status_code == 200:
                catalog = vault_resp.json().get("credentials", [])
                if catalog:
                    lines = ["## Vault Credentials"]
                    lines.append(
                        "Use `/vault/proxy-http` to make authenticated requests. "
                        "Claude NEVER sees credential values.\n"
                    )
                    for cred in catalog:
                        lines.append(
                            f"**{cred['name']}**: {cred.get('description', '')}"
                        )
                        lines.append(
                            f"  - Service: `{cred.get('service', 'N/A')}`"
                        )
                        lines.append(
                            f"  - Inject as: `{cred.get('injectAs', 'bearer_header')}`"
                        )
                        if cred.get("hint"):
                            lines.append(f"  - Usage: {cred['hint']}")
                        lines.append("")
                    lines.append(
                        f"Proxy syntax: `curl -s -X POST {VAULT_URL}/vault/proxy-http "
                        "-H 'Content-Type: application/json' "
                        "-d '{\"credentialName\":\"NAME\",\"url\":\"URL\",\"method\":\"GET\","
                        "\"injectAs\":\"TYPE\",\"context\":\"REASON\"}'`"
                    )
                    lines.append(
                        "\nIf vault is locked (423), ask the user to unlock in "
                        "Settings \u2192 Gemma Credentials."
                    )
                    sections.append("\n".join(lines))
                else:
                    sections.append(self._static_vault_hint())
            elif vault_resp.status_code == 423:
                sections.append(
                    "## Vault\n"
                    "Vault is locked. Ask the user to unlock in "
                    "Settings \u2192 Gemma Credentials."
                )
            else:
                sections.append(self._static_vault_hint())
        except Exception:
            sections.append(self._static_vault_hint())
        # Always append Memory Tagging Protocol
        sections.append(MEMORY_TAGGING_PROTOCOL)
        content = "\n\n".join(sections)
        return self.budget.allocate(0, content)

    async def _build_tier1(self, exchange_count: int) -> str:
        # Fire on first message (exchange_count == 1) or every N messages
        if exchange_count != 1 and exchange_count % self.tier1_interval != 0:
            return ""
        sections = []
        user_info = await self._query_rag("user info operator identity", category="user")
        if user_info:
            sections.append("## User Info\n" + user_info)
        network = await self._query_rag("network topology devices IPs ports", category="system")
        if network:
            sections.append("## Network Topology\n" + network)
        content = "\n\n".join(sections)
        return self.budget.allocate(1, content)

    async def _build_tier2(self, message: str) -> str:
        """Phase 4: Match user message against semantic triggers, inject RAG context."""
        fired = self.trigger_engine.match(message)
        if not fired:
            return ""
        sections = []
        for trigger in fired:
            rag_result = await self._query_rag(
                trigger.rag_query,
                category=trigger.rag_category,
                top_k=max(1, trigger.max_tokens // 100),
            )
            if rag_result:
                header = f"## Triggered Context [{trigger.id}]"
                # Honour per-trigger token budget
                truncated = rag_result[: trigger.max_tokens * 4]
                sections.append(f"{header}\n{truncated}")
        if not sections:
            return ""
        content = "\n\n".join(sections)
        return self.budget.allocate(2, content)

    async def _build_session_context(self) -> str:
        """Phase 5: Fetch prior session summaries via /session_context endpoint."""
        try:
            resp = await self.http_client.post(f"{self.rag_url}/session_context", json={})
            if resp.status_code == 200:
                data = resp.json()
                context = data.get("context", "")
                if context and context.strip():
                    return self.budget.allocate(3, "## Prior Session Context\n" + context)
        except Exception as e:
            logger.warning(f"Session context fetch failed (non-fatal): {e}")
        return ""

    def _format_injection(self, tiers: dict) -> str:
        parts = []
        for tier_num in sorted(tiers.keys()):
            content = tiers[tier_num]
            if content and content.strip():
                parts.append(content.strip())
        if not parts:
            return ""
        return "[SYSTEM CONTEXT - Auto-injected]\n" + "\n\n".join(parts) + "\n[END SYSTEM CONTEXT]"

    def _static_vault_hint(self):
        """Fallback vault hint when catalog is unavailable."""
        return (
            "## Vault\n"
            "Credentials are in the secure vault but CANNOT be read directly. "
            "To make authenticated requests, use the vault proxy:\n"
            "```\n"
            f"curl -s -X POST {VAULT_URL}/vault/proxy-http "
            "-H 'Content-Type: application/json' "
            "-d '{\"credentialName\":\"NAME\",\"url\":\"URL\",\"method\":\"GET\","
            "\"injectAs\":\"bearer_header\",\"context\":\"REASON\"}'\n"
            "```\n"
            "If the vault is locked, ask the user to unlock it in Settings."
        )

    async def _query_rag(self, query: str, category: str = None, top_k: int = 5) -> str:
        try:
            payload = {"query": query, "top_k": top_k}
            if category:
                payload["category"] = category
            resp = await self.http_client.post(f"{self.rag_url}/context", json=payload)
            if resp.status_code == 200:
                data = resp.json()
                return data.get("context", "")
        except Exception as e:
            logger.warning(f"RAG query failed: {e}")
        return ""

    def observe_exchange(self, message: str, response: str) -> None:
        """
        Phase 13: Record one conversation exchange with TriggerLearner.

        Call this after each message/response pair so TriggerLearner can
        accumulate keyword observations.  If LEARN_INTERVAL is configured,
        learn() fires automatically inside observe().

        Parameters
        ----------
        message  : The user's message text that was injected.
        response : The assistant's response (reserved for future use).
        """
        self.trigger_learner.observe(message, response)

    def run_learn_pass(self) -> int:
        """
        Phase 13: Manually trigger a TriggerLearner learning pass.

        Returns the number of new triggers promoted in this pass.
        Safe to call at session end or on a schedule.
        """
        return self.trigger_learner.learn()

    async def close(self):
        await self.http_client.aclose()


# ---------------------------------------------------------------------------
# Phase 4: SemanticTriggerEngine
# ---------------------------------------------------------------------------

class SemanticTriggerEngine:
    """
    Matches incoming user messages against enabled triggers stored in TriggerStore.
    Uses simple keyword substring matching (case-insensitive).
    Returns the list of Trigger objects that fired, for use by PriorityInjector._build_tier2().
    """

    def __init__(self, store: Optional[TriggerStore] = None):
        self._store = store or TriggerStore()

    def match(self, user_message: str) -> List[Trigger]:
        """
        Return all enabled triggers whose keywords appear in user_message.
        Matching is case-insensitive substring search.
        """
        text = user_message.lower()
        fired: List[Trigger] = []
        for trigger in self._store.list_enabled():
            if any(kw.lower() in text for kw in trigger.keywords):
                fired.append(trigger)
        return fired

    def reload(self) -> None:
        """Force-reload the trigger store from disk."""
        self._store.load()


# ---------------------------------------------------------------------------
# Phase 13: TriggerLearner — Auto-Learn Triggers from conversation patterns
# ---------------------------------------------------------------------------

class TriggerLearner:
    """
    Observes conversation exchanges and auto-creates semantic triggers when
    recurring patterns are detected.

    Data flow:
        PriorityInjector calls observe(message, response) after each exchange
        → keywords extracted from message are recorded in trigger_observations
        → learn() mines those observations; keywords hitting MIN_HITS times
          are promoted to real triggers via TriggerStore
        → auto-created triggers carry source="auto_learn" to distinguish them
          from manually created ones (source="manual")

    Errata note: COMMON_WORDS is English-only.  Non-English conversations will
    have poor stop-word filtering.  This is a known limitation — expanding to
    other languages requires replacing this set with a multilingual stop-word
    library (e.g. nltk.corpus.stopwords).
    """

    # Minimum number of observations before a keyword is promoted to a trigger
    MIN_HITS: int = 3

    # Maximum number of observation rows kept per keyword in RAM before flush
    MAX_BUFFER_PER_KEYWORD: int = 50

    # How many exchanges between periodic learn() calls (0 = manual only)
    LEARN_INTERVAL: int = 10

    # English stop-words to exclude from candidate keywords.
    # NOTE: English-only — this set will not filter stop-words in other languages.
    COMMON_WORDS: frozenset = frozenset({
        "a", "an", "the", "and", "or", "but", "if", "in", "on", "at",
        "to", "for", "of", "with", "by", "from", "is", "are", "was",
        "were", "be", "been", "being", "have", "has", "had", "do", "does",
        "did", "will", "would", "could", "should", "may", "might", "shall",
        "can", "need", "dare", "ought", "used", "it", "its", "this",
        "that", "these", "those", "i", "you", "he", "she", "we", "they",
        "me", "him", "her", "us", "them", "my", "your", "his", "our",
        "their", "what", "which", "who", "how", "when", "where", "why",
        "all", "each", "every", "both", "few", "more", "most", "other",
        "some", "such", "no", "not", "only", "same", "so", "than",
        "too", "very", "just", "about", "up", "out", "as", "into",
        "through", "during", "before", "after", "above", "below",
        "between", "own", "off", "over", "under", "again", "then",
        "once", "here", "there", "any", "also", "now", "get", "got",
        "let", "make", "like", "know", "see", "use", "go", "come",
        "think", "look", "want", "give", "find", "tell", "ask", "seem",
        "feel", "try", "leave", "call", "keep", "put", "set", "run",
    })

    def __init__(
        self,
        trigger_store: Optional[TriggerStore] = None,
        db_path: str = MEMORY_DB_PATH,
    ) -> None:
        """
        Parameters
        ----------
        trigger_store : TriggerStore, optional
            Shared TriggerStore instance.  A new one is created if not provided.
        db_path : str
            Path to the memory_lifecycle.py SQLite DB that holds the
            trigger_observations table.  Defaults to the app's memory DB.
        """
        self._trigger_store: TriggerStore = trigger_store or TriggerStore()
        self._db_path: str = db_path
        # RAM buffer: keyword -> list of context snippets observed with it
        self._buffer: Dict[str, List[str]] = defaultdict(list)
        self._exchange_count: int = 0
        self._logger = logging.getLogger(__name__ + ".TriggerLearner")

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def observe(self, message: str, response: str) -> None:
        """
        Record one conversation exchange.

        Extracts candidate keywords from *message*, then persists each
        keyword observation to the trigger_observations SQLite table so
        that patterns survive process restarts.

        Also called by PriorityInjector after every exchange.  If
        LEARN_INTERVAL > 0 and the interval has elapsed, learn() is
        triggered automatically (synchronously; DB write is fast).

        Parameters
        ----------
        message  : The user's message text.
        response : The assistant's response (unused currently; reserved for
                   future bi-directional pattern mining).
        """
        if not message or not message.strip():
            return

        self._exchange_count += 1
        candidates = self._extract_candidates(message)
        if not candidates:
            return

        # Persist to trigger_observations table
        context_snippet = message[:200]  # Store first 200 chars as context
        self._persist_observations(candidates, context_snippet)

        # Populate RAM buffer for fast learn() pass
        for keyword in candidates:
            buf = self._buffer[keyword]
            if len(buf) < self.MAX_BUFFER_PER_KEYWORD:
                buf.append(context_snippet)

        # Periodic auto-learn
        if self.LEARN_INTERVAL > 0 and self._exchange_count % self.LEARN_INTERVAL == 0:
            self._logger.debug(
                "TriggerLearner: exchange_count=%d — running periodic learn()",
                self._exchange_count,
            )
            self.learn()

    def learn(self) -> int:
        """
        Analyse trigger_observations in the DB and auto-create triggers for
        keywords that have been observed MIN_HITS or more times.

        Returns the number of new triggers created in this pass.
        """
        promoted = 0
        try:
            keyword_counts = self._count_observations_from_db()
        except Exception as exc:
            self._logger.warning("TriggerLearner.learn() DB read failed: %s", exc)
            # Fall back to RAM buffer counts if DB is unavailable
            keyword_counts = {kw: len(ctx) for kw, ctx in self._buffer.items()}

        for keyword, count in keyword_counts.items():
            if count < self.MIN_HITS:
                continue

            # Skip if a trigger for this keyword already exists
            existing = self._trigger_store.list_enabled()
            already_tracked = any(
                keyword.lower() in [kw.lower() for kw in (t.keywords or [])]
                for t in existing
            )
            if already_tracked:
                self._logger.debug(
                    "TriggerLearner: keyword '%s' already has a trigger — skipping",
                    keyword,
                )
                continue

            # Build and insert a new auto-learned trigger
            observations_ctx = self._buffer.get(keyword, [])
            rag_query = self._build_rag_query(keyword, observations_ctx)
            try:
                new_trigger = Trigger(
                    id=keyword.replace(" ", "_"),
                    keywords=[keyword],
                    rag_query=rag_query,
                    rag_category=None,
                    max_tokens=300,
                    enabled=True,
                    source="auto_learn",
                )
                self._trigger_store.add(new_trigger)
                promoted += 1
                self._logger.info(
                    "TriggerLearner: promoted '%s' as trigger (seen %d times)",
                    keyword,
                    count,
                )
            except Exception as exc:
                self._logger.warning(
                    "TriggerLearner: failed to add trigger for '%s': %s",
                    keyword,
                    exc,
                )

        # Clear RAM buffer after a learn pass
        self._buffer.clear()
        self._logger.debug(
            "TriggerLearner.learn() complete — %d new triggers promoted", promoted
        )
        return promoted

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _extract_candidates(self, text: str) -> List[str]:
        """
        Extract potential trigger keywords from *text*.

        Strategy:
        - Tokenise on word boundaries (keep alphanumeric + underscore)
        - Filter tokens shorter than 3 characters
        - Exclude tokens that are in COMMON_WORDS (English stop-words)
        - Deduplicate while preserving first-seen order

        Returns a list of lowercase keyword strings.

        Note: English-only stop-word filtering — see COMMON_WORDS docstring.
        """
        tokens = re.findall(r"\b[a-zA-Z][a-zA-Z0-9_]{2,}\b", text)
        seen: dict = {}
        for tok in tokens:
            lower = tok.lower()
            if lower not in self.COMMON_WORDS and lower not in seen:
                seen[lower] = True
        return list(seen.keys())

    def _build_rag_query(self, keyword: str, observations: List[str]) -> str:
        """
        Build a RAG query string for an auto-learned trigger.

        Uses the keyword itself plus a brief context sample from observed
        messages to make the query more specific.

        Parameters
        ----------
        keyword      : The promoted keyword.
        observations : List of context snippets seen alongside the keyword.
        """
        if not observations:
            return keyword
        # Take up to 3 unique context snippets, truncate each to 60 chars
        samples = list(dict.fromkeys(observations[:3]))
        context_hint = "; ".join(s[:60].strip() for s in samples)
        return f"{keyword} {context_hint}"

    def _persist_observations(self, keywords: List[str], context: str) -> None:
        """
        Write one row per keyword into the trigger_observations table.

        The trigger_observations schema (from memory_lifecycle.py):
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            memory_id TEXT NOT NULL,       -- repurposed as keyword text
            trigger_type TEXT NOT NULL,    -- "auto_learn_candidate"
            observed_at TEXT NOT NULL,     -- ISO-8601 UTC timestamp
            context TEXT                   -- snippet of the message
        """
        try:
            conn = sqlite3.connect(self._db_path, timeout=5)
            observed_at = datetime.now(timezone.utc).isoformat()
            rows = [
                (kw, "auto_learn_candidate", observed_at, context)
                for kw in keywords
            ]
            with conn:
                conn.executemany(
                    "INSERT INTO trigger_observations "
                    "(memory_id, trigger_type, observed_at, context) "
                    "VALUES (?, ?, ?, ?)",
                    rows,
                )
            conn.close()
        except Exception as exc:
            # Non-fatal: observations table may not exist in older DB versions
            self._logger.debug("TriggerLearner: could not persist observations: %s", exc)

    def _count_observations_from_db(self) -> Dict[str, int]:
        """
        Query trigger_observations for auto_learn_candidate rows and return
        a mapping of keyword -> observation count.
        """
        conn = sqlite3.connect(self._db_path, timeout=5)
        try:
            cursor = conn.execute(
                "SELECT memory_id, COUNT(*) FROM trigger_observations "
                "WHERE trigger_type = 'auto_learn_candidate' "
                "GROUP BY memory_id",
            )
            result = {row[0]: row[1] for row in cursor.fetchall()}
        finally:
            conn.close()
        return result
