"""Lossless event bundle helpers shared by the public GMIT adapters.

This module deliberately contains no GMIT prompts, personas, models, or client
credentials.  It only preserves structured rows produced elsewhere.
"""

from __future__ import annotations

import hashlib
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping


ACTOR_MAP = {
    "therapist": "facilitator",
    "facilitator": "facilitator",
    "patient a": "patient-a",
    "patient-a": "patient-a",
    "patient b": "patient-b",
    "patient-b": "patient-b",
}
ERROR_TEXTS = {"[llm call failed]", "[nlg function call failed]", "..."}
_SESSION_RE = re.compile(r"[^a-z0-9-]+")


class BundleError(ValueError):
    """Raised when an imported record cannot be represented without guessing."""


@dataclass(frozen=True)
class BundleResult:
    events_path: Path
    replay_path: Path
    receipt_path: Path
    event_count: int


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalize_session_id(value: str) -> str:
    normalized = _SESSION_RE.sub("-", value.strip().lower()).strip("-")
    if not normalized:
        raise BundleError("session_id must contain at least one letter or digit")
    return normalized


def actor_id(value: object) -> str:
    normalized = " ".join(str(value or "").strip().lower().replace("_", " ").split())
    try:
        return ACTOR_MAP[normalized]
    except KeyError as exc:
        raise BundleError(
            "unsupported speaker/actor value "
            f"{value!r}; expected Therapist, Facilitator, Patient A, or Patient B"
        ) from exc


def _optional_text(row: Mapping[str, Any], key: str) -> str | None:
    value = row.get(key)
    if value is None or value == "":
        return None
    return str(value)


def _require_xml10_text(value: str, *, field: str) -> None:
    """Reject XML 1.0-invalid controls instead of serializing changed text."""
    for char in value:
        codepoint = ord(char)
        if codepoint < 0x20 and char not in "\t\n\r":
            raise BundleError(f"{field} contains an XML 1.0 control character")
        if 0xD800 <= codepoint <= 0xDFFF or codepoint in {0xFFFE, 0xFFFF}:
            raise BundleError(f"{field} contains an XML 1.0-invalid character")


def canonicalize_rows(
    rows: Iterable[Mapping[str, Any]],
    *,
    session_id: str,
    condition: str,
) -> list[dict[str, Any]]:
    """Make canonical events without rewriting the source text or metadata."""
    if condition not in {"pure", "rl", "unknown"}:
        raise BundleError("condition must be pure, rl, or unknown")
    normalized_session = normalize_session_id(session_id)
    events: list[dict[str, Any]] = []
    for sequence, row in enumerate(rows, 1):
        speaker = row.get("speaker", row.get("actor_id"))
        text_value = row.get("text")
        if not isinstance(text_value, str) or not text_value or not text_value.strip():
            raise BundleError(f"row {sequence} has missing or empty text")
        act = _optional_text(row, "act")
        if (act and act.strip().upper() == "ERROR") or text_value.strip().lower() in ERROR_TEXTS:
            raise BundleError(f"row {sequence} is an error placeholder, not an utterance")
        event: dict[str, Any] = {
            "schema_version": 1,
            "session_id": normalized_session,
            "event_id": f"{normalized_session}--{sequence:04d}",
            "sequence": sequence,
            "actor_id": actor_id(speaker),
            "text": text_value,
            "condition": condition,
        }
        source_session_id = _optional_text(row, "source_session_id") or _optional_text(row, "session_id")
        if source_session_id is not None:
            event["source_session_id"] = source_session_id
        for source_key, target_key in (
            ("act", "act"),
            ("stage", "stage"),
            ("source_turn", "source_turn"),
            ("turn", "source_turn"),
            ("source_event_id", "source_event_id"),
            ("event_id", "source_event_id"),
        ):
            value = _optional_text(row, source_key)
            if value is not None and target_key not in event:
                event[target_key] = value
        events.append(event)
    if not events:
        raise BundleError("input contains no utterance rows")
    return events


