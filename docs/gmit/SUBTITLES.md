# Transcript subtitles and MP4 export

The transcript supplies the exact subtitle text. Display names are
**Facilitator**, **A**, and **B**; internal actor IDs remain unchanged.
No ASR, translation, paraphrasing, or additional model call is involved.

## Record the animated scene

After deploying the current allowlist, run this in Windows PowerShell, replacing
the replay path with your generated/imported bundle's absolute Windows path:

```powershell
& 'C:\Users\alafate abulimiti\research\workspace\greta_2.1\bin\GMIT\Start-GMIT.ps1' `
  -Replay 'C:\absolute\path\to\replay.xml' `
  -MeaningMiner -RecordVideo -Muted `
  -JavaHome 'C:\Program Files\Java\jdk1.8.0_111' `
  -StartDelaySeconds 15 -TurnGapSeconds 3 -EndHoldSeconds 10
```

`-MeaningMiner` enables the three actors' semantic gestures. Listener gaze and
seeded exclusive nods retain their current defaults; a subtitle option does not
enable gestures. `-Muted` suppresses physical speaker output during recording
while retaining synthesis, lip animation, audio scheduling and WAV output.
The finished movie includes those WAVs as audible audio. Omit `-Muted` to hear
the live run as well. Do not use the retired MODIFF launcher.

`-RecordVideo` captures the actual Ogre scene and draws the active caption into
each PNG's pixels. It suppresses the separate Java caption window. The resulting
MP4 therefore has permanent subtitles inside the image, visible without loading
an external subtitle track. Recording still opens Greta while it renders; it is
not a headless renderer. Keep the run directory, including its WAV/PNG/JSON
files, until encoding finishes. Cancellation/failure leaves partial evidence.

Without `-RecordVideo`, ordinary `-Replay`/`-GroupMI` uses a separate owned
desktop subtitle overlay and still exports SRT/JSON. That overlay is not part of
Ogre's image. Use movie recording for embedded subtitles. Import other transcript
formats first as described in [REPLAY.md](REPLAY.md).

## Encode the MP4

The launcher prints a unique runtime run directory. After it exits successfully,
use that directory in a WSL terminal:

```sh
cd '/mnt/c/Users/alafate abulimiti/research/workspace/greta'
PYTHONDONTWRITEBYTECODE=1 python3 -m integrations.gmit.export_video \
  --run-dir '/mnt/c/Users/alafate abulimiti/research/workspace/greta_2.1/bin/GMIT/local/replay-YYYYMMDD-HHMMSS-mmm' \
  --ffmpeg /home/linuxbrew/.linuxbrew/bin/ffmpeg \
  --ffprobe /home/linuxbrew/.linuxbrew/bin/ffprobe
```

The two executable paths above are the existing local installation. Elsewhere,
install FFmpeg/FFprobe with H.264 and AAC support and supply their actual paths
or place them on `PATH`. The encoder itself uses Python's standard library.
It creates `playback.mp4` and `video-export-receipt.json` in that run directory,
refuses to overwrite output, and checks the encoded streams before publication.
`--output /absolute/path/movie.mp4` selects another unused output filename.
Completed captures are required by default; `--allow-partial` is an explicit
diagnostic option and retains partial status in the receipt.

## Timing and evidence

`subtitles.srt` is UTF-8, with first cue at zero corresponding to the first Greta
`Audio.start` after synthesis. Later cues preserve the actual scheduled gaps.
The MP4 starts at that same origin and mixes each WAV at its scheduled offset.
Initial loading/countdown is omitted. These are Greta scheduling timestamps,
not sound-device latency measurements; no word timestamps are invented.

`subtitles.json` preserves raw original text and actor/event/source-turn/chunk
links, absolute Greta Timer bounds, relative cue bounds, and timing origin.
SRT/display whitespace is normalized; literal SRT markup is escaped. The sidecar
records `scheduled_audio_only: true` and
`physical_audio_completion_observed: false`. Both subtitle and capture status
remain partial until playback and cleanup succeed; paired finalization failure
downgrades both. A scheduled final cue alone does not establish completion.

