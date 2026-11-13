# GMIT in Greta

Updated 2026-11-09. P0 inspection and synthetic P1 acceptance are complete.
Pure LLM is now the only active dialogue condition: after the existing fixed
opening, the LLM selects facilitator dialogue acts and generates utterances.
Use [REPLAY.md](REPLAY.md) for generation
and offline playback. Historical RL code and weights remain preserved but are
excluded from current runs. Pure generation now runs from the MIT-licensed
`integrations/gmit/pure/` port in Greta without importing a private GMIT checkout.
Its fifteen original few-shot pairs remain an ignored local asset; they are not
published with the code. See [PURE.md](PURE.md) for provenance and startup.
Transcript replay exports synchronized SRT/JSON. `-RecordVideo` captures the
rendered scene with subtitles burned into its pixels, and the separate local
encoder produces an MP4 with synthesized audio. Display names are Facilitator,
A, and B; see [SUBTITLES.md](SUBTITLES.md). MeaningMiner remains opt-in through
`-MeaningMiner`; subtitles alone do not enable semantic gestures.
The [six controlled transcript drafts](CONDITIONS.md) illustrate a proposed
task/social by buddy/individualist 2 x 3 design. They are explicitly
assistant-authored synthetic stimuli, not raw local-Qwen simulation output;
the earlier automatic long-session drafts were rejected in text review.
The November 9 listener-nod update requests ten degrees over 1.36 seconds while
preserving seeded random triggering, fairness, and exclusivity. The existing
Pure seven-turn replay passed with all actor audio schedules, exact returns,
1,553 projected frames, and 600 observed samples. A separate guarded return fix
preserves the one-degree corrective release bound by retaining coupled shoulder
directions. See [GROUP_MI.md](GROUP_MI.md#listener-nod-visibility-2026-11-09) for
the current deployment and complete rollback sequence. The user accepted the
ten-degree nod and other behavior in the observed portion of a requested repeat;
that repeat later stopped during the final hand return. This is not evidence
of repeatable seven-turn completion; the fail-closed limitation is recorded in
the same verification section.
The authored group MI extension adds a 13-turn conversation about reducing
red meat, with a table and three seated characters. See [GROUP_MI.md](GROUP_MI.md)
for launch commands, NetBeans settings, and evidence.
The seated scene also supports per-avatar tabletop rests and listener gaze;
their calibration and acceptance records are in the same document. Current
rests use close, wide, and asymmetric palms-down placement. The earlier
2026-10-30 clasp calibration remains a historical record.
The user subsequently authorized committing and pushing these development files
to `feat/gmit-multiparty`. Initial P0/P1 work did not run an LLM. The later local
Pure and offline-import extension is described in [REPLAY.md](REPLAY.md): one
local-model episode produced seven utterances and replayed successfully; a
complete imported episode preserved 61 utterances across 88 playback chunks.
The local RL extension in [RL.md](RL.md) adds a separate CPU inference
environment, a hash-verified private checkpoint and encoder, and one bounded
episode through the existing round-robin evaluation wrapper.
Training, batch evaluation, GMIT source edits, and runtime-repository commits
remain outside this integration.

The earlier facilitator-only MODIFF-VQ experiment was retired from the active
workflow at the user's request on November 7. Start current replays through
`bin/GMIT/Start-GMIT.ps1`, without a MODIFF worker, WSL, or Python model loading.
Its implementation and local checkpoint resources remain preserved; see the
historical [MODIFF_VQ.md](MODIFF_VQ.md) record for its assumptions and limits.

The opt-in [MeaningMiner extension](MEANING_MINER.md) sends each of the three
actors' English semantic intentions through its own Planner and gesture lexicon.
It preserves the single speech route, realizes original library arm/finger
motions, and blends back to the calibrated actor rest. Ordinary replay leaves
it disabled; Pure/RL decisions are unaffected. Its verification record separates
library availability, actual selected gestures, and human visual acceptance.
The initial cross-hand-only guard was rejected in human review on 2026-11-03 and
rolled back. Its replacement checks palm/finger/arm proxies against the torso
and a tabletop boundary derived from the room XML, including sampled transitions.
It raises the resting clasp approximately 20 mm and fits a forward open workspace
with shoulder rotation, then restores the checked clasp path. The three actors
use balanced, reserved, and animated gesture profiles while retaining semantic
references. Exact-source offline checks passed seven trajectories (1,133 frames);
the source-only muted runtime passed seven turns (1,135 projected frames and 365
observed samples). A subsequent audible run exposed a sparse-search omission;
the corrected six-axis corner fallback passed the actual failed transitions.
The final audible replay passed seven turns, 1,132 projected frames, and 363
observed samples. The user confirmed no obvious intersections, distinct actor
styles, and normal remaining behavior. The maximum measured queue batch across
these checks was 315 ms, so this is not a 25 fps guarantee or universal mesh
collision proof. See the
same record for geometry limits, deployment receipts, and final observations.

The earlier November 7 conversational revision narrows MeaningMiner activation before
Planner through each seat's `gesture_admission="ideational-main"` policy, while
preserving raw extraction for audit and failing open when an ideational main
anchor is unresolved. Gesture amplitude uses a 0.24 base seated gain and updated
balanced/reserved/animated profiles. The calibrated rests now use close centered
facilitator palms, wider patient-A palms, and asymmetric patient-B palms;
their workspace offsets and body/table clearance policy were recalibrated.
The return uses a 160 ms finishing hold plus a continuous 2.4 s minimum-jerk
settling motion, with full collision checks and exact-rest verification.
Palm/finger tabletop padding tapers near the calibrated contact pose while
retaining the physical boundary. Collision offsets are released by safe subsets
at no more than one degree per joint per frame, avoiding repeated coarse resets.
Listener gaze retains simultaneous Audio-start-plus-200-ms turns,
while the actual next speaker takes priority at the handoff; a seeded 50% optional
single-listener 4-degree nod is independently switchable. The deployed audible
replay completed seven turns with nine selected gestures, 1,402 passing projected
frames, seven restorations, and three exclusive listener nods. MODIFF brow output
remained active, and legacy scratch files were restored. The user accepted the
overall conversational behavior, then the forward hand placement, tabletop
contact, and final natural return without visible jitter. The final audible run
observed 483 BAP samples and a largest queue batch of 41 ms; this is not a
continuous-collision or 25 fps guarantee. The November 3 observations above are
historical. That revision changed eight runtime paths through the existing
sixteen-file allowlist. Its receipt sequence and reverse-order recovery commands
are recorded in [MEANING_MINER.md](MEANING_MINER.md#verification-record).

The subsequent replay repair addresses the user's independent run, which
stopped with a residual shoulder correction at the final rest. A bounded
release-only tail reaches the exact rest without increasing the one-degree
release limit or relaxing geometric checks. Patient B's right palm and fingers
were recalibrated at the accepted horizontal position; the user accepted the
new native static preview. Nods last 1.12 seconds and favor listening actors
with fewer prior nods, including the facilitator, while retaining the seeded
50% trigger and one-listener exclusivity. Current checks exclude MODIFF.
The deployed audible replay completed seven turns and exact restorations,
1,350 passing projected frames, and one nod per actor; the user confirmed that
the hand placement, return, nods, speech, lips, and gaze were normal. Screenshot
sampling now refreshes a bounded one-second arm queue and retains strict
same-frame checks after initial submission. Seven runtime paths were updated
through the unchanged sixteen-file allowlist, with originals retained.
The separate repair evidence and runtime acceptance are recorded in
[MEANING_MINER.md](MEANING_MINER.md#replay-repair-and-right-palm-calibration-2026-11-07).

## Baseline and ownership

| Workspace | Verified state before edits | Responsibility |
| --- | --- | --- |
| `greta` | Clean `master` at `4a469dc2`; safely switched to existing `origin/feat/gmit-multiparty`, `d05c1e48` | Source of all new files |
| `greta_2.1` (the user's “greta2.1”) | `greta_2.1`, `53b252f1`; pre-existing edits and staged resources | Windows runtime only |
| `/home/abulimiti/workspace/gmit` | `main`, `f2bb22d22d8a64c9404365426450801b28d33a66`; existing `WORKLOG.md` edit | Read-only dependency |

Windows roots are `C:\Users\alafate abulimiti\research\workspace\greta` and
`C:\Users\alafate abulimiti\research\workspace\greta_2.1`. WSL uses `/mnt/c/`
for the same paths. The design attachment was found in Downloads, not the greta
root. Its stable project instructions were installed as `AGENTS.md`; the entire
attachment was not copied into repository instructions.

No pre-existing repository AGENTS files or custom agent definitions were found
in either Greta checkout. Global user rules remain applicable. The GMIT AGENTS
file, MEMORY and recent WORKLOG were read; this session's explicit read-only
scope overrides its normal instruction to update continuity files there.

Three project agents are installed under `.codex/agents/`, without model/global
settings changes. The local Codex binary is `0.164.0-alpha.11.1` and contains
standalone-agent support; TOML keys match the
[official schema](https://learn.chatgpt.com/docs/agent-configuration/subagents).
This already-running chat delegated the equivalent role instructions explicitly;
hot reload of the new custom definitions has not been demonstrated.

The integration lead owns deployment, GUI/audio, scripts, and this record.
`greta_engineer` owns the new scene/examples. `gmit_adapter` performed read-only
GMIT exploration. At most two implementation workers ran concurrently; the
independent reviewer runs after integration work.

### Upstream synchronization

The fork's `master` was subsequently fast-forwarded from `4a469dc2` to official
`isir/greta:master` at `ee52762c` (57 commits). The feature branch retains its
Greta 2.1 and GMIT commits while integrating this master; its common ancestor was
older, so it needed 69 upstream commits. Merge decisions and validation are
recorded in `UPSTREAM_SYNC.md`. This source synchronization does not deploy new
JARs or update `greta_2.1`; the P1 audiovisual acceptance below still describes the
previously tested runtime. Completed future changes are committed with
Conventional Commits and pushed to the task branch under the user's standing
authorization, unless a later request says otherwise.

## P0 findings

Greta runtime has `Modular.jar`, Ogre JAR/native Win64 libraries, CereProc JAR,
Win64 DLL, English Sarah/William/Adam/Hannah and French Suzanne/Laurent voice
assets. Merely finding licenses/assets does not prove a valid playback path.
Windows reports Intel Arc Pro Graphics and NVIDIA RTX 3000 Ada Generation
Laptop GPU, with 33,769,504,768 bytes of physical RAM. Actual rendering adapter
and performance are not inferred from this inventory.

Use the existing 64-bit JDK 8. A newer JAVA_HOME was observed to fail the legacy
Ogre native-library setup (`ClassLoader.usr_paths`). The launcher deliberately
finds `jdk1.8*`, or accepts an explicit `-JavaHome`. No Java/Greta process was
running before the first test; the launcher refuses concurrent Java processes.

GMIT full simulation starts at `make_env_instance` in
`src/gmit/env/factory.py:77`, not the human-evaluation `services/gmit_service`.
The factory fixes the sentence encoder to CPU and creates independent patient,
therapist, and stage-estimation clients. `GroupTherapyEnv.reset` adds an opening;
legacy steps yield F followed by alternating A/B (F-A-F-B).
`FacilitatorABRoundRobinWrapper` is explicitly evaluation-only: F-A-B cycles,
an even patient-turn budget, a final facilitator, and zero evaluation reward.
This is not a training-equivalent scheduler replacement.

The runner `regenerate_round_robin.py` still loops over multiple pairs even with
one episode and two patient turns; it was not run. No checkpoint was found in
the local clone. The current WSL Python 3.12 environment lacks torch, tianshou,
gymnasium, sentence-transformers and transformers; no project virtualenv was
found. The default `all-mpnet-base-v2` cache was absent, and TCP 8000 had no WSL
listener. Other environments, ports, Windows services, or cluster resources
remain unverified. No checkpoint or model was loaded or downloaded.

## P1 scene and routing

| Actor ID | Character / voice configuration | Position X | Input reader |
| --- | --- | --- | --- |
| `facilitator` | LENA / English Sarah | 0 | `gmit-facilitator-open-reader`, `gmit-facilitator-close-reader` |
| `patient-a` | EDDY / English William | -0.75 | `gmit-patient-a-reader` |
| `patient-b` | LAURA / English Sarah with a 17% pitch reduction in automatic playback | +0.75 | `gmit-patient-b-reader` |

All three CharacterManagers share one Environment. Each has independent
Realizer, TTS, CereProc, body, face, lip model/blender, audio, and MPEG4 avatar.
Blinking remains enabled. The logical actor IDs above are not assumed to equal
the renderer's generated object identifiers. Initial P1 did not bind gaze
targets; the later seated extension resolves each avatar's actual head node.
Planner instances remain available, but the synthetic inputs are speech-only
BML readers connected directly to each actor's Realizer.

The installed FML path synthesized the same speech twice (both intentions and
signals reach the Planner). The bounded probe caught two audio callbacks and
aborted. P1 therefore uses existing BML readers, without modifying old Java/JARs.
The optional Ini Manager UI node was removed: explicit `setDefinition` hits an
existing `languageIniLoader` null reference; the global IniManager already loads
`./Greta.ini`. The scene graph instantiates no NVBG, MeaningMiner, VAP, MODIFF,
OpenFace, ASR, or LLM module. Opt-in helpers separately attach MeaningMiner
semantic extraction or MODIFF eyebrow adaptation. Registry discovery messages
alone do not mean a module was instantiated.

`SmokeProbe.java` loads the actual runtime Modular graph, records the actual
JAR code sources, checks distinct managers, dispatches F-A-B-F once, saves each
synthesized WAV, captures the scene using the player's camera, and logs sampled
jaw/eyelid FAP values. It rejects missing, duplicate, or wrongly routed audio.
Audio schedule end times and mixer buffer positions are diagnostic evidence,
not proof that sound was heard. Render-texture captures prove rendered scene
content, not desktop-window visibility. No automatic retries repeat a speech.

## Explicit deployment and recovery

`integrations/gmit/deployment.json` is the complete sixteen-file allowlist: the scene,
four P1 BML files, the group MI scenario, the PowerShell launcher, Java probe
source, seated-pose helper, listener-gaze helper, optional MODIFF bridge,
MeaningMiner bridge, gesture and hand-geometry helpers, room layout, and local-output ignore
file. Every source path maps to the identical
relative runtime path.
No resource tree or existing JAR is copied. Instructions, agent definitions,
deployment tooling and this document remain in the development checkout.
The current source change set affects seven existing mappings only:
`MeaningMinerBridge.java`, `MeaningGesture.java`, `SmokeProbe.java`,
`SeatedScene.java`, `ListenerGaze.java`, `Start-GMIT.ps1`, and the group-MI room
XML. The allowlist itself is unchanged.

From the greta WSL root:

```sh
python3 integrations/gmit/deploy.py --runtime ../greta_2.1
python3 integrations/gmit/deploy.py --runtime ../greta_2.1 --apply
```

Every apply saves original bytes and a SHA-256 receipt under
`greta_2.1/bin/GMIT/local/deploy-*/receipt.json`, then verifies installed bytes.
The final original P1 deployment is
`deploy-20261026T161508628383Z/receipt.json`. Earlier same-session receipts
preserve the intermediate versions as well as the initial absence of each file.
To undo one deployment:

```sh
python3 integrations/gmit/deploy.py --runtime ../greta_2.1 --rollback /absolute/path/to/receipt.json
```

To restore the original pre-session state, roll back this session's receipts
in reverse timestamp order. Later manual edits cause a refusal rather than
being overwritten. Receipts, original backups and run evidence are retained.
Pre-existing runtime changes to TurnManagementModel, MODIFF output,
`fml_to_bml_model.xml`, staged FormerDFER resources, and `docs/agent` are outside
the allowlist and were preserved.

## Launch

In Windows PowerShell:

```powershell
Set-Location 'C:\Users\alafate abulimiti\research\workspace\greta_2.1'
# New scene; use the four numbered reader windows in order, after each finishes.
& '.\bin\GMIT\Start-GMIT.ps1'
# Automatic, bounded synthetic test; closes its own process after completion.
& '.\bin\GMIT\Start-GMIT.ps1' -Smoke
# Authored group MI conversation with captions and slower observation defaults.
& '.\bin\GMIT\Start-GMIT.ps1' -GroupMI
# Table, chairs, and seated-pose inspection without speech.
& '.\bin\GMIT\Start-GMIT.ps1' -GroupMI -Preview
# Replay a converted transcript without a model service.
& '.\bin\GMIT\Start-GMIT.ps1' -Replay 'C:\path\to\replay.xml'
# Slower observation replay: visible countdown, longer silent gaps and ending hold.
& '.\bin\GMIT\Start-GMIT.ps1' -Smoke -StartDelaySeconds 10 -TurnGapSeconds 3 -EndHoldSeconds 10
# Original unmodified two-character configuration.
& '.\bin\GMIT\Start-GMIT.ps1' -Original
```

The manual launch waits until the application exits. The optional observation
delays are bounded (start/end 0–15 seconds, turn gaps 1–5 seconds) and only apply
to automatic synthetic playback. The smoke launch has a parameter-derived
watchdog: 120 seconds setup/cleanup plus start/end delays and four bounded turn
budgets. Its legal maximum remains 278 seconds. Group MI uses the validated
scenario turn count, with a maximum of 662 seconds for 16 turns. Compilation has
its own 30-second limit. Run directories
contain stdout/stderr, compiled probe classes and any captured PNG/WAV files.
The launcher restores ModularSave, the old FML conversion scratch file, Ogre
log and generated OpenGL plugin config after its process exits. Initial failed
runs predate the last two backup entries; no tracked runtime resource change
was detected in the subsequent Windows Git check.

### NetBeans source launch

Open `greta/application/Modular` as the project. In Project Properties, select
the existing **64-bit JDK 8** as the project's Java Platform; NetBeans itself
may use another JDK. Set the Run properties as follows, adapting the local root:

| Setting | Value on this machine |
| --- | --- |
| Main Class | `greta.application.modular.Modular` |
| Configuration | `GMIT Group MI` |
| Arguments | `--gmit-group-mi` |
| Working Directory | `C:\Users\alafate abulimiti\research\workspace\greta_2.1\bin` |
| VM Options | `-Djava.library.path="C:\Users\alafate abulimiti\research\workspace\greta_2.1\bin\Player\Lib\External\Win64"` |

The checked-in `nbproject/configs/gmit-group-mi.properties` overrides the default
configuration's stale DLL path and points to the sibling complete runtime.
Run Project (F6) starts the seated conversation automatically. Cancel with the
caption's Stop button and allow the parent launcher to restore its scratch files.
Full instructions and forced-stop recovery are in
[GROUP_MI.md](GROUP_MI.md#run-modular-from-netbeans).

The previous XML argument
`"Configurations\Greta\GMIT\GMIT - 3 characters - CereProc.xml"` still opens
only the standing manual graph. It does not start group MI playback.
IDE execution uses source build outputs and development dependencies; P1 used
the installed runtime JARs. Verify actual loaded artifacts before comparing
results. The separate PowerShell launcher rejects other Java processes,
including NetBeans, so use the project entry while the IDE is open.

## Evidence and remaining work

Final acceptance evidence is recorded in `ACCEPTANCE.md`; independent review
in `REVIEW.md` found no blocking issues. XML/source checks,
synthesis, scheduled playback, rendered capture and human audiovisual
observation are separate evidence levels. The Windows Computer Use tool failed
before connecting with `sandboxCwd is not a local file URI`; application-level
instrumentation was used instead, without claiming desktop-tool observation.

Authentic GMIT generation followed by replay, and offline transcript import,
are now accepted as described in [REPLAY.md](REPLAY.md). The event adapter
preserves event identities, text, actors, order, and available annotations.
Listener gaze now passes three-speaker render and channel checks while replaying
speech, with tabletop hand posture preserved; see the later posture/gaze record
in [GROUP_MI.md](GROUP_MI.md). Independent nod/gesture composition remains P2 work.
Live per-step playback remains unverified: the Pure and RL adapters return
a whole episode before playback. A later live adapter must capture the reset
opening and every newly added row, including multiple rows sharing a source
turn and the closing facilitator, while rejecting failure placeholders.
`_da_rows` is an internal, revision-bound interface. Preserve recurrent policy
hidden/cell state and select one patient pair and a bounded episode explicitly.