def _utf16_units(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def split_playback_text(text: str, *, max_chars: int = 160, max_utf16: int = 300) -> list[str]:
    """Split text losslessly, preferring whitespace while respecting both limits."""
    if not text:
        raise BundleError("cannot create a playback chunk from empty text")
    chunks: list[str] = []
    remaining = text
    while remaining:
        end = 0
        utf16_count = 0
        for index, char in enumerate(remaining, 1):
            next_utf16 = utf16_count + _utf16_units(char)
            if index > max_chars or next_utf16 > max_utf16:
                break
            end = index
            utf16_count = next_utf16
        if end == 0:
            raise BundleError("a character exceeds the UTF-16 playback limit")
        if end < len(remaining):
            whitespace = max((i for i in range(1, end + 1) if remaining[i - 1].isspace()), default=0)
            if whitespace:
                end = whitespace
        chunk = remaining[:end]
        if not chunk:
            raise BundleError("could not make progress while splitting playback text")
        if not chunk.strip():
            raise BundleError("cannot represent a whitespace-only playback chunk losslessly")
        chunks.append(chunk)
        remaining = remaining[end:]
    if "".join(chunks) != text:
        raise AssertionError("playback splitting changed source text")
    return chunks


def _write_jsonl(path: Path, events: list[dict[str, Any]]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        for event in events:
            handle.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")


def _write_replay_xml(
    path: Path,
    events: list[dict[str, Any]],
    *,
    provenance: str,
    floor_mode: str,
    title: str | None,
) -> None:
    condition = str(events[0].get("condition", "unknown"))
    if condition not in {"pure", "rl", "unknown"}:
        raise BundleError("event condition must be pure, rl, or unknown")
    if not title or not title.strip():
        raise BundleError("replay title is required")
    _require_xml10_text(title, field="title")
    attrs = {
        "schema_version": "1",
        "session_id": str(events[0]["session_id"]),
        "provenance": provenance,
        "floor_mode": floor_mode,
        "title": title,
        "condition": condition,
    }
    for name, value in attrs.items():
        _require_xml10_text(value, field=f"root {name}")
    root = ET.Element("gmit-session", attrs)
    replay_sequence = 0
    for event in events:
        text = str(event["text"])
        _require_xml10_text(text, field=f"event {event['event_id']} text")
        chunks = split_playback_text(text)
        for chunk_index, chunk in enumerate(chunks, 1):
            replay_sequence += 1
            event_id = str(event["event_id"])
            turn_attrs = {
                "sequence": str(replay_sequence),
                "event_id": event_id if len(chunks) == 1 else f"{event_id}--chunk-{chunk_index}",
                "actor_id": str(event["actor_id"]),
                "stage": str(event.get("stage") or "unknown"),
                "act": str(event.get("act") or "unknown"),
                # Always link XML playback chunks to canonical JSONL events.
                # Original-system event identity remains JSONL provenance.
                "source_event_id": event_id,
            }
            if len(chunks) > 1:
                turn_attrs.update(
                    {
                        "chunk_index": str(chunk_index),
                        "chunk_count": str(len(chunks)),
                    }
                )
            if "source_turn" in event:
                turn_attrs["source_turn"] = str(event["source_turn"])
            for name, value in turn_attrs.items():
                _require_xml10_text(value, field=f"event {event_id} {name}")
            turn = ET.SubElement(root, "turn", turn_attrs)
            ET.SubElement(turn, "text").text = chunk
    ET.indent(root, space="  ")
    # XML parsers normalize literal CR to LF.  Numeric CR references survive a
    # DOM round trip, which is necessary for lossless CSV imports.
    serialized = ET.tostring(root, encoding="utf-8", xml_declaration=True).replace(b"\r", b"&#13;")
    with path.open("xb") as handle:
        handle.write(serialized)


def write_bundle(
    events: list[dict[str, Any]],
    *,
    output_dir: Path,
    provenance: str,
    floor_mode: str,
    input_sha256: str | None,
    title: str | None = None,
    extra_receipt: Mapping[str, Any] | None = None,
) -> BundleResult:
    """Write a new private bundle and a receipt. Existing destinations are refused."""
    if provenance not in {"gmit-generated", "transcript-import"}:
        raise BundleError("provenance must be gmit-generated or transcript-import")
    if not events:
        raise BundleError("cannot write an empty event bundle")
    if output_dir.exists():
        raise BundleError(f"refusing to overwrite existing output directory: {output_dir}")
    output_dir.mkdir(parents=True)
    events_path = output_dir / "events.jsonl"
    replay_path = output_dir / "replay.xml"
    receipt_path = output_dir / "receipt.json"
    try:
        total_chunks = sum(len(split_playback_text(str(event["text"]))) for event in events)
        if not 1 <= total_chunks <= 256:
            raise BundleError(f"playback would contain {total_chunks} chunks; expected 1 through 256")
        _write_jsonl(events_path, events)
        _write_replay_xml(
            replay_path,
            events,
            provenance=provenance,
            floor_mode=floor_mode,
            title=title,
        )
        receipt: dict[str, Any] = {
            "schema_version": 1,
            "session_id": events[0]["session_id"],
            "event_count": len(events),
            "provenance": provenance,
            "floor_mode": floor_mode,
            "input_sha256": input_sha256,
            "events_sha256": sha256_file(events_path),
            "replay_sha256": sha256_file(replay_path),
        }
        if extra_receipt:
            receipt.update(extra_receipt)
        with receipt_path.open("x", encoding="utf-8", newline="\n") as handle:
            json.dump(receipt, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
    except Exception:
        # Keep failed writes inspectable only when they are not a valid bundle.
        for path in (receipt_path, replay_path, events_path):
            path.unlink(missing_ok=True)
        output_dir.rmdir()
        raise
    return BundleResult(events_path, replay_path, receipt_path, len(events))
