# Facilitator gaze-conditioned MODIFF-VQ experiment

This opt-in engineering configuration uses the local Greta 2.1 VQ-VAE/E3
checkpoints to generate facilitator eyebrow motion from the facial AU history
of the patient selected by the existing gaze schedule. It does not generate
dialogue or change the Pure/RL policy, transcript, speaker order, voices, or
seated pose. Ordinary GMIT startup leaves it disabled.

## Meaning and limits of the input

The original model predicts therapist AUs from human client OpenFace features.
This experiment supplies **virtual Greta AU parameters**, not camera measurements
or inferred human emotions. It reads each patient's Face Keyframe Performer
before AU-to-FAP conversion. It does not reconstruct AUs from rendered pixels,
lip animation, or gaze angles.

The bridge maps normalized Greta intensities to the model's 0-to-5 input scale,
averages the two face sides, and maps Greta's AU43 eyelid closure to the input
AU45 column. These are explicit engineering approximations, not a calibrated
OpenFace measurement. Channels without an observed mask start neutral; subsequent
missing masks retain the preceding state. Speech-only scenarios may therefore
provide mostly neutral and blink activity. A running model alone does not prove
that its response is perceptually appropriate or sensitive to those inputs.

Each patient has a separate rolling 144-frame history (8 chunks of 18 frames).
At 25 fps the initial history takes about 5.76 seconds. Changing the facilitator's
gaze selects a different patient's history; it never concatenates histories from
different people. Target changes follow Greta Timer events, including the
speaker midpoint switch and the listener transition after speech starts.

The local model uses deterministic decoding, no previous-therapist input, no
audio features, and the existing pipeline's fixed neutral/reflection dialogue-act
defaults. It does not infer dialogue acts from the transcript. No microphone,
camera, OpenFace process, Wav2Vec model, LLM, VAP, or external API is started.

Only AU01, AU02, and AU04 are exposed as facilitator eyebrow motion. Outputs are
converted to normalized Greta units, multiplied by 0.3, and capped at 0.25.
The Java bridge additionally restricts output to eyebrow FAP channels. Existing
lip, eyelid/blink, eye-gaze, neck, body, and patient animation remain owned by
their existing chains. This isolation must be checked during actual playback.

## Local prerequisites and readiness

Use the complete `greta_2.1` runtime and its existing Windows 64-bit JDK 8.
The following files are required under `bin/Common/Data/MODIFFVQ/model/`:

| File | Role | Observed size |
| --- | --- | ---: |
| `vqvae_model.pt` | VQ-VAE checkpoint | 3,864,610 bytes |
| `predictor_model.pt` | E3 predictor checkpoint | 21,506,944 bytes |
| `norm_stats.npy` | Input/output normalization | 1,009 bytes |

The adapter imports the architecture from that same local model directory. It
strictly loads both checkpoint state dictionaries on CPU and records SHA-256
hashes of the models, normalization, and source modules. The predictor also
contains VQ-VAE weights; the local preflight found them equal to the standalone
VQ-VAE weights. Checkpoints use PyTorch's restricted weights-only loader with
the concrete NumPy scalar metadata types allowed. The original normalization
file is a pickled NumPy dictionary: use the existing trusted local bundle, not
an arbitrary downloaded replacement.

The existing WSL `venv-rl` has the required PyTorch and NumPy dependencies and
is reused without installing or changing packages. A CPU readiness probe on
2026-11-02 strictly loaded the local bundle and produced finite deterministic
18-by-17 outputs. The first 10-inference probe averaged 6.39 ms, with about
268 MiB peak process RSS and approximately 6.1 seconds to load. This is a
synthetic-tensor microbenchmark, not a measurement of concurrent rendering or
evidence of valid virtual-expression adaptation.

A subsequent recorded 10-inference check averaged 3.94 ms with 269.95 MiB RSS.
Bounded synthetic brow, frown, blink, and seeded AU inputs produced different
predictions, including different eyebrow outputs. This confirms input
sensitivity, not semantic validity. The observed host was a Core Ultra 7 165H
with 31.45 GiB RAM and an RTX 3000 Ada Laptop GPU (8,188 MiB). Only about 2.17 GiB
of Windows RAM was free at inspection, so CPU inference was used. No additional
model environment, GPU package, or checkpoint download was needed.

To repeat the bounded readiness check in **WSL**:

```bash
cd '/mnt/c/Users/alafate abulimiti/research/workspace/greta'
PYTHONDONTWRITEBYTECODE=1 \
  /home/abulimiti/.local/share/greta-gmit/venv-rl/bin/python \
  -m integrations.gmit.modiff_vq \
  --model-root '/mnt/c/Users/alafate abulimiti/research/workspace/greta_2.1/bin/Common/Data/MODIFFVQ' \
  --preflight-only
```

This command does not open Greta or start a persistent service.

## Opt-in Windows startup

After deploying the GMIT allowlist, use **Windows PowerShell** from the
development checkout:

```powershell
Set-Location 'C:\Users\alafate abulimiti\research\workspace\greta'
& '.\integrations\gmit\Start-GMIT-Modiff.ps1'
```

This starts one bounded CPU worker in WSL, waits for strict model loading, then
plays the authored group MI scenario in the complete Windows runtime. To use a
previously generated Pure/RL or imported transcript instead:

```powershell
& '.\integrations\gmit\Start-GMIT-Modiff.ps1' `
  -Replay 'C:\absolute\path\to\replay.xml'
