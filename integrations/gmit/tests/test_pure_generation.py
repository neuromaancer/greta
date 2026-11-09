from __future__ import annotations

import json
import contextlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from integrations.gmit.event_bundle import BundleError, canonicalize_rows
from integrations.gmit.pure import OllamaChatClient, PureGenerationError, REQUIRED_FEWSHOT_PAIR_COUNT, load_fewshot_asset, package_provenance, run_round_robin_episode
from integrations.gmit.pure.taxonomy import PatientStyle, PatientType
from integrations.gmit.run_pure import build_parser


class ScriptedClient:
    def __init__(self, outputs: list[str]) -> None:
        self.outputs = list(outputs)
        self.calls: list[dict[str, object]] = []

    def chat(self, messages: list[dict[str, str]], *, temperature: float, **options: object) -> str:
        self.calls.append({"messages": messages, "temperature": temperature, "options": options})
        if not self.outputs:
            raise AssertionError("unexpected LLM call")
        return self.outputs.pop(0)


class PureGenerationTests(unittest.TestCase):
    def _asset(self, directory: Path) -> Path:
        path = directory / "fewshot.json"
        pairs = [{"input": f"format input {index}", "output": f"format output {index}"} for index in range(REQUIRED_FEWSHOT_PAIR_COUNT)]
        path.write_text(json.dumps(pairs), encoding="utf-8")
        return path

    def test_strict_episode_preserves_call_roles_and_rows_without_gmit(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            asset = self._asset(Path(temp))
            pairs, receipt = load_fewshot_asset(asset)
            patients = ScriptedClient([
                '{"Category":"Sharing personal information or Describe past event","Patient\'s Utterance":"A names a practical barrier."}',
                '{"Category":"Empathic Reaction","Patient\'s Utterance":"B relates without taking over."}',
                '{"Category":"Changing unhealthy behavior in the future","Patient\'s Utterance":"A chooses a small step."}',
                '{"Category":"New Perspective","Patient\'s Utterance":"B sees one workable option."}',
            ])
            therapist = ScriptedClient([
                '{"act":"Reflection"}', "You are weighing convenience against a change you chose.",
                '{"act":"Planning with the Patient"}', "Would each of you like to name one small experiment?",
            ])
            stage = ScriptedClient(["Engaging"] * 7)
            previous_cwd = Path.cwd()
            try:
                os.chdir(temp)
                with patch.dict(os.environ, {"GMIT_ROOT": ""}, clear=False):
                    rows = run_round_robin_episode(
                        episode_id=7, theme="A bounded topic", turns=4,
                        patient_a_type=PatientType.OPEN, patient_b_type=PatientType.RECEPTIVE,
                        llm_patients=patients, llm_therapist=therapist, llm_stage=stage,
                        patient_style=PatientStyle.GMIT, prompt_dir=None, patient_fewshot=pairs,
                        use_readiness=False, open_persona_version="v2", silent=True,
                    )
            finally:
                os.chdir(previous_cwd)
            self.assertEqual([row["speaker"] for row in rows], ["Therapist", "Patient A", "Patient B", "Therapist", "Patient A", "Patient B", "Therapist"])
            self.assertEqual(rows[0]["act"], "Greeting or Closing")
            self.assertEqual(rows[0]["text"], "Welcome everyone. Let's begin by hearing what's on your mind.")
            self.assertEqual([row["turn"] for row in rows], [0, 0, 0, 1, 1, 1, 2])
            self.assertEqual(len(patients.calls), 4)
            self.assertEqual(len(therapist.calls), 4, "fixed opening must not make selector/NLG calls")
            self.assertEqual(len(stage.calls), 7)
            self.assertTrue(all(call["temperature"] == 0.2 for call in stage.calls))
            self.assertEqual([call["temperature"] for call in therapist.calls], [0.4, 0.7, 0.4, 0.7])
            self.assertEqual(receipt["count"], REQUIRED_FEWSHOT_PAIR_COUNT)
            self.assertTrue(Path(str(package_provenance()["package_root"])).is_absolute())

    def test_missing_or_placeholder_data_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(PureGenerationError):
                load_fewshot_asset(Path(temp) / "missing.json")
            self._asset(Path(temp))
            with self.assertRaises(BundleError):
                canonicalize_rows([{"speaker": "Patient A", "text": "..."}], session_id="x", condition="pure")

    def test_fewshot_asset_requires_exactly_fifteen_pairs(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            for count in (14, 16):
                path = Path(temp) / f"{count}.json"
                path.write_text(json.dumps([{"input": "i", "output": "o"}] * count), encoding="utf-8")
                with self.assertRaises(PureGenerationError):
                    load_fewshot_asset(path)

    def test_isolated_stdlib_package_runs_fake_episode_without_gmit(self) -> None:
        package_root = Path(__file__).resolve().parents[1] / "pure"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            shutil.copytree(package_root, root / "pure", ignore=shutil.ignore_patterns("__pycache__"))
            asset = self._asset(root)
            script = r'''
import builtins, json, os, sys
blocked = {"gmit", "benchmark_llm"}
original_import = builtins.__import__
def guarded_import(name, *args, **kwargs):
    if name.split(".", 1)[0] in blocked:
        raise AssertionError("forbidden private import: " + name)
    return original_import(name, *args, **kwargs)
builtins.__import__ = guarded_import
os.environ.pop("GMIT_ROOT", None)
from pure import load_fewshot_asset, run_round_robin_episode
from pure.taxonomy import PatientStyle, PatientType
class Fake:
    provider = "ollama"
    def __init__(self, values): self.values = list(values)
    def chat(self, messages, temperature=0.7, **kwargs): return self.values.pop(0)
pairs, _ = load_fewshot_asset(sys.argv[1])
rows = run_round_robin_episode(
    episode_id=0, theme="topic", turns=2,
    patient_a_type=PatientType.OPEN, patient_b_type=PatientType.RECEPTIVE,
    patient_style=PatientStyle.GMIT,
    llm_patients=Fake([
        '{"Category":"Sharing personal information or Describe past event","text":"A gives a reason."}',
        '{"Category":"Empathic Reaction","text":"B relates briefly."}',
    ]),
    llm_therapist=Fake(['{"act":"Reflection"}', "That sounds important to you."]),
    llm_stage=Fake(["Engaging"] * 4), prompt_dir=None, patient_fewshot=pairs,
    use_readiness=False, open_persona_version="v2", silent=True,
)
assert [row["speaker"] for row in rows] == ["Therapist", "Patient A", "Patient B", "Therapist"]
assert not any(name == "gmit" or name.startswith("gmit.") or name == "benchmark_llm" for name in sys.modules)
print("isolated-stdlib-pure-ok")
'''
            completed = subprocess.run(
                [sys.executable, "-S", "-c", script, str(asset)],
                cwd=root,
                env={key: value for key, value in os.environ.items() if key not in {"PYTHONPATH", "GMIT_ROOT"}},
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.stdout.strip(), "isolated-stdlib-pure-ok")

    def test_cli_has_no_private_checkout_flag(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                build_parser().parse_args(["--gmit-root", "/private/gmit"])

    def test_native_ollama_options_match_router_shape(self) -> None:
        captured: dict[str, object] = {}

        class Response:
            def read(self) -> bytes:
                return b'{"message":{"content":"ok"}}'

            def __enter__(self) -> "Response":
                return self

            def __exit__(self, *_args: object) -> None:
                return None

        def fake_open(request: object, timeout: float) -> Response:
            captured["body"] = request.data  # type: ignore[attr-defined]
            captured["timeout"] = timeout
            return Response()

        client = OllamaChatClient(base_url="http://127.0.0.1:11434", model="unit-model", base_options={"num_ctx": 8192, "seed": 42}, timeout_seconds=3)
        with patch("urllib.request.urlopen", fake_open):
            self.assertEqual(client.chat([{"role": "user", "content": "hello"}], temperature=0.4, repetition_penalty=1.15), "ok")
        payload = json.loads(captured["body"])
        self.assertEqual(payload["options"], {"num_ctx": 8192, "seed": 42, "temperature": 0.4, "repetition_penalty": 1.15})
        self.assertEqual(captured["timeout"], 3)


if __name__ == "__main__":
    unittest.main()