`video-frames.json` records actual captured-frame times, dimensions, audio/WAV
mapping, and completion status. The encoder validates local paths, dimensions,
cue/audio correspondence and WAV durations. It uses the captured time gaps,
H.264/yuv420p video, AAC audio and fast-start metadata. Its 25 fps output may
duplicate frames when capture is slower. PNG readback/encoding adds overhead;
this is not a real-time 25 fps guarantee or a new collision guarantee.

The existing MeaningMiner fail-closed return limitation remains documented in
[GROUP_MI.md](GROUP_MI.md#listener-nod-visibility-2026-11-09). A successful bounded
recording does not establish repeatable completion for every transcript.

## Deployment and recovery

The allowlist contains eighteen files. New `bin/GMIT/ReplaySubtitles.java` and
`bin/GMIT/ReplayVideo.java`, and updated `SmokeProbe.java`/`Start-GMIT.ps1`, map to
the same relative runtime paths. Tests and NetBeans launcher source stay in
the development checkout. Existing JARs, resources, voices and checkpoints are
retained. No private output is committed.

This session's deployment receipts, in deployment order, are:

1. `deploy-20261109T124407890315Z/receipt.json` (subtitle helper, seventeen files).
2. `deploy-20261109T131940033814Z/receipt.json` (video helper, eighteen files).
3. `deploy-20261109T132808309776Z/receipt.json` (record-mode startup and finalization).
4. `deploy-20261109T133051063037Z/receipt.json` (capture frames during end hold).

To restore the preceding runtime state, use
`integrations/gmit/deploy.py --runtime <absolute runtime root> --rollback
<absolute receipt path>` for this session's receipts in reverse order. The tool
checks deployed hashes and preserves subsequent edits. A helper with no original
is removed only if its deployed hash still matches. Keep a matching source/IDE
launcher when using the older runtime.

## Verification

Windows JDK 8 compilation, headless subtitle/video tests and PowerShell syntax
checks passed. Tests cover first-audio origin, gaps, raw text, Unicode/markup,
caption pixels, frame cadence, backwards time, output overwrite refusal and
paired partial status on finalization failure. Encoder tests use real synthetic
PNG/WAV inputs and FFmpeg, including timing, audio level, path quoting and
concurrent output protection. Native movie acceptance is recorded separately
from these tests and scheduled callbacks.

The real migrated Pure episode's native recording
`replay-20261109-142842-574` completed four F-A-B-F turns with MeaningMiner and
hand safety enabled, four selected semantic gestures across all three actors,
four audio schedules and four subtitle cues. It recorded exclusive ten-degree
nods for the facilitator and patient A. Its `playback.mp4` contains H.264
920 x 576 video (38.960 s), AAC audio (38.964 s), and a 38.964 s container. The
encoded duration matches the Greta schedule within one output frame. Root
decoded a frame from the MP4 and confirmed the visible caption and gesture;
that is separate from full audiovisual human acceptance, which is pending.

The encoder's initial real-capture check caught a short CFR output. Explicit
`fps=25` conversion now duplicates measured VFR frames correctly. The capture
contains 339 frames: measured intervals were 78 ms minimum, 115.086 ms mean,
and 1,134 ms maximum. It therefore has visible cadence limitations even though
the encoded stream is 25 fps. Recording this run used the preceding deployed
source, which holds the final image over the last one-second end hold. The
final deployment also captures end-hold frames; that small follow-up passed
compilation but has not had another native visual run. No collision or gaze
behavior was changed by this extension. The earlier failed record-mode startup
dereferenced its intentionally absent caption dialog; its null guard is fixed.

All eighteen deployed source/runtime hashes match. Seven preserved runtime
scratch files and all sixty-seven pre-existing runtime Git-status entries were
unchanged after execution. The private GMIT worklog hash was unchanged. Movie,
PNG/WAV data, private assets and all receipts remain ignored local artifacts.
