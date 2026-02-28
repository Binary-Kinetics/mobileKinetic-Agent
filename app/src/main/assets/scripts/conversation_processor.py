#!/usr/bin/env python3
"""Conversation processing pipeline for memory extraction."""

import json
import os
import re
import subprocess
import logging
import asyncio
import uuid
import httpx
from dataclasses import dataclass, field
from mka_config import RAG_URL
from pathlib import Path
from typing import Generator, List, Tuple, Optional
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

# === DATA MODELS ===
@dataclass
class Block:
    type: str           # e.g. 'human', 'assistant', 'tool_use', 'tool_result'
    role: str           # 'user', 'assistant', 'system', 'tool'
    content: str        # text content
    metadata: dict = field(default_factory=dict)
    raw_json: dict = field(default_factory=dict)

@dataclass
class TaggedMemory:
    tier: str           # 'permanent', 'midterm', 'short-term'
    metadata: dict      # parsed JSON metadata
    summary: str        # summary text

# === JSONL PARSER ===
class JSONLParser:
    """Reads JSONL file and yields parsed blocks."""

    def parse(self, filepath: str) -> Generator[Block, None, None]:
        with open(filepath, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    parsed = json.loads(line)
                except json.JSONDecodeError:
                    continue

                block = self._to_block(parsed)
                if block:
                    yield block

    def _to_block(self, parsed: dict) -> Optional[Block]:
        block_type = parsed.get("type", "unknown")
        role = parsed.get("role", "unknown")

        # Extract text content
        content = ""
        if isinstance(parsed.get("content"), str):
            content = parsed["content"]
        elif isinstance(parsed.get("content"), list):
            text_parts = []
            for item in parsed["content"]:
                if isinstance(item, dict) and item.get("type") == "text":
                    text_parts.append(item.get("text", ""))
            content = "\n".join(text_parts)
        elif isinstance(parsed.get("text"), str):
            content = parsed["text"]

        if not content.strip():
            return None

        return Block(
            type=block_type,
            role=role,
            content=content,
            metadata={},
            raw_json=parsed,
        )

# === RULE-BASED TAGGER ===
class RuleBasedTagger:
    """Adds metadata tags to blocks based on pattern detection."""

    DATE_PATTERNS = [
        re.compile(r'\d{4}-\d{2}-\d{2}'),                      # ISO 8601
        re.compile(r'\d{1,2}/\d{1,2}/\d{4}'),                  # US date
        re.compile(r'(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\w*\s+\d{1,2},?\s+\d{4}', re.I),
    ]
    URL_PATTERN = re.compile(r'https?://[^\s<>"]+')
    CODE_BLOCK_PATTERN = re.compile(r'```[\s\S]*?```')
    FILE_PATH_PATTERNS = [
        re.compile(r'(?:/[\w.-]+)+'),                            # Unix paths
        re.compile(r'[A-Z]:\\(?:[\w.-]+\\)*[\w.-]+'),           # Windows paths
    ]

    def tag(self, blocks: List[Block]) -> List[Block]:
        for block in blocks:
            # Date detection
            dates = set()
            for pattern in self.DATE_PATTERNS:
                dates.update(pattern.findall(block.content))
            if dates:
                block.metadata["dates"] = list(dates)

            # URL detection
            urls = self.URL_PATTERN.findall(block.content)
            if urls:
                block.metadata["urls"] = urls

            # Code block detection
            if self.CODE_BLOCK_PATTERN.search(block.content):
                block.metadata["has_code"] = True

            # File path detection
            paths = set()
            for pattern in self.FILE_PATH_PATTERNS:
                paths.update(pattern.findall(block.content))
            if paths:
                block.metadata["file_paths"] = list(paths)

        return blocks

# === MEMORY TAG EXTRACTOR ===
class MemoryTagExtractor:
    """Finds and parses [MEMORY_TAG] blocks."""

    TAG_PATTERN = re.compile(
        r'\[MEMORY_TAG:\s*(permanent|midterm|short-term|disposable)\s*,\s*(\{[^}]*\})\s*,\s*"([^"]+)"\s*\]'
    )

    def extract(self, blocks: List[Block]) -> Tuple[List[TaggedMemory], List[Block]]:
        tagged_memories = []
        remaining_blocks = []

        for block in blocks:
            matches = self.TAG_PATTERN.findall(block.content)
            if matches:
                for tier, metadata_str, summary in matches:
                    if tier == "disposable":
                        continue
                    try:
                        # Parse metadata JSON (may have unquoted keys)
                        metadata_str = self._fix_json_keys(metadata_str)
                        metadata = json.loads(metadata_str)
                    except json.JSONDecodeError:
                        metadata = {}
                    tagged_memories.append(TaggedMemory(
                        tier=tier, metadata=metadata, summary=summary
                    ))
                # Remove tags from content, keep remainder
                cleaned = self.TAG_PATTERN.sub('', block.content).strip()
                if cleaned:
                    block.content = cleaned
                    remaining_blocks.append(block)
            else:
                remaining_blocks.append(block)

        return tagged_memories, remaining_blocks

    def _fix_json_keys(self, s: str) -> str:
        """Add quotes to unquoted JSON keys like {people: [...]}."""
        return re.sub(r'(\{|,)\s*(\w+)\s*:', r'\1 "\2":', s)

# === JS STRIP RUNNER ===
def run_js_strip(input_path: str, output_path: str = None) -> dict:
    """Run jsonl_strip.js on a file. Returns stats dict or raises."""
    if output_path is None:
        output_path = input_path.replace('.jsonl', '.stripped.tmp')

    scripts_dir = os.path.dirname(os.path.abspath(__file__))
    js_path = os.path.join(scripts_dir, 'jsonl_strip.js')

    result = subprocess.run(
        ['node', js_path, input_path, output_path],
        capture_output=True, text=True, timeout=30
    )

    if result.returncode != 0:
        error_info = result.stderr.strip() if result.stderr else "Unknown error"
        raise RuntimeError(f"jsonl_strip.js failed: {error_info}")

    # Parse stats from stderr
    try:
        stats = json.loads(result.stderr.strip())
    except json.JSONDecodeError:
        stats = {"error": result.stderr.strip()}

    return stats


# === PHASE 6: HAIKU CLASSIFICATION ===

@dataclass
class ClassifiedMemory:
    """A memory block that has been classified by Haiku and is ready to write."""
    tier: str               # 'permanent', 'midterm', 'short-term' (disposable discarded)
    summary: str            # text summary of the memory
    topic: Optional[str]    # topic string, may be None
    conversation_type: Optional[str]  # e.g. 'technical', 'personal', 'factual'
    people: List[str]       # list of person names mentioned
    tags: List[str]         # semantic tags from Haiku


class HaikuClassifier:
    """Sends conversation blocks to claude-3-5-haiku-20241022 for memory tier classification."""

    HAIKU_PROMPT = """\
You are a memory classification assistant. Given conversation text, decide whether it \
contains information worth storing as long-term memory, and classify it into a retention tier.

Respond ONLY with a JSON object matching this schema (no prose, no code fences):
{
  "tier": "permanent" | "midterm" | "short-term" | "disposable",
  "summary": "<one concise sentence describing the memory>",
  "topic": "<single topic keyword, e.g. project_setup, user_preference, system_config>",
  "conversation_type": "<one of: technical, personal, factual, procedural, casual>",
  "people": ["<name>", ...],
  "tags": ["<tag>", ...]
}

Tier definitions:
- permanent   : user identity, long-lived facts, critical configs — never expires
- midterm     : project state, preferences, recurring topics — 18 weeks
- short-term  : recent context, transient tasks — 3 weeks
- disposable  : casual chat, one-off questions, nothing to remember — DISCARD

Be concise. If nothing is worth storing, use "disposable"."""

    def __init__(self, api_key: str, max_retries: int = 3):
        self._api_key = api_key
        self._max_retries = max_retries
        self._client = httpx.AsyncClient(
            base_url="https://api.anthropic.com",
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            timeout=60.0,
        )

    async def classify(self, blocks: List[Block]) -> List[ClassifiedMemory]:
        """Classify a list of blocks via Haiku. Returns only non-disposable results."""
        results: List[ClassifiedMemory] = []
        batches = self._create_batches(blocks)
        for batch in batches:
            classified = await self._classify_batch(batch)
            results.extend(classified)
        return results

    def _create_batches(self, blocks: List[Block], max_chars: int = 4000) -> List[List[Block]]:
        """Group blocks into batches not exceeding max_chars total content."""
        batches: List[List[Block]] = []
        current_batch: List[Block] = []
        current_chars = 0

        for block in blocks:
            block_len = len(block.content)
            if current_batch and current_chars + block_len > max_chars:
                batches.append(current_batch)
                current_batch = [block]
                current_chars = block_len
            else:
                current_batch.append(block)
                current_chars += block_len

        if current_batch:
            batches.append(current_batch)

        return batches

    async def _classify_batch(self, blocks: List[Block]) -> List[ClassifiedMemory]:
        """Call Haiku API for a single batch of blocks. Returns ClassifiedMemory list."""
        conversation_text = "\n\n".join(
            f"[{b.role.upper()}]: {b.content}" for b in blocks
        )

        payload = {
            "model": "claude-3-5-haiku-20241022",
            "max_tokens": 512,
            "system": self.HAIKU_PROMPT,
            "messages": [
                {"role": "user", "content": conversation_text}
            ],
        }

        last_error: Exception = RuntimeError("No attempts made")
        for attempt in range(self._max_retries):
            try:
                response = await self._client.post("/v1/messages", json=payload)
                response.raise_for_status()
                raw = response.json()["content"][0]["text"].strip()
                result = self._parse_response(raw)
                if result is None:
                    return []
                return [result]
            except (httpx.HTTPStatusError, httpx.RequestError, KeyError, json.JSONDecodeError) as exc:
                last_error = exc
                logger.warning("Haiku classify attempt %d failed: %s", attempt + 1, exc)
                if attempt < self._max_retries - 1:
                    await asyncio.sleep(2 ** attempt)

        logger.error("Haiku classify failed after %d retries: %s", self._max_retries, last_error)
        return []

    def _parse_response(self, raw: str) -> Optional["ClassifiedMemory"]:
        """Parse Haiku JSON response into ClassifiedMemory. Returns None for disposable."""
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            # Try to extract JSON if wrapped in prose
            match = re.search(r'\{[\s\S]*\}', raw)
            if not match:
                logger.warning("Haiku returned non-JSON: %s", raw[:200])
                return None
            try:
                data = json.loads(match.group())
            except json.JSONDecodeError:
                logger.warning("Haiku JSON parse failed for: %s", raw[:200])
                return None

        tier = data.get("tier", "disposable")
        if tier == "disposable":
            return None  # Caller discards these

        return ClassifiedMemory(
            tier=tier,
            summary=data.get("summary", ""),
            topic=data.get("topic"),
            conversation_type=data.get("conversation_type"),
            people=data.get("people", []),
            tags=data.get("tags", []),
        )

    async def close(self):
        """Close the underlying httpx client."""
        await self._client.aclose()


class MemoryWriter:
    """Writes ClassifiedMemory objects to SQLite (via MemoryIndex) and RAG/session endpoints."""

    def __init__(self, memory_index, rag_url: str = RAG_URL):
        self._index = memory_index
        self._rag_url = rag_url.rstrip("/")

    async def write(self, memories: List[ClassifiedMemory], session_id: str) -> List[str]:
        """
        Write each memory to SQLite AND RAG. Returns list of written memory IDs.
        Disposable-tier entries must be filtered out before calling this method.
        """
        written_ids: List[str] = []

        async with httpx.AsyncClient(timeout=15.0) as client:
            for mem in memories:
                # Compute expiry from tier
                expires_at: Optional[str] = None
                if mem.tier == "short-term":
                    expires_at = (datetime.utcnow() + timedelta(weeks=3)).isoformat() + "Z"
                elif mem.tier == "midterm":
                    expires_at = (datetime.utcnow() + timedelta(weeks=18)).isoformat() + "Z"
                # permanent: expires_at stays None

                # 1. Write to RAG server and get rag_memory_id
                rag_memory_id = await self._write_rag(
                    client=client,
                    text=mem.summary,
                    category=mem.topic or "general",
                    tier=mem.tier,
                    expires_at=expires_at,
                    people=mem.people,
                    conv_type=mem.conversation_type,
                )

                # 2. Write to SQLite via MemoryIndex
                memory_obj = self._index.make_memory(
                    rag_memory_id=rag_memory_id,
                    retention_tier=mem.tier,
                    topic=mem.topic,
                    conversation_type=mem.conversation_type,
                    source_session=session_id,
                    summary=mem.summary,
                    people=mem.people,
                    tags=mem.tags,
                )
                self._index.add_memory(memory_obj)
                written_ids.append(memory_obj.id)

                # 3. POST to session/memories so SessionContextBuilder can see it immediately
                try:
                    await client.post(
                        f"{self._rag_url}/session/memories",
                        json={
                            "id": memory_obj.id,
                            "tier": mem.tier,
                            "summary": mem.summary,
                            "topic": mem.topic,
                            "conversation_type": mem.conversation_type,
                            "people": mem.people,
                            "tags": mem.tags,
                            "expires_at": expires_at,
                            "session_id": session_id,
                        },
                    )
                except httpx.RequestError as exc:
                    logger.warning("session/memories POST failed (non-fatal): %s", exc)

        return written_ids

    async def _write_rag(
        self,
        client: httpx.AsyncClient,
        text: str,
        category: str,
        tier: str,
        expires_at: Optional[str],
        people: List[str],
        conv_type: Optional[str],
    ) -> str:
        """POST memory to RAG /memory endpoint. Returns rag_memory_id string."""
        payload = {
            "text": text,
            "category": category,
            "metadata": {
                "tier": tier,
                "expires_at": expires_at,
                "people": people,
                "conversation_type": conv_type,
            },
        }
        try:
            resp = await client.post(f"{self._rag_url}/memory", json=payload)
            resp.raise_for_status()
            data = resp.json()
            # RAG server returns {"id": "..."} or {"memory_id": "..."}
            rag_id = data.get("id") or data.get("memory_id") or str(uuid.uuid4())
        except (httpx.RequestError, httpx.HTTPStatusError, KeyError) as exc:
            logger.warning("RAG /memory write failed: %s — using local UUID", exc)
            rag_id = str(uuid.uuid4())
        return rag_id


async def process_session(
    jsonl_path: str,
    api_key: str,
    session_id: Optional[str] = None,
    rag_url: str = RAG_URL,
    db_path: Optional[str] = None,
) -> dict:
    """
    Full Phase 6 pipeline:
      JS strip → parse JSONL → rule-based tag → extract MEMORY_TAGs
      → Haiku classify remaining blocks → write all memories.

    Returns a summary dict with counts of tagged/classified/written memories.
    """
    # Lazy import to avoid hard dependency when running tests without memory_lifecycle installed
    try:
        from memory_lifecycle import MemoryIndex
    except ImportError as exc:
        raise ImportError("memory_lifecycle module not found — is it in the Python path?") from exc

    if session_id is None:
        session_id = str(uuid.uuid4())

    # Step 1: JS strip (rewrites JSONL in-place to stripped tmp file)
    stripped_path = jsonl_path.replace('.jsonl', '.stripped.tmp')
    try:
        strip_stats = run_js_strip(jsonl_path, stripped_path)
        logger.info("JS strip stats: %s", strip_stats)
        parse_path = stripped_path
    except RuntimeError as exc:
        logger.warning("JS strip failed (%s), parsing original file", exc)
        parse_path = jsonl_path

    # Step 2: Parse JSONL into blocks
    parser = JSONLParser()
    blocks: List[Block] = list(parser.parse(parse_path))
    logger.info("Parsed %d blocks from %s", len(blocks), parse_path)

    # Step 3: Rule-based tag
    tagger = RuleBasedTagger()
    blocks = tagger.tag(blocks)

    # Step 4: Extract explicit MEMORY_TAGs
    extractor = MemoryTagExtractor()
    tagged_memories, remaining_blocks = extractor.extract(blocks)
    logger.info("Extracted %d MEMORY_TAG memories; %d blocks remain", len(tagged_memories), len(remaining_blocks))

    # Convert TaggedMemory → ClassifiedMemory (they're already classified by tier)
    explicit_classified: List[ClassifiedMemory] = []
    for tm in tagged_memories:
        explicit_classified.append(ClassifiedMemory(
            tier=tm.tier,
            summary=tm.summary,
            topic=tm.metadata.get("topic"),
            conversation_type=tm.metadata.get("conversation_type"),
            people=tm.metadata.get("people", []),
            tags=tm.metadata.get("tags", []),
        ))

    # Step 5: Haiku classify remaining blocks (if any and api_key provided)
    haiku_classified: List[ClassifiedMemory] = []
    if remaining_blocks and api_key:
        classifier = HaikuClassifier(api_key=api_key)
        try:
            haiku_classified = await classifier.classify(remaining_blocks)
            logger.info("Haiku classified %d memories from %d blocks", len(haiku_classified), len(remaining_blocks))
        finally:
            await classifier.close()

    # Step 6: Write all classified memories
    all_memories = explicit_classified + haiku_classified
    kwargs = {"db_path": db_path} if db_path else {}
    index = MemoryIndex(**kwargs)
    writer = MemoryWriter(memory_index=index, rag_url=rag_url)
    written_ids = await writer.write(all_memories, session_id=session_id)
    logger.info("Wrote %d memories for session %s", len(written_ids), session_id)

    return {
        "session_id": session_id,
        "blocks_parsed": len(blocks),
        "tagged_memory_count": len(explicit_classified),
        "haiku_classified_count": len(haiku_classified),
        "written_count": len(written_ids),
        "written_ids": written_ids,
    }
