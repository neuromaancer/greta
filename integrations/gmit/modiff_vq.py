"""Bounded local readiness check for the Greta 2.1 MODIFF-VQ face model.

This module loads the published VQ-VAE/E3 architecture from a local MODIFFVQ
model directory and runs deterministic CPU inference.  It deliberately opens no
network ports, camera, microphone, or Greta process.  Its input is a caller
provided, 17-AU history; a GMIT transcript alone is not a valid model input.

The first GMIT integration only exposes AU01, AU02, and AU04.  Those upper-face
channels are kept separate from Greta's speech lips, blinking, and gaze control.
An output from this engineering check is not evidence that virtual-avatar AU
inputs are in the model's training distribution.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from typing import Any, Mapping, Sequence


AU_COLUMNS = (
    "AU01_r", "AU02_r", "AU04_r", "AU05_r", "AU06_r", "AU07_r", "AU09_r",
    "AU10_r", "AU12_r", "AU14_r", "AU15_r", "AU17_r", "AU20_r", "AU23_r",
    "AU25_r", "AU26_r", "AU45_r",
)
SAFE_UPPER_FACE_AUS = ("AU01_r", "AU02_r", "AU04_r")
WINDOW = 8
T_CHUNK = 18
N_AUS = len(AU_COLUMNS)


class ModiffVQError(RuntimeError):
    """The local model cannot be loaded or given a safe bounded input."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def model_paths(model_root: Path) -> dict[str, Path]:
    root = model_root.expanduser().resolve(strict=True)
    # Accept either the public MODIFFVQ directory or its nested model directory.
    # The former is friendlier for launch scripts while the latter is useful for
    # standalone diagnostics.
    if (root / "model").is_dir() and (root / "RealTimePipeVQ.py").is_file():
        root = root / "model"
    required = {
        "vqvae": root / "vqvae_model.pt",
        "predictor": root / "predictor_model.pt",
        "norm_stats": root / "norm_stats.npy",
        "pipeline_source": root / "MODIF_baseline.py",
        "encoders_source": root / "encoders.py",
        "vqvae_source": root / "vqvae.py",
    }
    missing = [name for name, path in required.items() if not path.is_file()]
    if missing:
        raise ModiffVQError(f"MODIFF-VQ model root is missing: {', '.join(missing)}")
    if root.name != "model" or not (root.parent / "RealTimePipeVQ.py").is_file():
        raise ModiffVQError("--model-root must be the model/ directory below MODIFFVQ")
    return required


def _import_model_classes(model_root: Path) -> tuple[Any, Any, Any, Any, Any]:
    package_parent = str(model_root.parent)
    existing = sys.modules.get("model")
    existing_paths = [Path(item).resolve() for item in getattr(existing, "__path__", ())] if existing else []
    if existing is not None and existing_paths != [model_root.resolve()]:
        # This process may have already imported a different MODIFF bundle.  A
        # strict preflight must never silently test those source files instead.
        for name in tuple(sys.modules):
            if name == "model" or name.startswith("model."):
                del sys.modules[name]
    if package_parent not in sys.path:
        sys.path.insert(0, package_parent)
    importlib.invalidate_caches()
    try:
        baseline = importlib.import_module("model.MODIF_baseline")
        encoders = importlib.import_module("model.encoders")
        vqvae = importlib.import_module("model.vqvae")
    except Exception as exc:  # pragma: no cover - depends on local torch install
        raise ModiffVQError(f"cannot import local MODIFF-VQ architecture: {exc}") from exc
    expected = model_root.resolve()
    for module in (baseline, encoders, vqvae):
        if Path(module.__file__).resolve().parent != expected:
            raise ModiffVQError(f"MODIFF-VQ import resolved outside requested model root: {module.__file__}")
    return (
        baseline.AllPipeline,
        encoders.AUsEncoder,
        encoders.AudioEncoder,
        encoders.DialogActEncoder,
        encoders.SinusoidalEmbeddings,
        vqvae.VQVAE,
    )


