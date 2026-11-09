from __future__ import annotations

import binascii
import json
import shutil
import struct
import subprocess
import tempfile
import unittest
import wave
import zlib
from pathlib import Path

from integrations.gmit.export_video import VideoExportError, _publish_no_clobber, export_video, load_capture


def _png(path: Path, rgb: tuple[int, int, int]) -> None:
    width = height = 2
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)

    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def _wav(path: Path, milliseconds: int, amplitude: int = 12000) -> None:
    rate = 8000
    samples = rate * milliseconds // 1000
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(struct.pack("<h", amplitude) * samples)


def _capture(root: Path, *, status: str = "completed", frame_file: str = "video-frames/frame-000001.png") -> Path:
    frames = root / "video-frames"
    frames.mkdir()
    _png(frames / "frame-000001.png", (255, 0, 0))
    _png(frames / "frame-000002.png", (0, 255, 0))
    _png(frames / "frame-000003.png", (0, 0, 255))
    _png(frames / "frame-000004.png", (0, 0, 255))
    _wav(root / "facilitator-1.wav", 120)
    manifest = {
        "schema_version": 1,
        "status": status,
        "clock": "GretaTimer",
        "subtitles_burned_in": True,
        "width": 2,
        "height": 2,
        "target_fps": 25,
        "first_audio_start_greta_timer_millis": 1000,
        "capture_end_greta_timer_millis": 1120,
        "frames": [
            {"file": frame_file, "greta_timer_millis": 1000},
            {"file": "video-frames/frame-000002.png", "greta_timer_millis": 1040},
            {"file": "video-frames/frame-000003.png", "greta_timer_millis": 1080},
            {"file": "video-frames/frame-000004.png", "greta_timer_millis": 1120},
        ],
        "audio": [{"event_id": "f-1", "actor_id": "facilitator", "file": "facilitator-1.wav", "start_greta_timer_millis": 1000, "end_greta_timer_millis": 1120}],
    }
    (root / "video-frames.json").write_text(json.dumps(manifest), encoding="utf-8")
    subtitles = {
        "schema_version": 1,
        "status": status,
        "timing_origin": "first_audio_start_greta_timer_millis",
        "first_audio_start_greta_timer_millis": 1000,
        "scheduled_audio_only": True,
        "physical_audio_completion_observed": False,
        "cues": [{"event_id": "f-1", "actor_id": "facilitator", "text": "burned in", "audio_start_greta_timer_millis": 1000, "audio_end_greta_timer_millis": 1120, "start_millis_from_first_audio": 0, "end_millis_from_first_audio": 120}],
    }
    (root / "subtitles.json").write_text(json.dumps(subtitles), encoding="utf-8")
    return root


class VideoExportTests(unittest.TestCase):
    def test_rejects_partial_and_path_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "partial"
            root.mkdir()
            _capture(root, status="partial")
            with self.assertRaises(VideoExportError):
                load_capture(root)
            self.assertEqual(load_capture(root, allow_partial=True).status, "partial")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "escape"
            root.mkdir()
            _capture(root, frame_file="../outside.png")
            with self.assertRaises(VideoExportError):
                load_capture(root)

    def test_rejects_overlapping_audio_schedule(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "overlap"
            root.mkdir()
            _capture(root)
            manifest_path = root / "video-frames.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            _wav(root / "patient-a-1.wav", 40)
            manifest["audio"].append({"event_id": "a-1", "actor_id": "patient-a", "file": "patient-a-1.wav", "start_greta_timer_millis": 1080, "end_greta_timer_millis": 1120})
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(VideoExportError):
                load_capture(root)

    def test_publish_refuses_concurrent_destination_without_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "encoded.tmp"
            destination = root / "playback.mp4"
            source.write_bytes(b"new")
            destination.write_bytes(b"user-created")
            with self.assertRaises(VideoExportError):
                _publish_no_clobber(source, destination, label="output")
            self.assertEqual(destination.read_bytes(), b"user-created")

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg/ffprobe unavailable")
    def test_encodes_and_probes_synthetic_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "run with spaces ' quote"
            root.mkdir()
            _capture(root)
            output = export_video(root)
            self.assertTrue(output.is_file())
            receipt = json.loads((root / "video-export-receipt.json").read_text(encoding="utf-8"))
            self.assertEqual(receipt["status"], "completed")
            self.assertEqual(receipt["frame_capture_intervals_millis"]["mean_ms"], 40.0)
            decoded = subprocess.run([shutil.which("ffmpeg") or "ffmpeg", "-v", "error", "-i", str(output), "-map", "0:a:0", "-f", "s16le", "-"], check=True, capture_output=True).stdout
            peak = max(abs(struct.unpack("<h", decoded[index:index + 2])[0]) for index in range(0, len(decoded) - 1, 2))
            self.assertGreater(peak, 8000, "amix must not normalize a non-overlapping voice against the silent bed")

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg/ffprobe unavailable")
    def test_sparse_irregular_capture_keeps_scheduled_duration_with_cfr_duplicates(self) -> None:
        """Protect the real regression where output ``-r`` dropped a long final hold."""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "sparse"
            root.mkdir()
            _capture(root)
            manifest_path = root / "video-frames.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            # Deliberately sparse capture: terminal frame has zero display time
            # and the prior frame must be held to the scheduled capture end.
            for frame, timestamp in zip(manifest["frames"], (1000, 1097, 1394, 1600)):
                frame["greta_timer_millis"] = timestamp
            manifest["capture_end_greta_timer_millis"] = 1600
            manifest["audio"][0]["end_greta_timer_millis"] = 1600
            _wav(root / "facilitator-1.wav", 600)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            subtitles_path = root / "subtitles.json"
            subtitles = json.loads(subtitles_path.read_text(encoding="utf-8"))
            subtitles["cues"][0]["audio_end_greta_timer_millis"] = 1600
            subtitles["cues"][0]["end_millis_from_first_audio"] = 600
            subtitles_path.write_text(json.dumps(subtitles), encoding="utf-8")
            output = export_video(root)
            probe = subprocess.run(
                [shutil.which("ffprobe") or "ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type,duration", "-of", "json", str(output)],
                check=True,
                capture_output=True,
                text=True,
            )
            data = json.loads(probe.stdout)
            stream_durations = {stream["codec_type"]: float(stream["duration"]) for stream in data["streams"]}
            expected = 0.600
            self.assertLessEqual(abs(float(data["format"]["duration"]) - expected), 1 / 25 + 0.001)
            self.assertLessEqual(abs(stream_durations["video"] - expected), 1 / 25 + 0.001)
            self.assertLessEqual(abs(stream_durations["audio"] - expected), 1 / 25 + 0.001)


if __name__ == "__main__":
    unittest.main()
