from __future__ import annotations

import tempfile
from unittest import mock
import unittest
from pathlib import Path
from contextlib import redirect_stderr
from io import StringIO

try:
    import numpy as np
except ImportError:  # The base Greta tooling environment does not require NumPy.
    np = None

from integrations.gmit import modiff_vq


class ModiffVQTests(unittest.TestCase):
    def test_safe_upper_face_preserves_only_brows(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        source = np.ones((18, len(modiff_vq.AU_COLUMNS)), dtype=np.float32)
        output = modiff_vq.safe_upper_face(source, np)
        selected = {modiff_vq.AU_COLUMNS.index(name) for name in modiff_vq.SAFE_UPPER_FACE_AUS}
        self.assertTrue(np.all(output[:, list(selected)] == 1))
        self.assertTrue(np.all(output[:, [i for i in range(len(modiff_vq.AU_COLUMNS)) if i not in selected]] == 0))

    def test_safe_upper_face_rejects_wrong_au_shape(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        with self.assertRaises(modiff_vq.ModiffVQError):
            modiff_vq.safe_upper_face(np.zeros((18, 3), dtype=np.float32), np)

    def test_model_root_requires_complete_local_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "model"
            root.mkdir()
            root.parent.joinpath("RealTimePipeVQ.py").write_text("", encoding="utf-8")
            with self.assertRaises(modiff_vq.ModiffVQError):
                modiff_vq.model_paths(root)

    def test_parser_requires_a_mode(self) -> None:
        with redirect_stderr(StringIO()):
            with self.assertRaises(SystemExit):
                modiff_vq.main(["--model-root", "/does-not-matter"])

    def test_mailbox_response_is_brow_only_and_bounded(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        output = np.full((18, len(modiff_vq.AU_COLUMNS)), 5.0, dtype=np.float32)
        text = modiff_vq.mailbox_response(("case-1", "patient-a", 7, 144), output, np)
        rows = [line.split("\t") for line in text.splitlines()]
        self.assertEqual(rows[0], [modiff_vq.MAILBOX_VERSION, "case-1", "patient-a", "7", "144"])
        self.assertEqual(len(rows), 19)
        self.assertTrue(all(len(row) == 3 and all(float(value) == 0.25 for value in row) for row in rows[1:]))

    def test_request_rejects_out_of_range_actor_input(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        with tempfile.TemporaryDirectory() as temporary:
            request = Path(temporary) / "request.tsv"
            row = "\t".join(["0"] * 16 + ["6"])
            request.write_text(
                modiff_vq.MAILBOX_VERSION + "\tcase-1\tpatient-a\t1\t144\n" + (row + "\n") * 144,
                encoding="utf-8",
            )
            with self.assertRaises(modiff_vq.ModiffVQError):
                modiff_vq.read_request(request, np)

    def test_request_rejects_unknown_source_actor(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        with tempfile.TemporaryDirectory() as temporary:
            request = Path(temporary) / "request.tsv"
            row = "\t".join(["0"] * len(modiff_vq.AU_COLUMNS))
            request.write_text(
                modiff_vq.MAILBOX_VERSION + "\tcase-1\tfacilitator\t1\t144\n" + (row + "\n") * 144,
                encoding="utf-8",
            )
            with self.assertRaises(modiff_vq.ModiffVQError):
                modiff_vq.read_request(request, np)

    def test_request_rejects_nan_and_negative_metadata(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        with tempfile.TemporaryDirectory() as temporary:
            request = Path(temporary) / "request.tsv"
            row = "\t".join(["0"] * len(modiff_vq.AU_COLUMNS))
            request.write_text(
                modiff_vq.MAILBOX_VERSION + "\tcase-1\tpatient-a\t-1\t144\n" + (row + "\n") * 144,
                encoding="utf-8",
            )
            with self.assertRaises(modiff_vq.ModiffVQError):
                modiff_vq.read_request(request, np)
            nan_row = "\t".join(["nan"] + ["0"] * (len(modiff_vq.AU_COLUMNS) - 1))
            request.write_text(
                modiff_vq.MAILBOX_VERSION + "\tcase-2\tpatient-a\t1\t144\n" + (nan_row + "\n") * 144,
                encoding="utf-8",
            )
            with self.assertRaises(modiff_vq.ModiffVQError):
                modiff_vq.read_request(request, np)

    def test_mailbox_processes_one_atomic_request_and_marks_exit_stale(self) -> None:
        if np is None:
            self.skipTest("NumPy is available in the dedicated MODIFF-VQ environment")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            marker = root / "artifact"
            marker.write_text("model", encoding="utf-8")
            row = "\t".join(["0.1"] * len(modiff_vq.AU_COLUMNS))
            root.joinpath("request.tsv").write_text(
                modiff_vq.MAILBOX_VERSION + "\tcase-1\tpatient-b\t1\t144\n" + (row + "\n") * 144,
                encoding="utf-8",
            )
            context = {
                "numpy": np,
                "paths": {"marker": marker},
                "predictor_embeds_vqvae": True,
                "embedded_vqvae_matches_standalone": True,
            }
            predicted = np.full((18, len(modiff_vq.AU_COLUMNS)), 1.0, dtype=np.float32)
            with (
                mock.patch.object(modiff_vq, "load_model", return_value=(object(), context)),
                mock.patch.object(modiff_vq, "predict_safe_upper_face", return_value=predicted),
            ):
                self.assertEqual(modiff_vq.serve_mailbox(root, root, max_seconds=0.05, torch_threads=2), 0)
            response = root.joinpath("response.tsv").read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(response), 19)
            self.assertTrue(all(len(line.split("\t")) == 3 for line in response[1:]))
            ready = root.joinpath("ready.json").read_text(encoding="utf-8")
            self.assertIn('"status": "stopped"', ready)
            self.assertFalse(root.joinpath("request.tsv").exists())


if __name__ == "__main__":
    unittest.main()