def _checkpoint_state(torch: Any, path: Path) -> Mapping[str, Any]:
    # The locally supplied predictor has a NumPy scalar in its metadata.  Keep
    # weights_only enabled and allow only that concrete non-executable type.
    import numpy as np
    try:
        with torch.serialization.safe_globals([
            np._core.multiarray.scalar,
            np.dtype,
            type(np.dtype("float64")),
            type(np.dtype("float32")),
        ]):
            payload = torch.load(path, map_location="cpu", weights_only=True)
    except Exception as exc:
        raise ModiffVQError(f"cannot safely load checkpoint {path.name}: {exc}") from exc
    if not isinstance(payload, Mapping) or not isinstance(payload.get("model_state"), Mapping):
        raise ModiffVQError(f"checkpoint {path.name} does not contain a model_state mapping")
    return payload


def load_model(model_root: Path, *, torch_threads: int = 2) -> tuple[Any, Mapping[str, Any]]:
    """Strictly load the existing local E3 configuration on CPU."""
    paths = model_paths(model_root)
    try:
        import numpy as np
        import torch
    except ImportError as exc:  # pragma: no cover - machine-specific dependency
        raise ModiffVQError(f"MODIFF-VQ requires local numpy and torch: {exc}") from exc
    if torch_threads < 1:
        raise ModiffVQError("torch_threads must be positive")
    torch.set_num_threads(torch_threads)
    classes = _import_model_classes(paths["vqvae"].parent)
    AllPipeline, AUsEncoder, AudioEncoder, DialogActEncoder, SinusoidalEmbeddings, VQVAE = classes
    vqvae = VQVAE(
        in_features=N_AUS, d_model=128, dz=128, K=64, T=T_CHUNK,
        n_heads=4, num_layers=3, dropout=0.1, beta=0.25,
    )
    vqvae_checkpoint = _checkpoint_state(torch, paths["vqvae"])
    vqvae.load_state_dict(vqvae_checkpoint["model_state"], strict=True)
    model = AllPipeline(
        client_aus_encoder=AUsEncoder(in_features=N_AUS, d_model=128),
        therapist_aus_encoder=AUsEncoder(in_features=N_AUS, d_model=128),
        pos_encoder=SinusoidalEmbeddings(d_model=128),
        dialog_act_encoder=DialogActEncoder(d_model=128),
        audio_encoder=AudioEncoder(in_features=1024, d_model=128, num_layers=4),
        W=WINDOW, d_model=128, n_heads=8, num_layers_transformer=4, dropout=0.3,
        vqvae=vqvae, K=64, T_down=9, use_therapist_prev=False, use_vqvae_enc=False,
    )
    predictor_checkpoint = _checkpoint_state(torch, paths["predictor"])
    embedded_vqvae = {
        key.removeprefix("vqvae."): value
        for key, value in predictor_checkpoint["model_state"].items()
        if key.startswith("vqvae.")
    }
    standalone_vqvae = vqvae_checkpoint["model_state"]
    if embedded_vqvae:
        embedded_matches = (
            set(embedded_vqvae) == set(standalone_vqvae)
            and all(torch.equal(embedded_vqvae[key], standalone_vqvae[key]) for key in standalone_vqvae)
        )
    else:
        embedded_matches = None
    model.load_state_dict(predictor_checkpoint["model_state"], strict=True)
    model.eval()
    stats = np.load(paths["norm_stats"], allow_pickle=True)
    if not hasattr(stats, "item"):
        raise ModiffVQError("norm_stats.npy is not a mapping")
    stats = stats.item()
    if not isinstance(stats, Mapping) or set(("min_x", "max_x", "min_y", "max_y")) - set(stats):
        raise ModiffVQError("norm_stats.npy lacks min_x, max_x, min_y, or max_y")
    for name in ("min_x", "max_x", "min_y", "max_y"):
        value = np.asarray(stats[name], dtype=np.float32)
        if value.shape != (N_AUS,) or not np.isfinite(value).all():
            raise ModiffVQError(f"norm_stats {name} must be 17 finite values")
    if (np.asarray(stats["max_x"]) < np.asarray(stats["min_x"])).any() or (
        np.asarray(stats["max_y"]) < np.asarray(stats["min_y"])
    ).any():
        raise ModiffVQError("norm_stats maximum values must not be below minimum values")
    return model, {
        "torch": torch,
        "numpy": np,
        "paths": paths,
        "stats": stats,
        "predictor": predictor_checkpoint,
        "predictor_embeds_vqvae": bool(embedded_vqvae),
        "embedded_vqvae_matches_standalone": embedded_matches,
    }