```

Use `-Preview` for a silent layout check. `-Runtime`, `-Distribution`,
`-PythonPath`, and `-JavaHome` override local locations. The Python path defaults
to `$GRETA_MODIFF_PYTHON`, then the WSL user's existing
`~/.local/share/greta-gmit/venv-rl/bin/python`. This is a Windows launcher using
WSL for CPU inference; it is not a native Windows Python installation.

The caption's Stop button cancels playback. The wrapper requests worker shutdown
in its cleanup path; the worker also has a finite maximum lifetime. Ordinary
`bin/GMIT/Start-GMIT.ps1` startup requires no worker and keeps MODIFF disabled.
Existing NetBeans profiles compile the additional helper but do not start a
model. A manually supplied `gmit.modiff.dir` is forwarded to the child process;
that advanced path requires a ready worker and has separate acceptance needs.

## Transport, deployment, and evidence

The worker and renderer exchange bounded files in an ignored local mailbox.
There are no TCP listeners. A request contains a unique ID, patient identity,
target epoch, input-end frame, and 144 rows of 17 AU values. A response repeats
that identity and contains 18 rows for the three permitted eyebrow AUs. Only one
request may be outstanding. Old-target replies are discarded, malformed replies
and worker failures are explicit, and inference cannot resend dialogue.

The new deployment mapping is
`greta/bin/GMIT/GazeModiffAdapter.java` to
`greta_2.1/bin/GMIT/GazeModiffAdapter.java`. The existing probe, gaze helper, and
PowerShell launcher are updated through the same allowlist. Python, its Windows
wrapper, tests, and this document remain in the development repository. No model,
JAR, voice, mesh, or resource tree is deployed. Deployment receipts preserve all
previous files and support reverse-order rollback as described in `PROJECT.md`.

Keep model receipts, mailbox data, captures, logs, and diagnostics under ignored
`bin/GMIT/local/`. Distinguish checkpoint existence, strict loading, input
sensitivity, target routing, observed channel isolation, and human visual
acceptance when reporting results. Synthetic input is never a real OpenFace
recording. Using this experiment with Pure and RL should use the same settings
in both conditions.

## Integration evidence, 2026-11-02

The strict-load and input-sensitivity receipt is local at
`greta_2.1/bin/GMIT/local/modiff-readiness-20261102/receipt.json`.
Nine Python tests and the headless Java checks passed. The Java checks exercise
future-frame isolation, partial left/right masks, independent patient histories,
exact history length, full reply identity, finite output bounds, and stopped
worker rejection. The helpers and NetBeans launcher compiled with Windows JDK 8;
the two PowerShell scripts passed syntax checks.

The silent opt-in preview `group-mi-20261102-143253-373` completed with six
accepted model replies across both patient targets and observed nonzero
facilitator brow channels. The ordinary recorded replay
`replay-20261102-143453-023` then completed all seven utterances in the original
F-A-B-F-A-B-F order. It produced seven intended audio callbacks, seven elapsed
schedule checks, 28 gaze checks, and 103 accepted MODIFF replies across eight
target changes. Replies arrived one or two Greta frames after their request.
The adapter observed nonzero facilitator brow channels during 1,791 sampled
frames and emitted only its eight permitted brow FAP masks. Patient AU input
was observed from both original face chains. There were no stale replies in this
particular run; stale identity rejection is covered by headless checks.

The run retained the seated pose, independent actor chains, and the existing
listener delay (205 to 233 ms after audio start). Six runtime scratch hashes were
unchanged after cleanup. The worker exited with status 0 and marked itself
stopped. These initial runs established programmatic and render evidence.

During the repeat `replay-20261102-150333-745`, the user confirmed on 2026-11-02
that the facilitator's eyebrow changes were visible and that sound, lip
synchronization, blinking, and gaze were normal. Translated user feedback:
"Eyebrow changes are visible; everything else is normal." This establishes
human audiovisual acceptance of this short facilitator-only engineering replay;
it does not establish OpenFace-equivalent input validity or the semantic
appropriateness of the generated expressions.
The repeat also completed all seven utterances and 28 gaze checks, applied 109
model replies, and discarded two replies whose gaze target epoch had changed.

The default-off silent preview `group-mi-20261102-143901-756` also completed,
with nine gaze checks and no MODIFF bridge activity or model worker. The runtime
Git status retained all 67 pre-existing entries without a new tracked change;
the two checkpoints and normalization file retained their original hashes.
An independent integration review checked the actual differences and run
evidence and found no blocking issue for publishing this opt-in experiment.

This task deployed two receipts, in order:
`deploy-20261102T133017130900Z/receipt.json` and
`deploy-20261102T133428444419Z/receipt.json`, under runtime `bin/GMIT/local/`.
All thirteen allowlisted source/runtime hashes match. To restore the runtime
state from before this experiment, roll back both receipts in reverse order:

```bash
cd '/mnt/c/Users/alafate abulimiti/research/workspace/greta'
for receipt in deploy-20261102T133428444419Z deploy-20261102T133017130900Z; do
  python3 integrations/gmit/deploy.py \
    --runtime '/mnt/c/Users/alafate abulimiti/research/workspace/greta_2.1' \
    --rollback "/mnt/c/Users/alafate abulimiti/research/workspace/greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```

Rollback preserves local evidence and does not change development Git history.
Using the ordinary launcher without `-ModiffDirectory` disables the experiment
without needing rollback. NetBeans MODIFF execution, richer patient expression
inputs, long-session stability, and validated OpenFace-equivalent adaptation
remain unverified.
