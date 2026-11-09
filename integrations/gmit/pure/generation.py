"""Local private-asset loading and public provenance for the Pure port."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_FEWSHOT_PATH = PACKAGE_ROOT.parent / "local" / "pure-assets" / "patient-fewshot.json"
REQUIRED_FEWSHOT_PAIR_COUNT = 15
FIXED_OPENING_TEXT = "Welcome everyone. Let's begin by hearing what's on your mind."
PORT_PROVENANCE = {
    "source_repository": "private GMIT checkout",
    "source_revision": "f2bb22d22d8a64c9404365426450801b28d33a66",
    "source_files": {
        "src/gmit/agents/taxonomy.py": "e85411f165e46f45d9ca35879be2bf173f975b7d61bc290a8e58096f37613b90",
        "src/gmit/agents/patient.py": "49f1c78e090e1035ef427fe49551c358dbdec95c386384311b9b9f7397a35a61",
        "src/gmit/agents/therapist_nlg.py": "daedc22a29043dad40c5dabcb530d01d64954066368f078f012d92fe42732817",
        "src/gmit/utils/json_utils.py": "8d3b428e359ef7e0e36ddd9512ab197df62671a4223f9370049cce21c29c57be",
        "src/gmit/utils/text.py": "3f985d6fdb471091f028603a97f823496220117be58b2eb9601686b858c769b0",
        "src/gmit/env/round_robin.py": "7ab568b2a95b058fa901c37a470db79f3c72224d66992a43bc1494da9cddfa71",
        "benchmark_llm.py": "782aa5f52743ae3ba73ec9f04a7b4e77274cf421ce0e74085ab56a892893b5ad",
    },
}


class PureGenerationError(RuntimeError):
    """Raised when a private local Pure asset is unavailable or malformed."""


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def package_provenance() -> dict[str, object]:
    """Return a cwd-independent receipt for all public Pure source files."""
    files = sorted(path for path in PACKAGE_ROOT.glob("*.py") if path.is_file())
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return {
        "generator": "integrations.gmit.pure.episode.run_round_robin_episode",
        "package_root": str(PACKAGE_ROOT),
        "source_files": [path.name for path in files],
        "source_sha256": digest.hexdigest(),
        "license": "MIT (Copyright (c) 2025 Alafate)",
        "port_provenance": PORT_PROVENANCE,
    }


def load_fewshot_asset(path: Path | str | None = None) -> tuple[list[dict[str, str]], dict[str, object]]:
    """Load the mandatory ignored local asset without importing a GMIT checkout."""
    asset = Path(path) if path is not None else DEFAULT_FEWSHOT_PATH
    try:
        asset = asset.expanduser().resolve(strict=True)
    except OSError as exc:
        raise PureGenerationError(f"few-shot asset is unavailable: {asset}") from exc
    try:
        decoded = json.loads(asset.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PureGenerationError(f"few-shot asset is not valid JSON: {asset}") from exc
    if not isinstance(decoded, list) or len(decoded) != REQUIRED_FEWSHOT_PAIR_COUNT:
        raise PureGenerationError(f"few-shot asset must be a JSON array with exactly {REQUIRED_FEWSHOT_PAIR_COUNT} entries")
    pairs: list[dict[str, str]] = []
    for index, value in enumerate(decoded, 1):
        if not isinstance(value, dict):
            raise PureGenerationError(f"few-shot asset entry {index} is not an object")
        input_text, output_text = value.get("input"), value.get("output")
        if not isinstance(input_text, str) or not input_text.strip() or not isinstance(output_text, str) or not output_text.strip():
            raise PureGenerationError(f"few-shot asset entry {index} requires non-empty input and output strings")
        pairs.append({"input": input_text, "output": output_text})
    return pairs, {"path": str(asset), "count": len(pairs), "sha256": _sha256_file(asset)}