def default_dialog_acts(torch: Any) -> dict[str, Any]:
    """The fixed neutral/reflection condition used by RealTimePipeVQ.py."""
    return {
        "client_talk_type": torch.full((1, WINDOW), 2, dtype=torch.float32),
        "reflection_subtype": torch.full((1, WINDOW), 1, dtype=torch.float32),
        "question_subtype": torch.zeros((1, WINDOW), dtype=torch.float32),
        "main_therapist_behaviour": torch.full((1, WINDOW), 1, dtype=torch.float32),
        "therapist_input_subtype": torch.zeros((1, WINDOW), dtype=torch.float32),
        "silences": torch.zeros((1, WINDOW, 4), dtype=torch.float32),
    }


def safe_upper_face(rows: Any, numpy: Any) -> Any:
    """Return only brows for a later overlay; lips, blink, and gaze stay owned elsewhere."""
    values = numpy.asarray(rows, dtype=numpy.float32)
    if values.shape[-1] != N_AUS:
        raise ModiffVQError(f"expected final AU dimension {N_AUS}, got {values.shape}")
    if not numpy.isfinite(values).all():
        raise ModiffVQError("model output contains non-finite values")
    output = numpy.zeros_like(values)
    for name in SAFE_UPPER_FACE_AUS:
        index = AU_COLUMNS.index(name)
        output[..., index] = numpy.clip(values[..., index], 0.0, 5.0)
    return output


def predict_full_face(model: Any, context: Mapping[str, Any], client_aus: Any) -> Any:
    """Predict one bounded 18x17 response; callers must filter before Greta output."""
    torch = context["torch"]
    numpy = context["numpy"]
    stats = context["stats"]
    incoming = numpy.asarray(client_aus, dtype=numpy.float32)
    if incoming.shape != (WINDOW, T_CHUNK, N_AUS):
        raise ModiffVQError(f"client AU history must have shape {(WINDOW, T_CHUNK, N_AUS)}, got {incoming.shape}")
    if not numpy.isfinite(incoming).all() or (incoming < 0).any():
        raise ModiffVQError("client AU history must be finite and non-negative")
    normal = (incoming - stats["min_x"]) / (stats["max_x"] - stats["min_x"] + 1e-8)
    source = torch.from_numpy(normal).unsqueeze(0)
    with torch.no_grad():
        predicted = model.predict_aus(
            x_au_client=source,
            x_au_therapist_prev=None,
            x_dialog_acts=default_dialog_acts(torch),
            x_audio_client=None,
            x_audio_therapist=None,
            is_new_session=None,
            sample=False,
        )
    denormalized = predicted.squeeze(0).cpu().numpy()
    denormalized = denormalized * (stats["max_y"] - stats["min_y"] + 1e-8) + stats["min_y"]
    if not numpy.isfinite(denormalized).all():
        raise ModiffVQError("model output contains non-finite values")
    return denormalized


def predict_safe_upper_face(model: Any, context: Mapping[str, Any], client_aus: Any) -> Any:
    """Predict deterministic upper-face-only response from 8x18x17 AU history."""
    return safe_upper_face(predict_full_face(model, context, client_aus), context["numpy"])


def _rss_mebibytes() -> float | None:
    try:
        import resource
    except ImportError:  # pragma: no cover - Windows Python does not provide resource
        return None
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return round(rss / 1024, 2) if sys.platform != "darwin" else round(rss / (1024 * 1024), 2)


def _json_number(value: Any) -> int | float | None:
    if value is None:
        return None
    return value.item() if hasattr(value, "item") else value


