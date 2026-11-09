"""Run one bounded standalone Pure-LLM episode through native Ollama."""

from __future__ import annotations

import argparse
import hashlib
import json
import multiprocessing as mp
import os
from queue import Empty
import random
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from pathlib import Path
from typing import Any

from .event_bundle import BundleError, canonicalize_rows, normalize_session_id, write_bundle
from .importer import private_output_path
from .pure import OllamaChatClient, PureGenerationError, load_fewshot_asset, package_provenance, run_round_robin_episode
from .pure.taxonomy import PatientStyle, PatientType


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


def _episode_worker(payload: dict[str, Any], result_queue: Any) -> None:
    """Child process: isolate network/model calls from the parent."""
    try:
        os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
        sys.dont_write_bytecode = True
        random.seed(payload["seed"])
        asset_pairs, asset_receipt = load_fewshot_asset(payload["fewshot_asset"])
        client_options = {**OLLAMA_OPTIONS, "seed": payload["seed"]}
        def client() -> OllamaChatClient:
            return OllamaChatClient(
                base_url=payload["ollama_host"],
                model=MODEL_ID,
                base_options=client_options,
                timeout_seconds=payload["request_timeout_seconds"],
            )
        rows = run_round_robin_episode(
            episode_id=0,
            theme=payload["theme"],
            turns=payload["patient_turns"],
            patient_a_type=PatientType[payload["patient_a_type"]],
            patient_b_type=PatientType[payload["patient_b_type"]],
            patient_style=PatientStyle.GMIT,
            llm_patients=client(),
            llm_therapist=client(),
            llm_stage=client(),
            prompt_dir=None,
            patient_fewshot=asset_pairs,
            use_readiness=False,
            open_persona_version="v2",
            silent=True,
        )
        result_queue.put({"rows": rows, "fewshot_asset": asset_receipt, "pure": package_provenance()})
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
    parser.add_argument("--ollama-host", default="http://127.0.0.1:11434")
    parser.add_argument("--patient-turns", type=int, default=4)
    parser.add_argument("--theme", default="Reducing red meat")
    parser.add_argument("--patient-a-type", default="OPEN")
    parser.add_argument("--patient-b-type", default="RECEPTIVE")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--session-id", default="pure-red-meat-ollama-v1")
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--preflight-timeout-seconds", type=float, default=5.0)
    parser.add_argument("--request-timeout-seconds", type=float, default=90.0)
    parser.add_argument("--fewshot-asset", type=Path, default=None,
                        help="Private JSON asset; defaults to integrations/gmit/local/pure-assets/patient-fewshot.json")
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--output-dir", type=Path)
    return parser


def _validate_args(args: argparse.Namespace) -> None:
    if args.patient_turns <= 0 or args.patient_turns % 2 or args.patient_turns > 8:
        raise BundleError("--patient-turns must be an even integer from 2 through 8")
    if args.timeout_seconds <= 0 or args.preflight_timeout_seconds <= 0 or args.request_timeout_seconds <= 0:
        raise BundleError("timeouts must be positive")
    if not args.theme.strip():
        raise BundleError("--theme must not be empty")
    args.ollama_host = _loopback_ollama_host(args.ollama_host)
    # Validate local, ignored examples before any model-service request.  The
    # content remains private; only metadata enters a generated receipt.
    _, args.fewshot_asset_receipt = load_fewshot_asset(args.fewshot_asset)


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
            "ollama_host": args.ollama_host,
            "patient_turns": args.patient_turns,
            "theme": args.theme,
            "patient_a_type": args.patient_a_type,
            "patient_b_type": args.patient_b_type,
            "seed": args.seed,
            "fewshot_asset": str(args.fewshot_asset) if args.fewshot_asset is not None else None,
            "request_timeout_seconds": args.request_timeout_seconds,
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
                "generator": "integrations.gmit.pure.run_round_robin_episode",
                "pure": result.get("pure"),
                "fewshot_asset": result.get("fewshot_asset", args.fewshot_asset_receipt),
                "service": identity,
                "model": MODEL_ID,
                "theme": args.theme,
                "patient_turns": args.patient_turns,
                "patient_a_type": args.patient_a_type,
                "patient_b_type": args.patient_b_type,
                "seed": args.seed,
                "timeout_seconds": args.timeout_seconds,
                "request_timeout_seconds": args.request_timeout_seconds,
                "ollama_options": {**OLLAMA_OPTIONS, "seed": args.seed},
                "role_temperatures": {"therapist_act": 0.4, "therapist_nlg": 0.7, "stage": 0.2, "patient": "0.7 to 1.0 across five bounded retries"},
            },
        )
    except (BundleError, PureGenerationError, OSError, KeyError, ValueError) as exc:
        print(f"pure run failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"events": str(result_bundle.events_path), "replay": str(result_bundle.replay_path), "receipt": str(result_bundle.receipt_path), "event_count": result_bundle.event_count}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
