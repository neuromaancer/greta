# Local RL generation and Greta replay

The RL adapter runs one short episode using the private GMIT SAC policy and
the existing round-robin evaluation environment. The policy selects facilitator
dialogue acts; the local Ollama model supplies language generation and the
existing patient and stage-estimation behavior. This is generation followed by
replay, not simultaneous live interaction with Greta.

The selected checkpoint is a provisional canonical candidate, not a
validation-ranked winner. Its associated language model was Qwen3.5-9B;
using `qwen3:4b-instruct-2507-q4_K_M` is a separate engineering configuration.
Do not present its output as a replication of the original experiment.

## Local resources

Keep the private checkpoint outside the public repository or under ignored
`integrations/gmit/local/`. Transfer only the selected checkpoint and the
required sentence encoder files. Compare SHA-256 hashes before loading them.
The machine-local copy receipts record the source, revision, size, and hashes.
Neither private GMIT source nor its existing research service is modified.

Use a separate inference environment so the accepted Pure environment stays
unchanged. With CPython 3.12 and `uv`, from the development checkout:

```sh
uv venv --python python3.12 ~/.local/share/greta-gmit/venv-rl
uv pip install --python ~/.local/share/greta-gmit/venv-rl/bin/python \
  'torch==2.10.0' --index-url https://download.pytorch.org/whl/cpu
uv pip install --python ~/.local/share/greta-gmit/venv-rl/bin/python \
  -r integrations/gmit/requirements-rl.txt
```

Supply the actual private paths in the shell:

```sh
export GMIT_ROOT=/absolute/path/to/private/gmit
export GMIT_RL_CHECKPOINT=/absolute/path/to/policy.pt
export GMIT_RL_ENCODER=/absolute/path/to/local/all-mpnet-base-v2
```

Use the matching `sentence-transformers/all-mpnet-base-v2` encoder, not the
smaller MiniLM encoder. A local snapshot path avoids a network model download.
The policy and encoder run on CPU; Ollama can use the GPU. The adapter defaults
to four PyTorch threads. The compatible private GMIT revision is pinned in the
adapter; changing it requires interface verification.

## Check loading without an LLM

```sh
PYTHONDONTWRITEBYTECODE=1 HF_HUB_OFFLINE=1 \
  ~/.local/share/greta-gmit/venv-rl/bin/python \
  -m integrations.gmit.run_rl \
  --gmit-root "$GMIT_ROOT" \
  --checkpoint "$GMIT_RL_CHECKPOINT" \
  --encoder-model "$GMIT_RL_ENCODER" \
  --preflight-only
```

Preflight constructs the observation network and strictly loads the checkpoint
with `weights_only=True`. It does not reset an episode, contact Ollama, generate
dialogue, or write a replay bundle. A failed load never falls back to Pure.

## Generate one short RL conversation

Start the existing backend in a separate WSL terminal:

```sh
bash integrations/gmit/start_ollama.sh
```

From the development checkout, with the path variables above:

```sh
PYTHONDONTWRITEBYTECODE=1 HF_HUB_OFFLINE=1 \
  ~/.local/share/greta-gmit/venv-rl/bin/python \
  -m integrations.gmit.run_rl \
  --gmit-root "$GMIT_ROOT" \
  --checkpoint "$GMIT_RL_CHECKPOINT" \
  --encoder-model "$GMIT_RL_ENCODER" \
  --patient-turns 4 --policy-mode sampled \
  --theme 'Reducing red meat' \
  --session-id "rl-$(date +%Y%m%d-%H%M%S)" \
  --timeout-seconds 600
```

Defaults select one OPEN/RECEPTIVE patient pair, seed 42, GMIT patient style,
OPEN persona v2, and readiness disabled. Four patient turns yield seven original
utterances in F-A-B-F-A-B-F order, including the unchanged fixed opening and final
facilitator turn. The adapter uses GMIT's evaluation wrapper, not the legacy
training scheduler. Tianshou's Collector retains recurrent hidden/cell state
across policy steps. No training, policy update, batch regeneration, or evaluation
judge is started.

Each Ollama call uses an 8192-token context and a 256-token generation limit,
with a recorded seed. The existing GMIT role temperatures and behavior remain
owned by GMIT. This configuration does not promise bitwise reproducibility.

The command prints `events`, `replay`, and `receipt` paths. Outputs default to
`integrations/gmit/local/rl/<session-id>/`. Existing output is refused before
generation. The receipt preserves original rows, checkpoint checksum, GMIT
revision, model identity, policy mode, generation options, and episode counts.
Failures and error-placeholder utterances do not produce a successful replay.

