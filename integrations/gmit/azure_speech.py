"""Synthesize one Java-prepared Jenny SSML utterance with Azure Speech.

The timing XML is the publication marker: the WAV is atomically published
first, then the XML is atomically published only after successful synthesis and
PCM validation.  This module deliberately prints no SSML, credential, region,
or provider error detail.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import sys
import tempfile
import wave
from xml.etree import ElementTree as ET


VOICE = "en-US-JennyNeural"
SAMPLE_RATE = 48_000
TICKS_PER_SECOND = 10_000_000
REGION = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")


class AzureSpeechError(RuntimeError):
    """A safe, non-diagnostic failure exposed to the Java caller."""


def _config_dir() -> Path:
    configured = os.environ.get("GRETA_AZURE_CONFIG_DIR")
    return Path(configured) if configured else Path.cwd() / "Common" / "Data" / "AzureTTS"


def _credentials() -> tuple[str, str]:
    key = os.environ.get("SPEECH_KEY")
    region = os.environ.get("SPEECH_REGION")
    if not (key and region):
        try:
            root = _config_dir()
            key = (root / "SPEECH_KEY.txt").read_text(encoding="utf-8-sig").strip()
            region = (root / "SPEECH_REGION.txt").read_text(encoding="utf-8-sig").strip()
        except OSError as exc:
            raise AzureSpeechError("Azure speech configuration is unavailable.") from exc
    if not key or not region or not REGION.fullmatch(region):
        raise AzureSpeechError("Azure speech configuration is unavailable.")
    return key, region


def _read_jenny_ssml(path: Path) -> str:
    try:
        payload = path.read_text(encoding="utf-8-sig")
        root = ET.fromstring(payload)
    except (OSError, ET.ParseError) as exc:
        raise AzureSpeechError("Azure speech input is invalid.") from exc
    voices = [element for element in root.iter() if element.tag.rsplit("}", 1)[-1] == "voice"]
    if len(voices) != 1 or voices[0].get("name") != VOICE:
        raise AzureSpeechError("Azure speech input is invalid.")
    return payload


def _temporary_target(target: Path, suffix: str) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(prefix=f".{target.name}.", suffix=suffix, dir=target.parent, delete=False)
    handle.close()
    return Path(handle.name)


def _timing_xml(visemes: list[tuple[int, int]], bookmarks: list[tuple[str, int]], frames: int) -> ET.Element:
    root = ET.Element(
        "azure-speech",
        {
            "voice": VOICE,
            "sample-rate": str(SAMPLE_RATE),
            "duration-ticks": str(frames * TICKS_PER_SECOND // SAMPLE_RATE),
        },
    )
    for viseme_id, ticks in visemes:
        ET.SubElement(root, "viseme", {"id": str(viseme_id), "ticks": str(ticks)})
    for bookmark_id, ticks in bookmarks:
        ET.SubElement(root, "bookmark", {"id": bookmark_id, "ticks": str(ticks)})
    return root


def synthesize(ssml_path: Path, wav_path: Path, timing_path: Path) -> None:
    if wav_path.exists() or timing_path.exists():
        raise AzureSpeechError("Azure speech output already exists.")
    ssml = _read_jenny_ssml(ssml_path)
    key, region = _credentials()
    try:
        import azure.cognitiveservices.speech as speechsdk
    except ImportError as exc:
        raise AzureSpeechError("Azure speech SDK is unavailable.") from exc

    temporary_wav: Path | None = None
    temporary_timing: Path | None = None
    try:
        temporary_wav = _temporary_target(wav_path, ".wav")
        configuration = speechsdk.SpeechConfig(subscription=key, region=region)
        configuration.set_speech_synthesis_output_format(
            speechsdk.SpeechSynthesisOutputFormat.Riff48Khz16BitMonoPcm
        )
        synthesizer = speechsdk.SpeechSynthesizer(speech_config=configuration, audio_config=None)
        visemes: list[tuple[int, int]] = []
        bookmarks: list[tuple[str, int]] = []

        def on_viseme(event) -> None:
            viseme_id, ticks = event.viseme_id, event.audio_offset
            if isinstance(viseme_id, int) and isinstance(ticks, int) and viseme_id >= 0 and ticks >= 0:
                visemes.append((viseme_id, ticks))

        def on_bookmark(event) -> None:
            bookmark_id, ticks = event.text, event.audio_offset
            if isinstance(bookmark_id, str) and bookmark_id and isinstance(ticks, int) and ticks >= 0:
                bookmarks.append((bookmark_id, ticks))

        synthesizer.viseme_received.connect(on_viseme)
        synthesizer.bookmark_reached.connect(on_bookmark)
        result = synthesizer.speak_ssml_async(ssml).get()
        if result.reason != speechsdk.ResultReason.SynthesizingAudioCompleted:
            raise AzureSpeechError("Azure speech synthesis did not complete.")
        temporary_wav.write_bytes(result.audio_data)
        with wave.open(str(temporary_wav), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, SAMPLE_RATE, "NONE"):
                raise AzureSpeechError("Azure speech returned an unexpected audio format.")
            frames = wav.getnframes()
        temporary_timing = _temporary_target(timing_path, ".xml")
        ET.ElementTree(_timing_xml(visemes, bookmarks, frames)).write(
            temporary_timing, encoding="utf-8", xml_declaration=True
        )
        os.replace(temporary_wav, wav_path)
        temporary_wav = None
        os.replace(temporary_timing, timing_path)
        temporary_timing = None
    except AzureSpeechError:
        raise
    except Exception as exc:
        raise AzureSpeechError("Azure speech synthesis failed.") from exc
    finally:
        for temporary in (temporary_wav, temporary_timing):
            if temporary is not None:
                try:
                    temporary.unlink()
                except OSError:
                    pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ssml", type=Path)
    parser.add_argument("wav", type=Path)
    parser.add_argument("timing_xml", type=Path)
    arguments = parser.parse_args(argv)
    try:
        synthesize(arguments.ssml, arguments.wav, arguments.timing_xml)
    except AzureSpeechError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
