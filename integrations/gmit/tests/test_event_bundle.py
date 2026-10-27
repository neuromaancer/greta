from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from integrations.gmit.event_bundle import BundleError, canonicalize_rows, split_playback_text, write_bundle
from integrations.gmit.importer import REPOSITORY_ROOT, private_output_path, read_rows, select_episode
from integrations.gmit.run_pure import _loopback_ollama_host


class EventBundleTests(unittest.TestCase):
    def test_preserves_rows_and_splits_losslessly(self) -> None:
        source_text = "A" * 219 + " 😀 " + "B" * 30
        chunks = split_playback_text(source_text)
        self.assertEqual("".join(chunks), source_text)
        self.assertTrue(all(len(chunk) <= 160 for chunk in chunks))
        rows = [
            {"speaker": "Therapist", "text": "Welcome", "act": "GREET_CLOSE", "turn": 0},
            {"speaker": "Patient A", "text": source_text, "stage": "ENGAGING", "turn": 0},
        ]
        events = canonicalize_rows(rows, session_id="Case One", condition="pure")
        self.assertEqual(events[0]["text"], "Welcome")
        self.assertEqual(events[1]["source_turn"], "0")
        with tempfile.TemporaryDirectory() as temp:
            bundle = write_bundle(events, output_dir=Path(temp) / "bundle", provenance="gmit-generated", floor_mode="recorded", input_sha256="abc", title="Test bundle")
            replay = ET.parse(bundle.replay_path).getroot()
            patient_chunks = [turn.findtext("text") for turn in replay.findall("turn") if turn.attrib["actor_id"] == "patient-a"]
            self.assertEqual("".join(patient_chunks), source_text)
            receipt = json.loads(bundle.receipt_path.read_text())
            self.assertEqual(receipt["event_count"], 2)
            self.assertEqual(replay.find("turn").attrib["stage"], "unknown")
            self.assertEqual(replay.find("turn").attrib["act"], "GREET_CLOSE")
            self.assertEqual(replay.find("turn").attrib["source_event_id"], events[0]["event_id"])

    def test_csv_preserves_quoted_newline(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.csv"
            path.write_text('episode,speaker,text\n1,Therapist,"first line\nsecond line"\n', encoding="utf-8")
            rows = read_rows(path, "csv")
            self.assertEqual(rows[0]["text"], "first line\nsecond line")

    def test_rejects_mixed_session_and_whitespace_only_text(self) -> None:
        rows = [
            {"session_id": "one", "speaker": "Therapist", "text": "hello"},
            {"session_id": "two", "speaker": "Patient A", "text": "hi"},
        ]
        with self.assertRaises(BundleError):
            select_episode(rows, None)
        with self.assertRaises(BundleError):
            canonicalize_rows([{"speaker": "Patient A", "text": " \t "}], session_id="x", condition="unknown")
        with self.assertRaises(BundleError):
            split_playback_text(" " * 221 + "x")

    def test_crlf_csv_round_trips_through_java_dom(self) -> None:
        original = "first\tline\r\nsecond & < >"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "input.csv"
            with source.open("w", encoding="utf-8", newline="") as handle:
                handle.write('speaker,text\r\nTherapist,"first\tline\r\nsecond & < >"\r\n')
            rows = read_rows(source, "csv")
            self.assertEqual(rows[0]["text"], original)
            events = canonicalize_rows(rows, session_id="source-session", condition="unknown")
            bundle = write_bundle(events, output_dir=root / "bundle", provenance="transcript-import", floor_mode="recorded", input_sha256="abc", title="CRLF")
            self.assertEqual(ET.parse(bundle.replay_path).findtext("turn/text"), original)
            if shutil.which("java") and shutil.which("javac"):
                checker = root / "CheckXml.java"
                checker.write_text('import java.nio.file.*; import java.nio.charset.StandardCharsets; import javax.xml.parsers.*; public class CheckXml { public static void main(String[] a) throws Exception { String actual=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(a[0]).getElementsByTagName("text").item(0).getTextContent(); String expected=new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8); if (!actual.equals(expected)) throw new AssertionError("DOM changed text"); }}', encoding="utf-8")
                expected = root / "expected.txt"
                expected.write_text(original, encoding="utf-8", newline="")
                subprocess.run(["javac", "-d", str(root), str(checker)], check=True, capture_output=True, text=True)
                subprocess.run(["java", "-cp", str(root), "CheckXml", str(bundle.replay_path), str(expected)], check=True, capture_output=True, text=True)

    def test_private_outputs_and_loopback_hosts(self) -> None:
        allowed = private_output_path(REPOSITORY_ROOT / "integrations/gmit/local/test")
        self.assertTrue(allowed.is_relative_to(REPOSITORY_ROOT / "integrations/gmit/local"))
        with self.assertRaises(BundleError):
            private_output_path(REPOSITORY_ROOT / "public-output")
        self.assertEqual(_loopback_ollama_host("http://127.0.0.1:11434/"), "http://127.0.0.1:11434")
        for host in ("http://example.com", "http://user@localhost", "http://localhost/other", "https://localhost"):
            with self.assertRaises(BundleError):
                _loopback_ollama_host(host)

    def test_rejects_error_and_multiple_episode_without_selection(self) -> None:
        with self.assertRaises(BundleError):
            canonicalize_rows([{"speaker": "Patient A", "text": "[LLM call failed]"}], session_id="x", condition="unknown")
        rows = [{"episode": "1", "speaker": "Therapist", "text": "one"}, {"episode": "2", "speaker": "Therapist", "text": "two"}]
        with self.assertRaises(BundleError):
            select_episode(rows, None)
        self.assertEqual(select_episode(rows, "2"), [rows[1]])

    def test_case_alias_cannot_bypass_private_output_guard(self) -> None:
        alias = REPOSITORY_ROOT.with_name(REPOSITORY_ROOT.name.swapcase())
        if not alias.exists() or not alias.samefile(REPOSITORY_ROOT):
            self.skipTest("filesystem has no case alias for the checkout")
        with self.assertRaises(BundleError):
            private_output_path(alias / "public-output")
        allowed = private_output_path(alias / "integrations/gmit/local/case-check")
        self.assertEqual(allowed.name, "case-check")

    def test_labelled_transcript_is_strict(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.txt"
            path.write_text("Therapist: hello\nPatient A: exact text\n", encoding="utf-8")
            self.assertEqual(len(read_rows(path, "labelled")), 2)
            path.write_text("Therapist: hello\nunlabelled\n", encoding="utf-8")
            with self.assertRaises(BundleError):
                read_rows(path, "labelled")


if __name__ == "__main__":
    unittest.main()
