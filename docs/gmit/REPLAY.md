# Pure generation and offline transcript replay

The public adapter imports the separately installed, private GMIT project. It
does not contain GMIT prompts, personas, transcripts, or policy checkpoints.
Generated and imported bundles remain under ignored local directories.

Two paths feed the same seated Greta scene:

1. A bounded Pure episode uses the existing GMIT strict round-robin function,
   one patient pair, and the selected local Ollama model. Its completed rows
   are converted to a replay bundle.
2. Offline import converts an existing transcript to the same bundle. Import
   uses only Python's standard library; playback requires neither Ollama nor
   the GMIT Python environment.

This is generation followed by playback. It does not synchronize ongoing GMIT
decisions with live audio, and it is not a reproduction of an original research
model configuration. The original authored demo remains available separately.

## Import an existing transcript

Run from the development checkout in WSL, replacing the input with a local file:

```sh
python3 -m integrations.gmit.importer /absolute/path/to/transcript.csv \
  --session-id offline-example --title 'Imported group conversation'
```

Supported structured formats are CSV, JSON row arrays, and JSONL. Use the
`speaker` and `text` fields; Therapist, Facilitator, Patient A, and Patient B
map to the three existing avatars. Available act, stage, and source-turn
annotations are retained. Inputs containing multiple episodes require explicit
selection. The importer also supports one labelled utterance per text line.

The command prints paths for `events.jsonl`, `replay.xml`, and a local receipt.
Default output is `integrations/gmit/local/imports/<session-id>/`. Existing
output is never silently replaced; choose a new session ID for another import.
Unknown roles, model-error placeholders,
and ambiguous inputs fail instead of being rewritten or dropped.

The original utterance text lives in `events.jsonl`. Long utterances become
playback chunks of at most 160 characters linked to the original event. Concatenating those
chunks recovers the original text. Chunking changes speech segmentation, not
content or speaker order. See [the event contract](../../integrations/gmit/CONTRACT.md).

## Play the imported bundle

From Windows PowerShell, use the Windows path to the generated XML:

```powershell
& 'C:\Users\alafate abulimiti\research\workspace\greta_2.1\bin\GMIT\Start-GMIT.ps1' `
  -Replay 'C:\path\to\replay.xml'
```

The scene uses the same table, seated avatars, voice/lip chains, and blinking as
the accepted group demo. Captions distinguish imported playback from generated
GMIT output and authored examples. Stop through the caption button and allow
cleanup to finish. The launcher restores its four existing runtime scratch
files after its child exits. It never calls a model or changes the transcript.

In NetBeans, open the development `application/Modular` project, select Windows
64-bit JDK 8, and use the **GMIT Replay Import** configuration. Its arguments are
`--gmit-replay`; Run Modular opens an XML file chooser. Choose the generated
`replay.xml`. Alternatively supply `--gmit-replay "C:\path\to\replay.xml"` in
Run arguments. Keep the complete runtime `bin` as Working Directory. The same
parent/child cleanup limitations as [the group demo](GROUP_MI.md#run-modular-from-netbeans)
apply if the IDE forcibly kills the parent process.

## Local Pure backend

The selected model is `qwen3:4b-instruct-2507-q4_K_M`. Its non-thinking Instruct
variant avoids adding a reasoning-mode adapter to GMIT. The model is shared by
the existing role clients; dialogue state remains separate in GMIT.

The local installation uses `/home/abulimiti/.local/share/greta-gmit/` for Ollama,
its model store, and an isolated Python environment. Override `GMIT_LOCAL_ROOT`
and `OLLAMA_BIN` for another installation. Nothing is installed into GMIT or
the complete Greta resource tree, and no system service is enabled.

The verified Ollama package is version `0.40.11`, from the
[official release](https://github.com/ollama/ollama/releases/tag/v0.40.11).
Its Linux amd64 archive SHA-256 is
`7bedab90dd122661be78798e9f0edf37fccf0cccf2699d2c58ea1a3afdc04b6d`.
Follow the [manual installation procedure](https://docs.ollama.com/linux),
extracting that archive into an isolated user directory instead of system paths.

The Pure import path needs only the packages pinned in
`integrations/gmit/requirements-pure.txt`, verified on CPython 3.12. It does not
load a sentence encoder, SAC checkpoint, or training stack. For another machine:

```sh
uv venv --python python3.12 ~/.local/share/greta-gmit/venv
uv pip install --python ~/.local/share/greta-gmit/venv/bin/python \
  -r integrations/gmit/requirements-pure.txt
