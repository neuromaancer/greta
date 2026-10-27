"""Run one bounded, existing GMIT Pure-LLM episode through native Ollama.

This is an external caller of ``benchmark_llm.run_round_robin_episode``.  It
does not reimplement GMIT prompts, personas, or floor scheduling.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import multiprocessing as mp
import os
from queue import Empty
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from pathlib import Path
from typing import Any

from .event_bundle import BundleError, canonicalize_rows, normalize_session_id, write_bundle
from .importer import private_output_path


# GMIT is read-only.  In particular, importing its modules must not create
# __pycache__ entries under the external checkout.
os.environ.setdefault("PYTHONDONTWRITEBYTECODE", "1")
sys.dont_write_bytecode = True

MODEL_ID = "qwen3:4b-instruct-2507-q4_K_M"
OLLAMA_OPTIONS = {"num_ctx": 8192, "num_predict": 256}


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _read_ollama_identity(host: str, model: str, timeout_seconds: float) -> dict[str, Any]:
    url = host.rstrip("/") + "/api/tags"
    request = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise BundleError(f"Ollama preflight failed for {url}: {exc}") from exc
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as exc:
        raise BundleError("Ollama /api/tags returned non-JSON data") from exc
    models = payload.get("models")
    if not isinstance(models, list):
        raise BundleError("Ollama /api/tags response has no models list")
    matches = [item for item in models if isinstance(item, dict) and item.get("name") == model]
    if len(matches) != 1:
        raise BundleError(f"Ollama model {model!r} is not available exactly once")
    item = matches[0]
    return {
        "host": host,
        "model": model,
        "name": item.get("name"),
        "digest": item.get("digest"),
        "size": item.get("size"),
        "modified_at": item.get("modified_at"),
        "tags_sha256": _sha256_bytes(body),
    }


def _loopback_ollama_host(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise BundleError("--ollama-host must use http on localhost, 127.0.0.1, or [::1]")
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise BundleError("--ollama-host must not include credentials, a query, fragment, or path")
    if not parsed.netloc:
        raise BundleError("--ollama-host must include a loopback host")
    return value.rstrip("/")


def _configure_gmit_paths(gmit_root: str) -> Path:
    root = Path(gmit_root).resolve(strict=True)
    source = root / "src"
    benchmark = root / "benchmark_llm.py"
    if not source.is_dir() or not benchmark.is_file():
        raise BundleError("GMIT root must contain src/ and benchmark_llm.py")
    for item in (str(root), str(source)):
        if item not in sys.path:
            sys.path.insert(0, item)
    return root


def _gmit_revision(root: Path) -> str:
    completed = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
        timeout=5,
    )
    return completed.stdout.strip()


def _episode_worker(payload: dict[str, Any], result_queue: Any) -> None:
    """Child process: isolate unbounded third-party/model calls from the parent."""
    try:
        os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
        sys.dont_write_bytecode = True
        root = _configure_gmit_paths(payload["gmit_root"])
        import numpy as np
        import benchmark_llm  # type: ignore
        from gmit.agents.taxonomy import PatientStyle, PatientType  # type: ignore
        from gmit.utils.llm_router import LLMRouter  # type: ignore

        random.seed(payload["seed"])
        np.random.seed(payload["seed"])

        class BoundedOllamaRouter(LLMRouter):
            """Pass bounded native-Ollama options without changing GMIT behavior."""

            def chat(self, messages: list[dict[str, str]], temperature: float = 0.7, **kwargs: Any) -> str:
                for key, value in OLLAMA_OPTIONS.items():
                    kwargs.setdefault(key, value)
                kwargs.setdefault("seed", payload["seed"])
                return super().chat(messages, temperature=temperature, **kwargs)

        llm_patients = BoundedOllamaRouter(provider="ollama", model=MODEL_ID, base_url=payload["ollama_host"])
        llm_therapist = BoundedOllamaRouter(provider="ollama", model=MODEL_ID, base_url=payload["ollama_host"])
        llm_stage = BoundedOllamaRouter(provider="ollama", model=MODEL_ID, base_url=payload["ollama_host"])
        rows = benchmark_llm.run_round_robin_episode(
            episode_id=0,
            theme=payload["theme"],
            turns=payload["patient_turns"],
            patient_a_type=PatientType[payload["patient_a_type"]],
            patient_b_type=PatientType[payload["patient_b_type"]],
            patient_style=PatientStyle.GMIT,
            llm_patients=llm_patients,
            llm_therapist=llm_therapist,
            llm_stage=llm_stage,
            prompt_dir=None,
            patient_fewshot_k=None,
            use_readiness=False,
            open_persona_version="v2",
            silent=True,
        )
        result_queue.put(
            {
                "rows": rows,
                "benchmark_sha256": _sha256_bytes((root / "benchmark_llm.py").read_bytes()),
                "gmit_revision": _gmit_revision(root),
            }
        )
    except BaseException as exc:  # Deliver a bounded, non-secret error to the parent.
        result_queue.put({"error": f"{type(exc).__name__}: {exc}"})


def _run_bounded_episode(payload: dict[str, Any], timeout_seconds: float) -> dict[str, Any]:
    context = mp.get_context("spawn")
    queue = context.Queue()
    process = context.Process(target=_episode_worker, args=(payload, queue), daemon=True)
    started = time.monotonic()
    process.start()
    try:
        # Drain before join: multiprocessing queues flush through a pipe and
        # joining first can deadlock when a returned episode exceeds its buffer.
        result = queue.get(timeout=timeout_seconds)
    except Empty as exc:
        process.terminate()
        process.join(10)
        raise BundleError(f"Pure episode exited {process.exitcode} without a result before {timeout_seconds:g} seconds") from exc
    remaining = max(0.0, timeout_seconds - (time.monotonic() - started))
    process.join(remaining)
    if process.is_alive():
        process.terminate()
        process.join(10)
        raise BundleError(f"Pure episode worker did not exit within {timeout_seconds:g} seconds")
    if not isinstance(result, dict):
        raise BundleError("Pure episode returned an invalid worker result")
    if "error" in result:
        raise BundleError(f"Pure episode failed: {result['error']}")
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gmit-root", type=Path, default=os.environ.get("GMIT_ROOT"),
                        help="Private GMIT checkout; defaults to GMIT_ROOT when set")
    parser.add_argument("--ollama-host", default="http://127.0.0.1:11434")
    parser.add_argument("--patient-turns", type=int, default=4)
    parser.add_argument("--theme", default="Reducing red meat")
    parser.add_argument("--patient-a-type", default="OPEN")
    parser.add_argument("--patient-b-type", default="RECEPTIVE")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--session-id", default="pure-red-meat-ollama-v1")
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--preflight-timeout-seconds", type=float, default=5.0)
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--output-dir", type=Path)
    return parser


def _validate_args(args: argparse.Namespace) -> None:
    if args.gmit_root is None:
        raise BundleError("set --gmit-root or GMIT_ROOT to the private GMIT checkout")
    if args.patient_turns <= 0 or args.patient_turns % 2 or args.patient_turns > 8:
        raise BundleError("--patient-turns must be an even integer from 2 through 8")
    if args.timeout_seconds <= 0 or args.preflight_timeout_seconds <= 0:
        raise BundleError("timeouts must be positive")
    if not args.theme.strip():
        raise BundleError("--theme must not be empty")
    args.ollama_host = _loopback_ollama_host(args.ollama_host)
    _configure_gmit_paths(str(args.gmit_root))


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        _validate_args(args)
        identity = _read_ollama_identity(args.ollama_host, MODEL_ID, args.preflight_timeout_seconds)
        if args.preflight_only:
            print(json.dumps({"preflight": "ok", "service": identity}, ensure_ascii=False))
            return 0
        session_id = normalize_session_id(args.session_id)
        output_dir = private_output_path(args.output_dir or Path(__file__).resolve().parent / "local" / "pure" / session_id)
        if output_dir.exists():
            raise BundleError(f"refusing to overwrite existing output directory: {output_dir}")
        payload = {
            "gmit_root": str(args.gmit_root),
            "ollama_host": args.ollama_host,
            "patient_turns": args.patient_turns,
            "theme": args.theme,
            "patient_a_type": args.patient_a_type,
            "patient_b_type": args.patient_b_type,
            "seed": args.seed,
        }
        result = _run_bounded_episode(payload, args.timeout_seconds)
        rows = result.get("rows")
        if not isinstance(rows, list):
            raise BundleError("Pure episode returned no dialogue rows")
        expected_rows = 3 * (args.patient_turns // 2) + 1
        if len(rows) != expected_rows:
            raise BundleError(f"Pure episode returned {len(rows)} rows, expected {expected_rows}")
        events = canonicalize_rows(rows, session_id=session_id, condition="pure")
        expected_actors = ["facilitator"]
        for _ in range(args.patient_turns // 2):
            expected_actors.extend(("patient-a", "patient-b", "facilitator"))
        if [event["actor_id"] for event in events] != expected_actors:
            raise BundleError("Pure episode violated the expected facilitator/patient-a/patient-b order")
        result_bundle = write_bundle(
            events,
            output_dir=output_dir,
            provenance="gmit-generated",
            floor_mode="recorded",
            input_sha256=None,
            title="GMIT Pure-LLM recorded episode",
            extra_receipt={
                "generator": "benchmark_llm.run_round_robin_episode",
                "benchmark_sha256": result.get("benchmark_sha256"),
                "gmit_revision": result.get("gmit_revision"),
                "service": identity,
                "model": MODEL_ID,
                "theme": args.theme,
                "patient_turns": args.patient_turns,
                "patient_a_type": args.patient_a_type,
                "patient_b_type": args.patient_b_type,
                "seed": args.seed,
                "timeout_seconds": args.timeout_seconds,
                "ollama_options": {**OLLAMA_OPTIONS, "seed": args.seed},
                "role_temperatures": {"therapist_act": 0.4, "therapist_nlg": 0.7, "stage": 0.2, "patient": "GMIT-owned adaptive"},
            },
        )
    except (BundleError, OSError, KeyError, ValueError) as exc:
        print(f"pure run failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"events": str(result_bundle.events_path), "replay": str(result_bundle.replay_path), "receipt": str(result_bundle.receipt_path), "event_count": result_bundle.event_count}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