## Play the result

After generation, stop the Ollama terminal with Ctrl-C if it is no longer needed.
Use the printed replay XML's Windows path in Windows PowerShell:

```powershell
& 'C:\Users\alafate abulimiti\research\workspace\greta_2.1\bin\GMIT\Start-GMIT.ps1' `
  -Replay 'C:\path\to\rl-session\replay.xml' `
  -StartDelaySeconds 10 -TurnGapSeconds 3 -EndHoldSeconds 15
```

The runtime reuses the accepted seated scene, clasped hands, gaze, speech, lip
synchronization, blinking, and captions. Replay makes no model calls. Long
utterances may be split into several speech segments without changing text or
speaker order.

In NetBeans, open the development `application/Modular` project and select
**GMIT Replay Import**. Run Modular and choose this RL `replay.xml`, or set
Arguments to `--gmit-replay "C:\path\to\rl-session\replay.xml"`. Keep Windows
64-bit JDK 8 and the complete `greta_2.1/bin` working directory. Pure and RL are
generation conditions; both use this replay profile. **GMIT Group MI** remains
the separate fixed synthetic example.

No runtime deployment is needed for this adapter: the new Python files stay in
`greta`, and the existing replay launcher reads the private output directly.
To remove the local RL setup, first stop its processes, then remove only its
separate virtual environment and copied private assets. Preserve the shared
Ollama model and Pure environment.

## Verification, 2026-10-28

The user authorized retrieving a suitable existing checkpoint over `ssh cluster`
and trying local inference. The selected 5,710,442-byte policy matched its
previously recorded SHA-256. The eleven required files of the 438,682,922-byte
MPNet snapshot also matched the cluster copies. Policy weights, encoder files,
copy receipts, and generated text remain private. No cluster job or service was
started or changed, and both GMIT checkouts remained read-only.

The no-server preflight strictly loaded the policy using PyTorch 2.10.0+cpu,
Tianshou 2.0.0, and the matching local encoder. One sampled-policy episode then
completed with one OPEN/RECEPTIVE pair, four patient turns, three Collector steps,
and seven original utterances in F-A-B-F-A-B-F order. The existing Ollama model
handled thirteen calls. Original-row text, event identifiers, actor order,
sequence numbers, and bundle checksums were verified. No training or evaluation
judge ran. The local receipt records the engineering configuration separately
from the checkpoint's associated research model.

The first replay exposed an adapter contract error before scene startup: the
generation scheduler name had been used as the playback `floor_mode`. The
adapter now emits `recorded` and preserves the generation scheduler in its
receipt. A regression test covers this boundary. The saved episode was
re-exported without another model call; event JSONL bytes stayed identical.
Greta's actual Java parser accepted the corrected seven-turn bundle.

A subsequent startup stopped safely on a stale first-audio schedule after a
51.5-second synchronous delay. A local diagnostic then timed all 28 keyframe
performer calls at 0-23 ms and completed the seven turns. The delay did not recur
in the final ordinary launch; its root cause was not established. No audio-clock
guard was weakened, timestamps were not rebased, and no automatic retry was
added. The diagnostic is local-only and is not required for startup.

The ordinary PowerShell launcher run `replay-20261028-151510-016` exited 0:
seven dispatches, seven audio callbacks, seven elapsed schedule checks, seven
speaker midpoint switches, and 28 gaze phase checks. Listener transitions were
scheduled 211-239 ms after the audio start. Seven WAVs and sixteen render
captures remain local. Ollama was stopped before playback. These software
checks do not measure physical audio timing. Actual NetBeans F6 interaction
was not repeated.

At the user's request, the same saved seven-utterance RL episode was replayed
with a 15-second opening delay in `replay-20261028-152627-939`. The ordinary
launcher exited 0 with seven dispatches, audio callbacks, and elapsed schedule
checks. No new model generation occurred. The user confirmed the voices, lip
movement, captions, actor correspondence, hand posture, and gaze were all normal
(translated from Chinese: "Everything is normal"). This supplies human
audiovisual acceptance for the RL replay path, separately from its software
scheduling checks.

Fifteen focused adapter/bundle tests passed, and independent integration review
found no remaining contract or publication blocker. No runtime JAR, avatar,
voice, room, or helper deployment was required; all twelve allowlisted runtime
files remained identical to source. The runtime's 67 pre-existing Git status
entries and recorded scratch/resource hashes were restored unchanged.