```

Start the local server in a separate WSL terminal:

```sh
bash integrations/gmit/start_ollama.sh
```

The script binds to WSL loopback by default, allows one loaded model and one
parallel request, and sets an 8192-token context. It runs in the foreground;
Ctrl-C stops this server. Model files remain available for the next launch.
GMIT's native Ollama route uses `http://127.0.0.1:11434`, not an OpenAI `/v1`
endpoint. This local adapter accepts only loopback HTTP hosts. Windows-hosted
or remote services require a separately reviewed network configuration.

Download the selected model once, with the same model-store setting:

```sh
OLLAMA_HOST=127.0.0.1:11434 \
  ~/.local/share/greta-gmit/ollama-v0.40.11/bin/ollama pull \
  qwen3:4b-instruct-2507-q4_K_M
```

After preflight, run one short episode from the development checkout:

```sh
PYTHONDONTWRITEBYTECODE=1 ~/.local/share/greta-gmit/venv/bin/python \
  -m integrations.gmit.run_pure --gmit-root /home/abulimiti/workspace/gmit \
  --patient-turns 4 --theme 'Reducing red meat' --timeout-seconds 600
```

`--gmit-root` or `GMIT_ROOT` must identify the private checkout. Set a fresh
`--session-id` for another generation; successful output is never overwritten.
The engineering defaults are seed 42, an 8192-token context, and a maximum of
256 generated tokens per model call. Python, NumPy, and model-call seeds are
recorded; this does not promise bitwise reproducibility across backend versions.
The existing role-specific temperatures, GMIT patient style, OPEN/RECEPTIVE
patient types, readiness disabled, and OPEN persona v2 remain explicit choices.

Four patient turns mean two A/B cycles: seven original utterances including
the existing fixed opening and final facilitator response. The other six
utterances are generated through GMIT's LLM calls. The existing function controls
the dialogue, patient behavior, and act selection; this adapter does not
implement a replacement scheduler. Use the printed replay XML with the same
PowerShell or NetBeans entry above. Generation failures do not become playable
successes. There is no automatic RL fallback.

## Deployment and recovery

Only the existing allowlisted runtime helpers are redeployed. Adapter Python,
NetBeans source/configuration, and this documentation remain in `greta`.
Transcripts and run receipts are local artifacts, not deployment-manifest
entries. Preserve the complete package's existing resource files.

