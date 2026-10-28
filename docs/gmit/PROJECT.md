# GMIT in Greta

Updated 2026-10-28. P0 inspection and synthetic P1 acceptance are complete.
The authored group MI extension adds a 13-turn conversation about reducing
red meat, with a table and three seated characters. See [GROUP_MI.md](GROUP_MI.md)
for launch commands, NetBeans settings, and evidence.
The seated scene also supports per-avatar clasped-hand poses and listener gaze;
their separate calibration and acceptance records are in the same document.
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
| `patient-b` | LAURA / English Sarah | +0.75 | `gmit-patient-b-reader` |

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
`./Greta.ini`. No NVBG, MeaningMiner, VAP, MODIFF, OpenFace, ASR, or LLM module is
instantiated by the new scene. Registry discovery messages for such modules do
not mean those modules were instantiated.

`SmokeProbe.java` loads the actual runtime Modular graph, records the actual
JAR code sources, checks distinct managers, dispatches F-A-B-F once, saves each
synthesized WAV, captures the scene using the player's camera, and logs sampled
jaw/eyelid FAP values. It rejects missing, duplicate, or wrongly routed audio.
Audio schedule end times and mixer buffer positions are diagnostic evidence,
not proof that sound was heard. Render-texture captures prove rendered scene
content, not desktop-window visibility. No automatic retries repeat a speech.

## Explicit deployment and recovery

`integrations/gmit/deployment.json` is the complete twelve-file allowlist: the scene,
four P1 BML files, the group MI scenario, the PowerShell launcher, Java probe
source, seated-pose helper, listener-gaze helper, room layout, and local-output ignore file. Every source path maps to the identical
relative runtime path.
No resource tree or existing JAR is copied. Instructions, agent definitions,
deployment tooling and this document remain in the development checkout.

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
are now accepted as described in [REPLAY.md](REPLAY.md). The external adapter
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