def input_sensitivity(model: Any, context: Mapping[str, Any]) -> dict[str, Any]:
    """Compare bounded synthetic AU contexts without claiming input validity."""
    np = context["numpy"]
    base = np.full((WINDOW, T_CHUNK, N_AUS), 0.1, dtype=np.float32)
    cases = {"neutral": base.copy()}
    brow12 = base.copy()
    brow12[-1, :, AU_COLUMNS.index("AU01_r")] = 3.0
    brow12[-1, :, AU_COLUMNS.index("AU02_r")] = 3.0
    cases["brow_1_2_pulse"] = brow12
    brow4 = base.copy()
    brow4[-1, :, AU_COLUMNS.index("AU04_r")] = 3.0
    cases["brow_4_pulse"] = brow4
    blink = base.copy()
    blink[-1, :, AU_COLUMNS.index("AU45_r")] = 4.0
    cases["blink_45_pulse"] = blink
    rng = np.random.default_rng(20261102)
    cases["all_au_seeded"] = rng.uniform(0.0, 1.0, size=(WINDOW, T_CHUNK, N_AUS)).astype(np.float32)
    full = {name: predict_full_face(model, context, values) for name, values in cases.items()}
    neutral = full["neutral"]
    reports: dict[str, Any] = {}
    for name, values in full.items():
        delta = np.abs(values - neutral)
        brows = safe_upper_face(values, np)
        neutral_brows = safe_upper_face(neutral, np)
        reports[name] = {
            "full_exact_match_to_neutral": bool(np.array_equal(values, neutral)),
            "full_linf_delta_to_neutral": float(delta.max()),
            "upper_face_linf_delta_to_neutral": float(np.abs(brows - neutral_brows).max()),
            "full_finite": bool(np.isfinite(values).all()),
        }
    return {
        "seed": 20261102,
        "raw_input_scale": "[0,5]",
        "cases": reports,
        "limitation": "Sensitivity only checks that the local checkpoint changes under synthetic input; it does not validate virtual-avatar AU semantics.",
    }


def preflight(model_root: Path, *, repeats: int, torch_threads: int) -> dict[str, Any]:
    if repeats < 1 or repeats > 50:
        raise ModiffVQError("repeats must be in [1, 50]")
    loaded_at = time.perf_counter()
    model, context = load_model(model_root, torch_threads=torch_threads)
    load_ms = round((time.perf_counter() - loaded_at) * 1000, 2)
    np = context["numpy"]
    # A deterministic non-zero synthetic tensor proves local loading and shape only.
    # It is explicitly not a substitute for recorded OpenFace client features.
    synthetic = np.full((WINDOW, T_CHUNK, N_AUS), 0.1, dtype=np.float32)
    warmup = predict_safe_upper_face(model, context, synthetic)
    started = time.perf_counter()
    for _ in range(repeats):
        result = predict_safe_upper_face(model, context, synthetic)
    elapsed_ms = (time.perf_counter() - started) * 1000
    paths = context["paths"]
    return {
        "preflight": "ok",
        "mode": "local-engineering-readiness-only",
        "device": "cpu",
        "torch_threads": torch_threads,
        "repeats": repeats,
        "load_ms": load_ms,
        "mean_inference_ms": round(elapsed_ms / repeats, 2),
        "output_shape": list(result.shape),
        "safe_output_aus": list(SAFE_UPPER_FACE_AUS),
        "suppressed_aus": [name for name in AU_COLUMNS if name not in SAFE_UPPER_FACE_AUS],
        "finite_output": bool(np.isfinite(result).all()),
        "deterministic_warmup_match": bool(np.array_equal(warmup, result)),
        "rss_mebibytes": _rss_mebibytes(),
        "artifacts": {name: {"bytes": path.stat().st_size, "sha256": sha256_file(path)} for name, path in paths.items()},
        "predictor_epoch": _json_number(context["predictor"].get("epoch")),
        "predictor_val_pearson": _json_number(context["predictor"].get("val_pearson")),
        "predictor_embeds_vqvae": context["predictor_embeds_vqvae"],
        "embedded_vqvae_matches_standalone": context["embedded_vqvae_matches_standalone"],
        "input_contract": "8x18x17 recorded OpenFace-style client AU history",
        "limitation": "Synthetic input verifies local execution only; it does not validate virtual-avatar AU adaptation.",
        "input_sensitivity": input_sensitivity(model, context),
    }


