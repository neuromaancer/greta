"""Encode a completed, locally captured Greta replay into an MP4.

Frames already contain Java/Graphics2D subtitles.  This tool only validates the
capture contract, preserves its measured timing through a concat timeline, and
muxes scheduled PCM WAV files.  It never starts Greta, a model, or a browser.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import tempfile
import time
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


class VideoExportError(ValueError):
    """Raised when a capture cannot be exported without guessing."""


@dataclass(frozen=True)
class Frame:
    path: Path
    timer_ms: int


@dataclass(frozen=True)
class Audio:
    event_id: str
    actor_id: str
    path: Path
    start_ms: int
    end_ms: int
    pcm_duration_ms: float


@dataclass(frozen=True)
class Capture:
    run_dir: Path
    manifest_path: Path
    subtitles_path: Path
    status: str
    width: int
    height: int
    target_fps: int
    origin_ms: int
    end_ms: int
    frames: tuple[Frame, ...]
    audio: tuple[Audio, ...]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _require_int(mapping: dict[str, Any], field: str) -> int:
    value = mapping.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise VideoExportError(f"{field} must be an integer")
    return value


def _safe_file(root: Path, value: object, *, field: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise VideoExportError(f"{field} must be a non-empty relative path")
    raw = Path(value)
    if raw.is_absolute() or ".." in raw.parts:
        raise VideoExportError(f"{field} must not escape the run directory")
    candidate = root / raw
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as exc:
        raise VideoExportError(f"{field} is missing: {value}") from exc
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise VideoExportError(f"{field} symlink escapes the run directory") from exc
    current = root
    for part in raw.parts:
        current = current / part
        if current.is_symlink():
            raise VideoExportError(f"{field} must not use symlinks")
    if not resolved.is_file():
        raise VideoExportError(f"{field} is not a regular file")
    return resolved


def _wav_duration_ms(path: Path) -> float:
    try:
        with wave.open(str(path), "rb") as handle:
            if handle.getcomptype() != "NONE":
                raise VideoExportError(f"audio file is not PCM WAV: {path.name}")
            if handle.getframerate() <= 0 or handle.getnchannels() <= 0 or handle.getsampwidth() <= 0:
                raise VideoExportError(f"audio file has invalid PCM metadata: {path.name}")
            return handle.getnframes() * 1000.0 / handle.getframerate()
    except (wave.Error, OSError) as exc:
        raise VideoExportError(f"audio file is not a readable WAV: {path.name}") from exc


def _load_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise VideoExportError(f"{label} is not readable JSON") from exc


def load_capture(run_dir: Path | str, *, allow_partial: bool = False) -> Capture:
    """Validate the completed Java capture contract at the filesystem boundary."""
    root = Path(run_dir).expanduser().resolve(strict=True)
    if not root.is_dir():
        raise VideoExportError("--run-dir must be a directory")
    manifest_path = root / "video-frames.json"
    subtitles_path = root / "subtitles.json"
    manifest = _load_json(manifest_path, "video-frames.json")
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        raise VideoExportError("video-frames.json must be a schema_version 1 object")
    status = manifest.get("status")
    if status not in {"completed", "partial"}:
        raise VideoExportError("capture status must be completed or partial")
    if status != "completed" and not allow_partial:
        raise VideoExportError("refusing partial capture; pass --allow-partial to export it explicitly")
    if manifest.get("clock") != "GretaTimer":
        raise VideoExportError("capture clock must be GretaTimer")
    if manifest.get("subtitles_burned_in") is not True:
        raise VideoExportError("subtitles_burned_in must be true")
    width = _require_int(manifest, "width")
    height = _require_int(manifest, "height")
    target_fps = _require_int(manifest, "target_fps")
    origin_ms = _require_int(manifest, "first_audio_start_greta_timer_millis")
    end_ms = _require_int(manifest, "capture_end_greta_timer_millis")
    if width <= 0 or height <= 0 or target_fps != 25 or end_ms <= origin_ms:
        raise VideoExportError("capture dimensions, target_fps=25, or timer range is invalid")
    raw_frames = manifest.get("frames")
    raw_audio = manifest.get("audio")
    if not isinstance(raw_frames, list) or not raw_frames or not isinstance(raw_audio, list) or not raw_audio:
        raise VideoExportError("capture requires non-empty frames and audio arrays")
    frames: list[Frame] = []
    last_frame_ms: int | None = None
    for index, value in enumerate(raw_frames, 1):
        if not isinstance(value, dict):
            raise VideoExportError(f"frame {index} is not an object")
        timer_ms = _require_int(value, "greta_timer_millis")
        if last_frame_ms is not None and timer_ms <= last_frame_ms:
            raise VideoExportError("frame timestamps must be strictly increasing")
        if timer_ms > end_ms:
            raise VideoExportError("frame timestamp exceeds capture end")
        frames.append(Frame(_safe_file(root, value.get("file"), field=f"frames[{index}].file"), timer_ms))
        last_frame_ms = timer_ms
    if not any(frame.timer_ms <= origin_ms for frame in frames):
        raise VideoExportError("capture has no frame at or before the first audio start")
    audio: list[Audio] = []
    last_start: int | None = None
    last_audio_end: int | None = None
    event_ids: set[str] = set()
    for index, value in enumerate(raw_audio, 1):
        if not isinstance(value, dict):
            raise VideoExportError(f"audio {index} is not an object")
        event_id = value.get("event_id")
        actor_id = value.get("actor_id")
        if not isinstance(event_id, str) or not event_id or event_id in event_ids or not isinstance(actor_id, str) or not actor_id:
            raise VideoExportError("audio requires unique event_id and actor_id")
        start_ms = _require_int(value, "start_greta_timer_millis")
        audio_end_ms = _require_int(value, "end_greta_timer_millis")
        if audio_end_ms <= start_ms or start_ms < origin_ms or audio_end_ms > end_ms:
            raise VideoExportError("audio timer range is outside the video range")
        if last_start is not None and start_ms < last_start:
            raise VideoExportError("audio entries must be ordered by start timestamp")
        if last_audio_end is not None and start_ms < last_audio_end:
            raise VideoExportError("audio entries must not overlap")
        path = _safe_file(root, value.get("file"), field=f"audio[{index}].file")
        duration_ms = _wav_duration_ms(path)
        if abs(duration_ms - (audio_end_ms - start_ms)) > 40.0:
            raise VideoExportError(f"PCM duration differs from scheduled audio by more than 40 ms: {path.name}")
        audio.append(Audio(event_id, actor_id, path, start_ms, audio_end_ms, duration_ms))
        event_ids.add(event_id)
        last_start = start_ms
        last_audio_end = audio_end_ms
    if audio[0].start_ms != origin_ms:
        raise VideoExportError("first_audio_start_greta_timer_millis must equal the first audio start")
    subtitles = _load_json(subtitles_path, "subtitles.json")
    if not isinstance(subtitles, dict) or subtitles.get("schema_version") != 1:
        raise VideoExportError("subtitles.json must be a schema_version 1 object")
    if subtitles.get("status") != status or subtitles.get("timing_origin") != "first_audio_start_greta_timer_millis":
        raise VideoExportError("subtitles status or timing_origin does not match the capture contract")
    if subtitles.get("first_audio_start_greta_timer_millis") != origin_ms:
        raise VideoExportError("subtitles origin does not match first audio start")
    if subtitles.get("scheduled_audio_only") is not True or subtitles.get("physical_audio_completion_observed") is not False:
        raise VideoExportError("subtitles audio-observation flags do not match the capture contract")
    cues = subtitles.get("cues")
    if not isinstance(cues, list) or len(cues) != len(audio):
        raise VideoExportError("subtitles must contain exactly one cue per audio event")
    for index, (cue, item) in enumerate(zip(cues, audio), 1):
        if not isinstance(cue, dict) or not isinstance(cue.get("text"), str):
            raise VideoExportError(f"subtitle cue {index} must be an object with text")
        if cue.get("event_id") != item.event_id or cue.get("actor_id") != item.actor_id:
            raise VideoExportError("subtitle cue event/actor does not match audio")
        if cue.get("audio_start_greta_timer_millis") != item.start_ms or cue.get("audio_end_greta_timer_millis") != item.end_ms:
            raise VideoExportError("subtitle cue timing does not match audio")
        if cue.get("start_millis_from_first_audio") != item.start_ms - origin_ms or cue.get("end_millis_from_first_audio") != item.end_ms - origin_ms:
            raise VideoExportError("subtitle cue relative timing does not match audio")
    return Capture(root, manifest_path, subtitles_path, status, width, height, target_fps, origin_ms, end_ms, tuple(frames), tuple(audio))


def _timeline(capture: Capture) -> tuple[list[Frame], list[float], float]:
    retained = [frame for frame in capture.frames if frame.timer_ms >= capture.origin_ms]
    prior = [frame for frame in capture.frames if frame.timer_ms <= capture.origin_ms]
    first = prior[-1]
    if not retained or retained[0].timer_ms != capture.origin_ms:
        retained.insert(0, Frame(first.path, capture.origin_ms))
    elif retained[0].path != first.path:
        retained.insert(0, Frame(first.path, capture.origin_ms))
    while len(retained) > 1 and retained[-1].timer_ms == capture.end_ms:
        retained.pop()
    durations = [(retained[index + 1].timer_ms - frame.timer_ms) / 1000.0 for index, frame in enumerate(retained[:-1])]
    durations.append((capture.end_ms - retained[-1].timer_ms) / 1000.0)
    if any(duration <= 0 for duration in durations):
        raise VideoExportError("frame timeline has a non-positive duration")
    return retained, durations, (capture.end_ms - capture.origin_ms) / 1000.0


def _concat_quote(path: Path) -> str:
    return "'" + str(path).replace("'", "'\\''") + "'"


def _run(command: list[str], *, timeout_seconds: float) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=timeout_seconds)
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "").strip()
        raise VideoExportError(f"external video tool failed: {detail[:1000]}") from exc
    except (OSError, subprocess.SubprocessError) as exc:
        raise VideoExportError(f"external video tool failed: {exc}") from exc


def _probe_json(ffprobe: str, path: Path, *, timeout_seconds: float, entries: str) -> dict[str, Any]:
    result = _run([ffprobe, "-v", "error", "-show_entries", entries, "-of", "json", str(path)], timeout_seconds=timeout_seconds)
    try:
        decoded = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise VideoExportError(f"ffprobe returned invalid JSON for {path.name}") from exc
    if not isinstance(decoded, dict):
        raise VideoExportError(f"ffprobe returned an invalid object for {path.name}")
    return decoded


def _verify_frame_dimensions(capture: Capture) -> None:
    for frame in capture.frames:
        try:
            with frame.path.open("rb") as handle:
                header = handle.read(24)
        except OSError as exc:
            raise VideoExportError(f"cannot read frame: {frame.path.name}") from exc
        if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
            raise VideoExportError(f"frame is not a PNG with IHDR: {frame.path.name}")
        width, height = struct.unpack(">II", header[16:24])
        if width != capture.width or height != capture.height:
            raise VideoExportError(f"frame dimensions differ from capture contract: {frame.path.name}")


def _publish_no_clobber(source: Path, destination: Path, *, label: str) -> None:
    try:
        os.link(source, destination)
    except FileExistsError as exc:
        raise VideoExportError(f"refusing to overwrite concurrently created {label}: {destination}") from exc
    except OSError as exc:
        raise VideoExportError(f"cannot publish {label} without overwrite: {exc}") from exc


def _interval_stats(frames: Iterable[Frame]) -> dict[str, float | int | None]:
    values = [frame.timer_ms for frame in frames]
    intervals = [values[index + 1] - values[index] for index in range(len(values) - 1)]
    return {"frame_count": len(values), "interval_count": len(intervals), "min_ms": min(intervals) if intervals else None, "max_ms": max(intervals) if intervals else None, "mean_ms": (sum(intervals) / len(intervals)) if intervals else None}


def export_video(
    run_dir: Path | str,
    *,
    output: Path | str | None = None,
    ffmpeg: str = "ffmpeg",
    ffprobe: str = "ffprobe",
    timeout_seconds: float = 300.0,
    allow_partial: bool = False,
) -> Path:
    """Encode, probe, receipt, and atomically publish a capture MP4."""
    if timeout_seconds <= 0:
        raise VideoExportError("timeout_seconds must be positive")
    capture = load_capture(run_dir, allow_partial=allow_partial)
    output_path = Path(output) if output is not None else capture.run_dir / "playback.mp4"
    output_path = output_path.expanduser().resolve()
    if output_path.exists() or output_path.is_symlink():
        raise VideoExportError(f"refusing to overwrite existing output: {output_path}")
    if not output_path.parent.is_dir():
        raise VideoExportError("output parent directory does not exist")
    receipt_path = output_path.with_name("video-export-receipt.json")
    if receipt_path.exists() or receipt_path.is_symlink():
        raise VideoExportError(f"refusing to overwrite existing receipt: {receipt_path}")
    if shutil.which(ffmpeg) is None or shutil.which(ffprobe) is None:
        raise VideoExportError("ffmpeg and ffprobe must be available on PATH or supplied explicitly")
    _verify_frame_dimensions(capture)
    frames, durations, video_duration = _timeline(capture)
    with tempfile.TemporaryDirectory(prefix="gmit-video-", dir=output_path.parent) as temp_name:
        temp = Path(temp_name)
        concat = temp / "frames.concat"
        lines: list[str] = []
        for frame, duration in zip(frames, durations):
            lines.extend((f"file {_concat_quote(frame.path)}", f"duration {duration:.6f}"))
        lines.append(f"file {_concat_quote(frames[-1].path)}")
        concat.write_text("\n".join(lines) + "\n", encoding="utf-8")
        temp_output = temp / "playback.mp4"
        command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-xerror", "-f", "concat", "-safe", "0", "-i", str(concat)]
        for item in capture.audio:
            command.extend(("-i", str(item.path)))
        filter_parts = [f"anullsrc=r=48000:cl=stereo:d={video_duration:.6f}[bed]"]
        labels = ["[bed]"]
        for index, item in enumerate(capture.audio, 1):
            delay = item.start_ms - capture.origin_ms
            label = f"[a{index}]"
            filter_parts.append(f"[{index}:a]adelay={delay}:all=1{label}")
            labels.append(label)
        filter_parts.append("".join(labels) + f"amix=inputs={len(labels)}:duration=first:dropout_transition=0:normalize=0[aout]")
        command.extend(("-filter_complex", ";".join(filter_parts), "-map", "0:v:0", "-map", "[aout]", "-vf", f"fps={capture.target_fps}", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", "-t", f"{video_duration:.6f}", str(temp_output)))
        _run(command, timeout_seconds=timeout_seconds)
        final = _probe_json(ffprobe, temp_output, timeout_seconds=timeout_seconds, entries="format=duration:stream=codec_type,duration")
        streams = final.get("streams")
        if not isinstance(streams, list) or sorted(stream.get("codec_type") for stream in streams if isinstance(stream, dict)) != ["audio", "video"]:
            raise VideoExportError("encoded file does not contain exactly one audio and one video stream")
        try:
            actual_duration = float(final["format"]["duration"])
        except (KeyError, TypeError, ValueError) as exc:
            raise VideoExportError("encoded file has no measurable duration") from exc
        if abs(actual_duration - video_duration) > max(0.25, 1.0 / capture.target_fps + 0.1):
            raise VideoExportError("encoded duration does not match the capture timeline")
        stream_tolerance = max(0.25, 1.0 / capture.target_fps + 0.1)
        for stream in streams:
            if not isinstance(stream, dict):
                raise VideoExportError("encoded file has an invalid stream record")
            try:
                stream_duration = float(stream["duration"])
            except (KeyError, TypeError, ValueError) as exc:
                raise VideoExportError("encoded stream has no measurable duration") from exc
            if abs(stream_duration - video_duration) > stream_tolerance:
                raise VideoExportError(f"encoded {stream.get('codec_type')} duration {stream_duration:.6f}s does not match capture timeline {video_duration:.6f}s")
        receipt = {
            "schema_version": 1,
            "status": "completed",
            "output": str(output_path),
            "output_sha256": _sha256(temp_output),
            "manifest_sha256": _sha256(capture.manifest_path),
            "subtitles_sha256": _sha256(capture.subtitles_path),
            "capture_status": capture.status,
            "video_duration_seconds": video_duration,
            "encoded_duration_seconds": actual_duration,
            "frame_capture_intervals_millis": _interval_stats(capture.frames),
            "audio": [{"event_id": item.event_id, "actor_id": item.actor_id, "file": item.path.name, "sha256": _sha256(item.path), "scheduled_duration_millis": item.end_ms - item.start_ms, "pcm_duration_millis": item.pcm_duration_ms} for item in capture.audio],
            "tools": {"ffmpeg": ffmpeg, "ffprobe": ffprobe},
        }
        temp_receipt = temp / "video-export-receipt.json"
        temp_receipt.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        _publish_no_clobber(temp_output, output_path, label="output")
        _publish_no_clobber(temp_receipt, receipt_path, label="receipt")
    return output_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--timeout-seconds", type=float, default=300.0)
    parser.add_argument("--allow-partial", action="store_true")
    args = parser.parse_args(argv)
    try:
        output = export_video(args.run_dir, output=args.output, ffmpeg=args.ffmpeg, ffprobe=args.ffprobe, timeout_seconds=args.timeout_seconds, allow_partial=args.allow_partial)
    except (VideoExportError, OSError) as exc:
        print(f"video export failed: {exc}", file=os.sys.stderr)
        return 2
    print(json.dumps({"output": str(output), "receipt": str(output.with_name("video-export-receipt.json"))}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