Use [the standard deployment tool and rollback procedure](PROJECT.md#explicit-deployment-and-recovery).
The replay launcher retains local audio/render evidence separately from source
data. Software scheduling and buffer observations do not alone prove physical
audio completion; record human observation separately.

## RL selection boundary

Read-only inspection found documented previously evaluated policy candidates,
but no suitable development-validation ranking establishing a best checkpoint.
A provisional canonical candidate and its recorded checksum are retained in a
private local selection receipt. The checkpoint was initially absent locally,
so its bytes, strict loading, and behavior were not validated during the Pure
extension.
Held-out results were not used to rank candidates. Using it with the selected
4B language model would require a new compatibility/behavior check; no RL
execution or training was performed for this extension.

The subsequent local RL integration copies the selected checkpoint and matching
encoder from cluster, checks their hashes, strictly loads the CPU policy, and
generates one bounded episode with the existing scheduler. See [RL.md](RL.md)
for its separate environment, startup commands, and current acceptance evidence.

## Verified Pure generation and replay, 2026-10-27

One episode completed with OPEN/RECEPTIVE patients, the red-meat-reduction theme,
and four patient turns. The existing GMIT function returned seven utterances in
F-A-B-F-A-B-F order, including its fixed opening. The model identity was
`qwen3:4b-instruct-2507-q4_K_M`, digest
`0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0`.
The stored model package is 2,497,293,803 bytes; that is not its runtime VRAM
requirement. The first measured episode, including process/import/model startup,
took 122.32 seconds. Two-second GPU samples reached 4,601 MiB total used memory,
including the desktop; Greta was not rendering concurrently with generation.
No throughput or concurrent-rendering performance claim follows from this check.

Ollama was stopped and its loopback endpoint was verified unreachable before
replay. The PowerShell replay run `replay-20261027-154008-011` exited 0 with
seven dispatches, seven scheduled intervals, and exactly seven audio callbacks:
F=3, A=2, B=2. Audio durations ranged from 4.281 to 11.541 seconds. Each actor
had 707 face samples; blinking and inactive-mouth checks passed, and all seated
pose channels remained verified. Seven WAVs and five render captures stay local.
The user confirmed (translated from Chinese): “Audio, lip movement, captions,
and speaker correspondence are all normal.”

The original rows, model/configuration identity, input/output checksums, and
generation logs remain in ignored local bundles. GMIT source and its existing
modified work log were not changed. No policy or encoder was loaded by Pure.

## Verified offline import and replay, 2026-10-27

One complete, previously generated GMIT episode was selected explicitly from a
private simulation CSV. It contains 61 original utterances: F=31, A=15, B=15.
Its condition and topic are not established by the available metadata and remain
unknown. It is not human-participant data. Exact CSV-to-event text equality and
event-to-chunk reconstruction were checked, together with actor and order
preservation. The original ending is retained; no closing utterance was added.

An initial 74-chunk replay stopped safely at chunk 16: its synthesized audio
lasted 15.283 seconds, exceeding the existing 15-second bound. The importer
default was reduced from 220 to 160 characters and the same source episode was
imported again without changing its text. The corrected bundle has 88 chunks.
Character count is a practical segmentation limit, not a guarantee of speech
duration for every voice or language; the audio-duration guard remains active.

The final source-entry run `netbeans-group-mi-20261027-154956-159` completed all
88 dispatches, audio callbacks, and scheduled intervals in the recorded order:
F=56, A=17, B=15 playback chunks. Audio durations were 1.052-11.798 seconds.
Each actor had 7,471 face samples; blinking, inactive-mouth, and seated-pose
checks passed. Eight render captures and 88 WAV files remain local. The model
server was stopped throughout import and playback. No automatic retry or
generation occurred during replay.

The user confirmed the offline path (translated from Chinese): "Offline import
playback is normal." That observation is separate from the final complete
88-chunk software trace; it does not establish human observation of every chunk
in that later run. Scheduled completion is not a physical-audio completion ACK.

Windows JDK 8 compiled and executed the changed Modular source entry with an
explicit replay path. Its class origin was the local source-check build; all
42 graph modules came from the complete runtime package. This verifies the
source-entry replay route, but not an actual NetBeans F6 action, file-chooser
interaction, or full IDE dependency rebuild. The packaged Modular JAR was not
replaced. Eight focused adapter tests passed, including private-output path
checks on NTFS case aliases and CRLF preservation through Java's XML parser.

The launcher restored its four scratch files. All seven saved runtime baseline
hashes matched afterward, and the complete runtime's 67 pre-existing Git status
entries were unchanged. All eleven allowlisted deployed files matched source
and receipt hashes. Only `bin/GMIT/SmokeProbe.java` and `bin/GMIT/Start-GMIT.ps1`
changed bytes relative to the previous deployment; each maps to the same path
under `greta_2.1`. No resource tree or runtime JAR was replaced.

The deployment receipt is
`greta_2.1/bin/GMIT/local/deploy-20261027T143257375183Z/receipt.json`.
From the development checkout, restore the previous helper versions with:

```sh
python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
  --rollback ../greta_2.1/bin/GMIT/local/deploy-20261027T143257375183Z/receipt.json
```