MAILBOX_VERSION = "gmit-modiff-vq/1"


def _atomic_text(path: Path, content: str) -> None:
    """Atomically replace a mailbox file in its existing local directory."""
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent, text=True)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def _request_header(fields: Sequence[str]) -> tuple[str, str, str, int, int]:
    if len(fields) != 5 or fields[0] != MAILBOX_VERSION:
        raise ModiffVQError("request header must be gmit-modiff-vq/1, id, source actor, epoch, and input end frame")
    request_id, source_actor = fields[1], fields[2]
    if not request_id or source_actor not in {"patient-a", "patient-b"}:
        raise ModiffVQError("request requires a non-empty id and source actor patient-a or patient-b")
    try:
        epoch, input_end_frame = int(fields[3]), int(fields[4])
    except ValueError as exc:
        raise ModiffVQError("request epoch and input end frame must be integers") from exc
    if epoch < 0 or input_end_frame < 0:
        raise ModiffVQError("request epoch and input end frame must be non-negative")
    return request_id, source_actor, fields[0], epoch, input_end_frame


def read_request(path: Path, numpy: Any) -> tuple[tuple[str, str, int, int], Any]:
    """Read one complete request; malformed input is never treated as an old request."""
    lines = path.read_text(encoding="utf-8").splitlines()
    if len(lines) != 1 + WINDOW * T_CHUNK:
        raise ModiffVQError(f"request must contain one header and {WINDOW * T_CHUNK} AU rows")
    request_id, source_actor, _version, epoch, input_end_frame = _request_header(lines[0].split("\t"))
    rows: list[list[float]] = []
    for number, line in enumerate(lines[1:], start=2):
        fields = line.split("\t")
        if len(fields) != N_AUS:
            raise ModiffVQError(f"request AU row {number} must have {N_AUS} fields")
        try:
            rows.append([float(value) for value in fields])
        except ValueError as exc:
            raise ModiffVQError(f"request AU row {number} has a non-numeric value") from exc
    values = numpy.asarray(rows, dtype=numpy.float32).reshape(WINDOW, T_CHUNK, N_AUS)
    if not numpy.isfinite(values).all() or (values < 0).any() or (values > 5).any():
        raise ModiffVQError("request AU values must be finite raw OpenFace-scale values in [0, 5]")
    return (request_id, source_actor, epoch, input_end_frame), values


def mailbox_response(header: tuple[str, str, int, int], safe_output: Any, numpy: Any) -> str:
    """Produce an 18-frame brow-only reply in Greta's normalized 0..1 AU scale."""
    request_id, source_actor, epoch, input_end_frame = header
    values = numpy.asarray(safe_output, dtype=numpy.float32)
    if values.shape != (T_CHUNK, N_AUS) or not numpy.isfinite(values).all():
        raise ModiffVQError("safe output must be finite 18x17 AU frames")
    indices = [AU_COLUMNS.index(name) for name in SAFE_UPPER_FACE_AUS]
    brows = numpy.clip(values[:, indices] / 5.0 * 0.3, 0.0, 0.25)
    output = ["\t".join((MAILBOX_VERSION, request_id, source_actor, str(epoch), str(input_end_frame)))]
    output.extend("\t".join(f"{value:.8f}" for value in row) for row in brows)
    return "\n".join(output) + "\n"


def _ready_payload(context: Mapping[str, Any], *, status: str, detail: str | None = None) -> str:
    paths = context["paths"]
    payload: dict[str, Any] = {
        "protocol": MAILBOX_VERSION,
        "status": status,
        "device": "cpu",
        "input_contract": "8x18x17 raw OpenFace-scale [0,5] AU history",
        "output_contract": "18x3 AU01/AU02/AU04 Greta-normalized values; gain=0.3, clip=[0,0.25]",
        "source_actors": ["patient-a", "patient-b"],
        "predictor_embeds_vqvae": context["predictor_embeds_vqvae"],
        "embedded_vqvae_matches_standalone": context["embedded_vqvae_matches_standalone"],
        "artifacts": {name: sha256_file(path) for name, path in paths.items()},
        "limitation": "This local mailbox is an engineering diagnostic, not validated virtual-avatar or OpenFace adaptation.",
    }
    if detail:
        payload["detail"] = detail
    return json.dumps(payload, sort_keys=True) + "\n"


