from __future__ import annotations

import argparse
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

from integrations.gmit.event_bundle import BundleError
from integrations.gmit import run_rl
from integrations.gmit.run_rl import _expected_actors, _loopback_ollama_host, _output_dir, _validate_rows


class RLAdapterTests(unittest.TestCase):
    def test_four_patient_turns_keep_opening_and_final_facilitator(self) -> None:
        self.assertEqual(
            _expected_actors(4),
            [
                "facilitator",
                "patient-a",
                "patient-b",
                "facilitator",
                "patient-a",
                "patient-b",
                "facilitator",
            ],
        )

    def test_rejects_duplicate_or_extra_episode_rows(self) -> None:
        rows = [
            {"speaker": "Therapist", "text": "Opening."},
            {"speaker": "Patient A", "text": "A."},
            {"speaker": "Patient B", "text": "B."},
            {"speaker": "Therapist", "text": "Closing."},
        ]
        _validate_rows(rows, 2)
        with self.assertRaises(BundleError):
            _validate_rows(rows + rows, 2)

    def test_existing_output_is_refused_before_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "existing"
            destination.mkdir()
            args = argparse.Namespace(session_id="rl-case", output_dir=destination)
            with self.assertRaises(BundleError):
                _output_dir(args)

    def test_preflight_never_queries_ollama(self) -> None:
        with (
            mock.patch.object(run_rl, "_validate_args"),
            mock.patch.object(run_rl, "_read_ollama_identity") as identity,
            mock.patch.object(
                run_rl,
                "_run_bounded_episode",
                return_value={"preflight": "ok", "gmit_revision": "abc"},
            ),
        ):
            self.assertEqual(run_rl.main(["--checkpoint", "/checkpoint.pt", "--preflight-only"]), 0)
        identity.assert_not_called()

    def test_worker_failure_cannot_write_a_bundle(self) -> None:
        with (
            mock.patch.object(run_rl, "_validate_args"),
            mock.patch.object(run_rl, "_read_ollama_identity", return_value={"model": "local"}),
            mock.patch.object(run_rl, "_run_bounded_episode", side_effect=BundleError("timed out")),
            mock.patch.object(run_rl, "write_bundle") as write_bundle,
        ):
            self.assertEqual(run_rl.main(["--checkpoint", "/checkpoint.pt", "--output-dir", "/tmp/new-rl-test"]), 2)
        write_bundle.assert_not_called()

    def test_rl_bundle_uses_recorded_transport_and_preserves_floor_receipt(self) -> None:
        rows = [
            {"speaker": "Therapist", "text": "Opening."},
            {"speaker": "Patient A", "text": "A."},
            {"speaker": "Patient B", "text": "B."},
            {"speaker": "Therapist", "text": "Closing."},
        ]
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "bundle"
            result = {"rows": rows, "gmit_revision": "abc", "checkpoint_sha256": "def", "device": "cpu"}
            with (
                mock.patch.object(run_rl, "_validate_args"),
                mock.patch.object(run_rl, "_read_ollama_identity", return_value={"model": "local"}),
                mock.patch.object(run_rl, "_run_bounded_episode", return_value=result),
            ):
                self.assertEqual(
                    run_rl.main(
                        ["--checkpoint", "/checkpoint.pt", "--patient-turns", "2", "--output-dir", str(destination)]
                    ),
                    0,
                )
            root = ET.parse(destination / "replay.xml").getroot()
            self.assertEqual(root.get("provenance"), "gmit-generated")
            self.assertEqual(root.get("floor_mode"), "recorded")
            self.assertEqual(root.get("condition"), "rl")
            receipt = json.loads((destination / "receipt.json").read_text(encoding="utf-8"))
            self.assertEqual(
                receipt["generation_floor_mode"],
                "facilitator-a-b-round-robin-evaluation",
            )

    def test_rl_uses_the_same_loopback_only_ollama_boundary(self) -> None:
        self.assertEqual(_loopback_ollama_host("http://127.0.0.1:11434"), "http://127.0.0.1:11434")
        with self.assertRaises(BundleError):
            _loopback_ollama_host("http://example.invalid:11434")


if __name__ == "__main__":
    unittest.main()
