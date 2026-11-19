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

from integrations.gmit.export_video import VideoExportError, _paced_timeline, _publish_no_clobber, _verify_frame_dimensions, export_video, load_capture


def _png(path: Path, rgb: tuple[int, int, int]) -> None:
    width = height = 2
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)

    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def _ppm(path: Path, rgb: tuple[int, int, int]) -> None:
    path.write_bytes(b"P6\n2 2\n255\n" + bytes(rgb) * 4)


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


def _two_turn_capture(root: Path) -> Path:
    _capture(root)
    _ppm(root / "00-scene.ppm", (200, 200, 200))
    frames = root / "video-frames"
    _png(frames / "frame-000000.png", (200, 200, 200))
    for index, color in enumerate(((40, 40, 255), (60, 60, 255), (80, 80, 255), (100, 100, 255), (120, 120, 255), (140, 140, 255)), 5):
        _png(frames / f"frame-{index:06d}.png", color)
    _wav(root / "patient-a-1.wav", 120, amplitude=8000)
    manifest_path = root / "video-frames.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["capture_end_greta_timer_millis"] = 2080
    manifest["frames"].insert(0, {"file": "video-frames/frame-000000.png", "greta_timer_millis": 960})
    manifest["frames"].extend([
        {"file": "video-frames/frame-000004.png", "greta_timer_millis": 1600},
        {"file": "video-frames/frame-000005.png", "greta_timer_millis": 1720},
        {"file": "video-frames/frame-000006.png", "greta_timer_millis": 1880},
        {"file": "video-frames/frame-000007.png", "greta_timer_millis": 1920},
        {"file": "video-frames/frame-000008.png", "greta_timer_millis": 1960},
        {"file": "video-frames/frame-000009.png", "greta_timer_millis": 2060},
        {"file": "video-frames/frame-000010.png", "greta_timer_millis": 2080},
    ])
    manifest["audio"].append({
        "event_id": "a-1", "actor_id": "patient-a", "file": "patient-a-1.wav",
        "start_greta_timer_millis": 1920, "end_greta_timer_millis": 2040,
    })
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    subtitles_path = root / "subtitles.json"
    subtitles = json.loads(subtitles_path.read_text(encoding="utf-8"))
    subtitles["cues"].append({
        "event_id": "a-1", "actor_id": "patient-a", "text": "burned in two",
        "audio_start_greta_timer_millis": 1920, "audio_end_greta_timer_millis": 2040,
        "start_millis_from_first_audio": 920, "end_millis_from_first_audio": 1040,
    })
    subtitles_path.write_text(json.dumps(subtitles), encoding="utf-8")
    return root


class VideoExportTests(unittest.TestCase):
    def test_hd_rgb_frame_requires_matching_header_and_complete_pixels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rel = "video-frames/frame-000001.ppm"
            _capture(root, frame_file=rel)
            frame = root / rel
            header = b"P6\n2 2\n255\n"
            frame.write_bytes(header + b"\xfe\x15\x40" * 4)
            capture = load_capture(root)
            _verify_frame_dimensions(capture)
            for broken in (header + bytes(11), b"P6\n1 4\n255\n" + bytes(12)):
                frame.write_bytes(broken)
                with self.assertRaises(VideoExportError):
                    _verify_frame_dimensions(capture)

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

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg/ffprobe unavailable")
    def test_wide_paced_export_retimes_audio_without_crop_or_caption_regeneration(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "two turns"
            root.mkdir()
            _two_turn_capture(root)
            subtitle_output = root / "wide.srt"
            output = export_video(
                root, output=root / "wide.mp4", turn_gap_ms=600, lead_ms=1000,
                end_hold_ms=40, subtitle_output=subtitle_output,
            )
            receipt = json.loads((root / "video-export-receipt.json").read_text(encoding="utf-8"))
            self.assertEqual(receipt["presentation_timeline"], {
                "mode": "wide-paced", "turn_gap_millis": 600, "lead_millis": 1000, "end_hold_millis": 40,
            })
            self.assertAlmostEqual(receipt["video_duration_seconds"], 1.88)
            self.assertEqual(
                subtitle_output.read_text(encoding="utf-8"),
                "1\n00:00:01,000 --> 00:00:01,120\nFacilitator: burned in\n\n"
                "2\n00:00:01,720 --> 00:00:01,840\nA: burned in two\n",
            )
            probe = subprocess.run(
                [shutil.which("ffprobe") or "ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type", "-of", "json", str(output)],
                check=True, capture_output=True, text=True,
            )
            self.assertLessEqual(abs(float(json.loads(probe.stdout)["format"]["duration"]) - 1.88), 1 / 25 + 0.001)

    def test_wide_paced_export_rejects_end_hold_not_present_in_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "two turns"
            root.mkdir()
            _two_turn_capture(root)
            with self.assertRaises(VideoExportError):
                export_video(root, output=root / "wide.mp4", turn_gap_ms=600, end_hold_ms=41)

    def test_wide_paced_timeline_does_not_seed_speech_or_silence_with_previous_caption_frame(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "two turns"
            root.mkdir()
            _two_turn_capture(root)
            manifest_path = root / "video-frames.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["frames"] = [frame for frame in manifest["frames"] if frame["greta_timer_millis"] != 1000]
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            timeline = _paced_timeline(load_capture(root), turn_gap_ms=600, lead_ms=1000, end_hold_ms=40)
            by_time = {frame.timer_ms: frame.path.name for frame in timeline.frames}
            self.assertEqual(by_time[1000], "frame-000002.png")
            self.assertEqual(by_time[1120], "frame-000004.png")
            self.assertEqual(timeline.audio_starts_ms, (1000, 1720))
            self.assertEqual(by_time[1760], "frame-000008.png")

    def test_subtitle_output_must_be_a_sibling_of_movie(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "capture"
            root.mkdir()
            _capture(root)
            elsewhere = Path(temp) / "elsewhere"
            elsewhere.mkdir()
            with self.assertRaises(VideoExportError):
                export_video(root, output=root / "movie.mp4", subtitle_output=elsewhere / "movie.srt")

    def test_zero_lead_paced_timeline_does_not_require_a_pre_speech_frame(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "one turn"
            root.mkdir()
            _capture(root)
            timeline = _paced_timeline(load_capture(root), turn_gap_ms=600, lead_ms=0, end_hold_ms=0)
            self.assertEqual(timeline.frames[0].timer_ms, 0)
            self.assertEqual(timeline.audio_starts_ms, (0,))


if __name__ == "__main__":
    unittest.main()