def serve_mailbox(model_root: Path, serve_dir: Path, *, max_seconds: float, torch_threads: int) -> int:
    """Serve one local file mailbox without sockets, audio, cameras, or model downloads."""
    if max_seconds <= 0 or max_seconds > 3600:
        raise ModiffVQError("max_seconds must be in (0, 3600]")
    mailbox = serve_dir.expanduser().resolve()
    mailbox.mkdir(parents=True, exist_ok=True)
    model, context = load_model(model_root, torch_threads=torch_threads)
    ready_path = mailbox / "ready.json"
    request_path = mailbox / "request.tsv"
    response_path = mailbox / "response.tsv"
    error_path = mailbox / "error.json"
    stop_path = mailbox / "stop"
    _atomic_text(ready_path, _ready_payload(context, status="ready"))
    seen: set[str] = set()
    started = time.monotonic()
    try:
        while time.monotonic() - started < max_seconds:
            if stop_path.exists():
                _atomic_text(ready_path, _ready_payload(context, status="stopped", detail="stop file observed"))
                return 0
            if not request_path.is_file():
                time.sleep(0.02)
                continue
            try:
                header, history = read_request(request_path, context["numpy"])
                if header[0] in seen:
                    raise ModiffVQError(f"request id already processed: {header[0]}")
                prediction = predict_safe_upper_face(model, context, history)
                # The Java bridge waits for this removal before it may publish a
                # new request.  Removing before publishing avoids deleting a
                # next request that was atomically renamed in too early.
                request_path.unlink()
                _atomic_text(response_path, mailbox_response(header, prediction, context["numpy"]))
                seen.add(header[0])
            except Exception as exc:
                _atomic_text(error_path, json.dumps({"protocol": MAILBOX_VERSION, "error": str(exc)}, sort_keys=True) + "\n")
                _atomic_text(ready_path, _ready_payload(context, status="error", detail=str(exc)))
                return 2
        _atomic_text(ready_path, _ready_payload(context, status="stopped", detail="max_seconds elapsed"))
        return 0
    finally:
        # A reader must not treat an earlier ready state as an active service after exit.
        if ready_path.exists():
            current = json.loads(ready_path.read_text(encoding="utf-8"))
            if current.get("status") == "ready":
                _atomic_text(ready_path, _ready_payload(context, status="stopped", detail="server exited"))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-root", required=True, type=Path, help="Local MODIFFVQ directory or its model/ directory.")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight-only", action="store_true", help="Run a bounded strict-load CPU readiness check.")
    mode.add_argument("--serve-dir", type=Path, help="Run the local facilitator-only file mailbox in this directory.")
    parser.add_argument("--repeats", type=int, default=10, help="Timed deterministic inferences (1-50).")
    parser.add_argument("--torch-threads", type=int, default=2, help="CPU threads used by PyTorch.")
    parser.add_argument("--max-seconds", type=float, default=600, help="Maximum mailbox lifetime in seconds (1-3600).")
    parser.add_argument("--report", type=Path, help="Optional local JSON receipt path for preflight output.")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.preflight_only:
        result = preflight(args.model_root, repeats=args.repeats, torch_threads=args.torch_threads)
        rendered = json.dumps(result, sort_keys=True)
        if args.report:
            args.report.expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
            _atomic_text(args.report.expanduser().resolve(), rendered + "\n")
        print(rendered)
        return 0
    return serve_mailbox(args.model_root, args.serve_dir, max_seconds=args.max_seconds, torch_threads=args.torch_threads)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ModiffVQError as exc:
        print(f"MODIFF-VQ preflight failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
