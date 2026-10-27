"""Import a lossless external GMIT transcript into a private replay bundle."""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable, Mapping

from .event_bundle import BundleError, canonicalize_rows, normalize_session_id, sha256_file, write_bundle


ADAPTER_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = ADAPTER_ROOT.parents[1]
PRIVATE_REPOSITORY_ROOTS = tuple((REPOSITORY_ROOT / relative).resolve() for relative in ("integrations/gmit/local", "bin/GMIT/local"))


# One delimiter space after ':' is structural. Any additional leading spaces
# belong to the utterance and are retained in the imported text.
LABELLED_LINE = re.compile(r"^\s*(Therapist|Facilitator|Patient A|Patient B)\s*:(?: ?)(.*)$", re.IGNORECASE)


def _records_from_json(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        rows = value
    elif isinstance(value, dict):
        candidates = [value.get(key) for key in ("events", "dialog_act_rows", "rows") if key in value]
        if len(candidates) != 1 or not isinstance(candidates[0], list):
            raise BundleError("JSON must be a row array or contain exactly one of events, dialog_act_rows, rows")
        rows = candidates[0]
    else:
        raise BundleError("JSON root must be an object or array")
    if not all(isinstance(row, dict) for row in rows):
        raise BundleError("every structured row must be a JSON object")
    return [dict(row) for row in rows]


def read_rows(path: Path, kind: str) -> list[dict[str, Any]]:
    # newline='' prevents Python's universal-newline layer from turning CRLF
    # inside quoted CSV cells into LF before canonicalization.
    with path.open("r", encoding="utf-8", newline="") as handle:
        raw = handle.read()
    if kind == "auto":
        suffix = path.suffix.lower()
        kind = {".csv": "csv", ".json": "json", ".jsonl": "jsonl", ".txt": "labelled"}.get(suffix, "")
        if not kind:
            raise BundleError("cannot infer input type; pass --format")
    if kind == "csv":
        rows = list(csv.DictReader(io.StringIO(raw)))
        if not rows:
            raise BundleError("CSV contains no data rows")
        return rows
    if kind == "json":
        return _records_from_json(json.loads(raw))
    if kind == "jsonl":
        rows: list[dict[str, Any]] = []
        for line_number, line in enumerate(raw.splitlines(), 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise BundleError(f"JSONL line {line_number} is not an object")
            rows.append(value)
        if not rows:
            raise BundleError("JSONL contains no object rows")
        return rows
    if kind == "labelled":
        rows = []
        for line_number, line in enumerate(raw.splitlines(), 1):
            if not line.strip():
                continue
            match = LABELLED_LINE.match(line)
            if not match:
                raise BundleError(f"unlabelled or ambiguous transcript line {line_number}")
            text = match.group(2)
            if not text:
                raise BundleError(f"labelled transcript line {line_number} has empty text")
            rows.append({"speaker": match.group(1), "text": text, "source_turn": str(line_number)})
        if not rows:
            raise BundleError("labelled transcript contains no utterance lines")
        return rows
    raise BundleError(f"unsupported input format {kind!r}")


def select_episode(rows: list[dict[str, Any]], episode: str | None) -> list[dict[str, Any]]:
    session_values = [row.get("session_id") for row in rows]
    has_session = [value not in (None, "") for value in session_values]
    if any(has_session) and not all(has_session):
        raise BundleError("structured input mixes rows with and without session_id values")
    if all(has_session) and len({str(value) for value in session_values}) != 1:
        raise BundleError("input contains multiple session_id values; split it before import")

    episode_fields = [key for key in ("episode", "episode_id") if any(row.get(key) not in (None, "") for row in rows)]
    if len(episode_fields) > 1:
        raise BundleError("input has both episode and episode_id fields; normalize to one before import")
    episode_field = episode_fields[0] if episode_fields else "episode"
    has_episode = [row.get(episode_field) not in (None, "") for row in rows]
    if any(has_episode) and not all(has_episode):
        raise BundleError("structured input mixes rows with and without episode values")
    if not all(has_episode):
        if episode is not None:
            raise BundleError("--episode was supplied but input has no episode field")
        return rows
    episodes = {str(row[episode_field]) for row in rows}
    if episode is None:
        if len(episodes) != 1:
            raise BundleError("input contains multiple episodes; pass --episode to select exactly one")
        return rows
    selected = [row for row in rows if str(row[episode_field]) == episode]
    if not selected:
        raise BundleError(f"episode {episode!r} was not found")
    return selected


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--format", choices=("auto", "csv", "json", "jsonl", "labelled"), default="auto")
    parser.add_argument("--episode")
    parser.add_argument("--session-id")
    parser.add_argument("--condition", choices=("pure", "rl", "unknown"), default="unknown")
    parser.add_argument("--floor-mode", default="recorded")
    parser.add_argument("--title")
    parser.add_argument("--output-dir", type=Path)
    return parser


def private_output_path(path: Path) -> Path:
    """Allow ignored adapter roots in this repository, or a resolved external path."""
    resolved = path.expanduser().resolve(strict=False)
    # DrvFS can resolve Users/users to the same directory while retaining the
    # supplied spelling. Lexical relative_to() alone would mistake it for an
    # external destination and allow a public-repository output path.
    def contains_directory(directory: Path) -> bool:
        for ancestor in (resolved, *resolved.parents):
            try:
                if ancestor.samefile(directory):
                    return True
            except (FileNotFoundError, NotADirectoryError):
                continue
        return False

    if not contains_directory(REPOSITORY_ROOT):
        return resolved
    if any(contains_directory(private_root) for private_root in PRIVATE_REPOSITORY_ROOTS):
        return resolved
    raise BundleError("--output-dir inside the repository must be under integrations/gmit/local or bin/GMIT/local")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        source = args.input.resolve(strict=True)
        rows = select_episode(read_rows(source, args.format), args.episode)
        session_id = normalize_session_id(args.session_id or f"import-{source.stem}")
        output_dir = private_output_path(args.output_dir or ADAPTER_ROOT / "local" / "imports" / session_id)
        events = canonicalize_rows(rows, session_id=session_id, condition=args.condition)
        result = write_bundle(
            events,
            output_dir=output_dir,
            provenance="transcript-import",
            floor_mode=args.floor_mode,
            input_sha256=sha256_file(source),
            title=args.title or "Imported GMIT transcript",
            extra_receipt={"input_path": str(source), "input_format": args.format, "episode": args.episode},
        )
    except (BundleError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"import failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"events": str(result.events_path), "replay": str(result.replay_path), "receipt": str(result.receipt_path), "event_count": result.event_count}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
