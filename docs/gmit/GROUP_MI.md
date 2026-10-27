# Synthetic group motivational interviewing demo

This short, authored conversation uses the existing shared Greta scene. A
facilitator and two fictional participants discuss reducing red meat. It is a
repeatable engineering demonstration, not generated GMIT output or evidence of
clinical effectiveness. No LLM, policy checkpoint, training, or evaluation is run.

The group demo adds a table, three chairs, a seated pose with hands on the table,
and listener eye/head gaze toward the current speaker. The
furniture uses the complete package's existing Office resources. The original
standing four-line smoke configuration remains available with `-Smoke`.

## Start the scene

From Windows PowerShell in the complete runtime package:

```powershell
Set-Location 'C:\Users\alafate abulimiti\research\workspace\greta_2.1'
& '.\bin\GMIT\Start-GMIT.ps1' -GroupMI
```

The launcher selects the existing Windows 64-bit JDK 8, opens the three-character
scene and a caption window, waits 10 seconds, and plays the 13 turns once. There
is a three-second gap between turns and a ten-second hold at the end. The process
then exits. Override these delays with `-StartDelaySeconds`, `-TurnGapSeconds`,
and `-EndHoldSeconds`. Close any other Java/Greta instance before starting.
The caption window identifies the speaker, authored stage, and full utterance.
Its **Stop run** button requests cancellation of this run. The
launcher then restores the same runtime scratch files as it does after completion.
Avoid operating the original BML reader windows during automatic playback;
this runtime does not expose their frames to the probe for hiding.

| Position | Role | Character | Voice configuration |
| --- | --- | --- | --- |
| Center | Facilitator | LENA | English Sarah |
| Left | Alex, patient A | EDDY | English William |
| Right | Sam, patient B | LAURA | English Sarah |

The facilitator and Sam share a voice configuration but retain independent
speech, lip, audio, and animation chains. Their position and captions distinguish
them. Avatar resources, blinking, and runtime JARs come from the accepted P1
setup. Group playback adds its room and seated pose to the existing graph
without replacing the Environment root.

`-Smoke` still runs the original four-line F-A-B-F test. A launch without either
switch still opens the scene for its original four manual readers. Choose one
mode per launch; `-GroupMI`, `-Smoke`, and `-Original` cannot be combined.

To inspect the seated layout without speech, use `-GroupMI -Preview`. This
bounded preview saves scene captures locally and then exits.

## Run Modular from NetBeans

1. Open the development checkout's `greta/application/Modular` project. The
   opt-in entry is source code in this project; the complete runtime package's
   older Modular project does not contain it automatically.
2. In Project Properties, choose **Windows 64-bit JDK 8** as the project's Java
   Platform. Add the installed JDK through Java Platforms if it is missing.
   The JDK used to run NetBeans itself is a separate setting.
3. Select the **GMIT Group MI** run configuration. It is supplied by
   `nbproject/configs/gmit-group-mi.properties`. Reopen the project if an already
   open IDE has not noticed the new configuration.
4. Check these settings under Project Properties > Run:

| Setting | Value |
| --- | --- |
| Main Class | `greta.application.modular.Modular` |
| Arguments | `--gmit-group-mi` |
| Working Directory | The complete `greta_2.1/bin` directory |
| VM Options | `-Djava.library.path="<complete-runtime-bin>/Player/Lib/External/Win64"` |

The shared configuration defaults to a sibling runtime package via
`${basedir}/../../../greta_2.1/bin`. Change Working Directory locally if yours
is elsewhere. Its VM option derives the DLL directory from that setting and
overrides the old developer-specific path in the default configuration.

Right-click **Modular > Run**, or make Modular the main project and press **F6**.
The opt-in launcher compiles the small demo
helpers, launches the seated scene with captions, and plays the same 13-turn
script. Use **Stop run** in the caption window to cancel. Let the
launcher finish restoring its scratch files before another launch.

The original XML argument still only opens the Modular graph and its individual
readers. `group-mi-red-meat.xml` is a dialogue script, not a Modular graph to
open directly. The `--gmit-group-mi` entry connects graph loading, room setup,
posture, and scripted playback.

The NetBeans bridge runs a child JVM with the IDE's actual application classpath
plus the compiled demo helpers. It does not silently substitute runtime
`Modular.jar` for the project's source build. The child exits before the parent
restores `ModularSave.xml`, `fml_to_bml.xml`, the Ogre log, and the generated
OpenGL plugin configuration. Logs and backups are written under
`bin/GMIT/local/netbeans-group-mi-*/`; NetBeans Output prints the directory.

Use the caption's Stop button instead of forcibly stopping the parent JVM in
NetBeans. Force-killing the parent or closing the IDE can bypass its cleanup
and leave the child running. After closing that child, the local
`scratch-state.json` records which scratch files originally existed; the matching
`.before` files are their originals. `scratch-restored.txt` indicates completed
restoration. Do not restore an old snapshot over a later completed run.

If the parent was force-killed, first close its remaining Greta child and any
other Greta run. Then inspect the recorded state and recover that interrupted
run in PowerShell, setting the two paths to the actual runtime and run directory:

```powershell
$runtimeBin = 'C:\path\to\greta_2.1\bin'
$runDirectory = Join-Path $runtimeBin 'GMIT\local\netbeans-group-mi-TIMESTAMP'
if (Test-Path (Join-Path $runDirectory 'scratch-restored.txt')) { throw 'Already restored; preserve later edits.' }
$state = Get-Content (Join-Path $runDirectory 'scratch-state.json') -Raw | ConvertFrom-Json
$allowed = @('ModularSave.xml', 'fml_to_bml.xml', 'Log/Ogre.log', 'Player/Lib/External/Win64/Plugins_OpenGL.cfg')
foreach ($entry in $state.files) {
    if ($entry.path -notin $allowed) { throw 'Unexpected scratch path.' }
    if ($entry.existed -and -not (Test-Path (Join-Path $runDirectory (($entry.path -replace '/', '_') + '.before')))) { throw 'Missing original backup.' }
}
foreach ($entry in $state.files) {
    $target = Join-Path $runtimeBin $entry.path
    if ($entry.existed) {
        Copy-Item -LiteralPath (Join-Path $runDirectory (($entry.path -replace '/', '_') + '.before')) -Destination $target -Force
    } elseif (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
}
Set-Content (Join-Path $runDirectory 'scratch-restored.txt') 'Manually restored after the child was closed.'
```

## Conversation

The authored order is **F-A-B, F-A-B, F-A-B, F-A-B, F**:

1. The facilitator invites participation and respects the option to pass.
2. Alex and Sam describe their own reasons for change and reservations.
3. Reflections and open questions explore what matters to each participant.
4. The participants respond to each other and recall available support.
5. Each chooses a small experiment, followed by a shared summary.

The dialogue illustrates autonomy, reflection, open questions, and personal
reasons for change, informed by the
[MINT introduction to motivational interviewing](https://motivationalinterviewing.org/node/11216).
The `stage` and `act` values are author annotations for this example; they are
not GMIT stage estimates, policy decisions, or validated MI ratings.

## Script and execution

Edit `bin/Examples/GMIT/group-mi-red-meat.xml` in the development repository and
deploy through the existing allowlist. The runtime uses the same relative path.
Each turn supplies `sequence`, a unique `event_id`, `actor_id`, `stage`, `act`,
and a `text` element. The document supplies schema version, synthetic provenance,
session identifier, floor mode, and title. The session and event identifiers
identify this reusable fixture; each playback's evidence has its own local run
directory. These identifiers do not turn repeated playback into a new GMIT run.

Only `facilitator`, `patient-a`, and `patient-b` are accepted. Playback is bounded
to 4–16 turns in complete F-A-B cycles followed by a closing F, with contiguous
sequences from 1, at most 300 characters per utterance, and 15 seconds of
synthesized audio per turn. The Java preflight rejects invalid scripts before
loading Modular. It writes one speech-only BML file per turn into the local run
directory, preserving text and escaping XML. Each file is sent directly through
its actor's existing BML reader to that actor's Realizer. No new scheduler is
installed in GMIT, and this fixture's order does not select a live GMIT floor mode.

The probe checks actor routing, duplicate audio, Greta's scheduling clock, and
inactive mouths, while recording audio, render captures, and sampled jaw/eyelid
values. Errors abort the run; speech is never automatically retried. The outer
watchdog derives its limit from the turn count and observation delays. The
maximum legal 16-turn configuration is bounded to 662 seconds, including startup
and cleanup; the supplied 13-turn configuration defaults to a 530-second limit.

`bin/Environments/GMIT/group-mi-room.xml` defines the furniture, camera, seat
positions, and static joint angles. `SeatedScene.java` adds those room nodes
without replacing the active Environment. It routes each actor's body frames
through a pose overlay, preserving seated hips, knees, ankles, pelvis,
root placement, and the configured shoulder/elbow/wrist pose during speech.
The probe checks every overlaid channel against actual avatar BAP frames during
playback. Existing `object.table`, `object.chair`, and `object.floor` resources
are reused; no meshes or weights are copied. Known furniture meshes ignore Leaf
size metadata in this renderer, so layout offsets account for their actual bounds.
This is a calibrated fixed pose for these three avatars, not a collision solver,
generated sitting transition, or contact-aware hand/arm animation.

`ListenerGaze.java` updates the two listeners when the speaker changes. It binds
to the existing avatars' rendered head nodes, accounts for seated root BAP
translation and yaw, and smooths bounded head/eye turns over 18 Greta frames
(0.72 seconds at 25 fps). The current speaker returns to the forward seated pose.
Only cervical yaw/pitch and four eyeball FAP channels are controlled. LipBlender
output is cloned before those eye channels change; mouth and eyelid masks remain
intact. Finite gaze-only frames also move idle listeners without requiring speech.
Gaze has priority over concurrent cervical gestures; independent nod/gesture
composition is not verified.

Use `-DisableListenerGaze` or `-DisableTabletopArms` with the PowerShell launcher
to isolate the behaviors. In NetBeans, add `-Dgmit.gaze.enabled=false` or
`-Dgmit.pose.arms=false` to VM Options; the parent forwards them to its child.
`-Dgmit.gaze.head=false` and `-Dgmit.gaze.eyes=false` isolate individual gaze
channels. Existing speech, captions, actor mapping, and transcript order are
independent of these switches. The silent `-GroupMI -Preview` also captures all
three speaker-target states before exiting.

## Deployment and evidence

The twelve-file allowlist includes the scenario, room XML, seated-pose and gaze helpers,
launcher, probe, original scene and BML examples, and local-output ignore file.
Every listed file maps to the identical relative path in `greta_2.1`; the other
P1 assets remain unchanged. Use `integrations/gmit/deploy.py` to preview and apply,
as documented in [PROJECT.md](PROJECT.md#explicit-deployment-and-recovery).
Each deployment retains originals and a SHA-256 receipt, with rollback that
refuses to overwrite later edits.

Local runs are stored under `bin/GMIT/local/group-mi-*/`, including logs,
generated BML, WAV audio, and render-texture PNGs. These artifacts stay ignored.
An audio callback and elapsed schedule establish software behavior; they do not
establish sound heard from the physical speakers. Render-texture evidence and
human visual/audible observation are reported separately. Later posture/gaze
evidence appears below; original acceptance sections describe their earlier
versions. Independent head gestures and live per-step playback remain unverified.

## Earlier standing demo acceptance, 2026-10-27

The completed red-meat-reduction run is `group-mi-20261027-133156-510`, with
launcher exit code 0. The user confirmed (translated from Chinese):
“Audio, lip movement, and captions are all normal.”

- All 13 turns were dispatched and completed their scheduled intervals in the
  authored order, with 13 matching event records and exactly 13 audio callbacks:
  five for the facilitator and four for each participant. Utterance durations
  ranged from 5.739 to 8.561 seconds. There were no probe failures or retries.
- All 13 generated BML speech texts matched their source utterances. The run
  retained 13 WAV files and 655 render-texture PNGs locally. The scene capture
  shows all three avatars together; the human confirmation supplies separate
  evidence of audible playback, lip movement, and readable captions.
- Each actor had 654 sampled face frames. Jaw ranges were 0–85, 0–72, and 0–92
  for facilitator, Alex, and Sam respectively; inactive-mouth checks passed.
  Blinking was enabled for all three, with sampled eyelid values spanning 0–940.
- All 42 graph modules used existing runtime JARs, including the nested
  `Common/Lib/Internal/lib` dependencies. No production JAR was rebuilt or
  deployed. The logs retain 26 existing Furhat sender null-reference diagnostics,
  along with module-parameter and renderer/material warnings. This demo does not
  instantiate a Furhat module or claim to fix those diagnostics.
- JDK 8 compilation, PowerShell parsing, mode-exclusivity checks, and parser-only
  validation passed. Eight malformed scenario cases were rejected before GUI
  startup: unknown actor, wrong floor order, duplicate event ID, skipped sequence,
  unsupported provenance, nested text markup, external DTD, and excessive text.

The deployment receipt is `deploy-20261027T123142489436Z/receipt.json` under the
runtime's local directory. All nine deployed files matched source and receipt
SHA-256 values. All 66 pre-existing runtime Git status entries were preserved;
the four launcher-managed scratch files and three sampled pre-existing modified
files also retained their pre-run hashes. Independent integration review covers
the final diff, deployment mapping, and run evidence.

After undoing all subsequent seated-scene deployments described below, restore
the earlier P1 launcher/probe and remove the scenario from the development checkout:

```sh
python3 integrations/gmit/deploy.py --runtime ../greta_2.1 --rollback ../greta_2.1/bin/GMIT/local/deploy-20261027T123142489436Z/receipt.json
```

The original four-turn smoke mode was retained but was not separately replayed
during this extension. Stop-button interaction and forced watchdog expiry were
not exercised in the completed run. This acceptance does not validate live GMIT,
gaze/head/gesture behavior, an updated source-built Greta runtime, or MI quality
against a clinical rating instrument.

## Seated scene and source entry acceptance, 2026-10-27

The completed seated run is `netbeans-group-mi-20261027-142420-750`, with parent
exit code 0 and `scratch-restored.txt` present. The user confirmed during this
run (translated from Chinese): “Seating, audio, lip movement, and captions are
all normal.” The render capture independently shows three seated avatars at one
table, with their chairs behind them and all three framed together.

- All 13 authored turns completed in order with 13 matching event records and
  exactly 13 audio callbacks: facilitator 5, patient A 4, patient B 4. Durations
  were 5.739–8.561 seconds. Generated BML text matched all 13 source utterances.
  No probe failure, duplicate callback, retry, or seated-cleanup failure occurred.
- Root placement, yaw, pelvis, hips, knees, and ankles remained at the configured
  pose during sampled speech frames. Inactive-mouth checks passed. Each actor
  had 580 sampled face frames; maximum jaw values were 87, 72, and 80 for F, A,
  and B, and eyelid ranges were 0–940 for all three. The run retained 13 WAVs and
  581 render-texture PNGs locally.
- Windows JDK 8 compiled the changed Modular entry and bridge against the
  existing runtime dependency JARs. The run invoked that compiled source entry
  with `--gmit-group-mi`; its logged code source is the development checkout's
  ignored `bin/GMIT/local/netbeans-entry-check/classes/` directory. All 42 graph
  modules still came from existing runtime JARs. The bridge's parent/child path
  and normal cleanup were exercised; no production JAR was replaced.
- The full NetBeans dependency build and an actual IDE Run/F6 click were not
  exercised. The committed profile and property-loading path were inspected.
  This source-entry check does not establish acceptance of a complete rebuilt
  Greta dependency graph. Caption cancellation, forced IDE Stop, and timeout
  recovery were not exercised in this run.
- The original 66 runtime Git status entries remain; the only additional status
  entry is the allowlisted `bin/Environments/GMIT/` directory. All four managed
  scratch files and the three sampled pre-existing modified files retained their
  pre-task SHA-256 values. All eleven deployed files match source and the final
  receipt. Existing renderer/module warnings and Furhat diagnostics remain.

The seated extension added four source files: `bin/GMIT/SeatedScene.java`,
`bin/Environments/GMIT/group-mi-room.xml`,
`application/Modular/src/greta/application/modular/GMITLauncher.java`, and
`application/Modular/nbproject/configs/gmit-group-mi.properties`. Only the first
two are runtime deployments; the NetBeans bridge and profile stay in the source
checkout. The existing probe and launcher were updated in place through the
allowlist. Static previews exposed and corrected root BAP masking and furniture
alignment before this accepted audible run.

The final seated deployment receipt is
`deploy-20261027T132303028170Z/receipt.json`. To return the runtime to the previous
standing group demo, roll back these four seated-extension receipts in reverse
order from the development checkout (WSL):

```sh
for receipt in \
  deploy-20261027T132303028170Z \
  deploy-20261027T131943279989Z \
  deploy-20261027T131630750260Z \
  deploy-20261027T130450503607Z
do
  python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
    --rollback "../greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```

The existing four-line standing smoke mode remains separate. Dynamic listener
gaze, nods/gestures, authentic GMIT replay, and live model generation are still
outside this acceptance. The independent integration reviewer checked the
actual code changes, lifecycle handling, deployment mapping, and evidence.

## Tabletop posture and listener gaze, 2026-10-27

The user reported hands intersecting the chairs and bodies intersecting the table.
The facilitator and chair moved back by 0.18 m; each patient and chair moved
outward by 0.12 m relative to the earlier accepted scene. Shoulder/elbow and
wrist rotations now hold both palms downward at the tabletop. Only the existing
room XML and small runtime helpers changed; meshes, voices, checkpoints, and
production JARs were preserved. This calibration covers LENA, EDDY, and LAURA in
the supplied scene, not arbitrary avatars, furniture, or dynamic collision physics.

The final silent preview `group-mi-20261027-164007-118` completed all three
speaker-target states. Render captures show the two listeners turning toward
each active speaker and no obvious hand/torso intersection with the tabletop
in the tested view. Actual neck and eye channels matched the bounded targets.
The separate `group-mi-20261027-164156-630` preview disabled listener gaze and
completed with the tabletop posture intact. The first gaze preview exposed that
idle actors emitted no new body frames; finite gaze-only transition frames fixed
that failure before speech acceptance. Earlier arm previews were calibration
attempts, not accepted final poses.

The existing seven-utterance Pure bundle was then replayed with these changes;
no new model generation ran. `replay-20261027-164532-884` exited 0 with seven
dispatches, seven audio callbacks, seven elapsed schedule intervals, and 21
gaze-state verifications. Original F-A-B-F-A-B-F order and actor counts
(F=3, A=2, B=2) were retained. Audio durations were 4.281-11.541 seconds.
Each actor had 702 face samples, with jaw movement on the active actor and
eyelid values reaching 940. Inactive-mouth and seated-arm/leg checks passed
throughout playback. Nine render captures and seven WAV files remain local.

These are rendered and software-channel observations. A new human audiovisual
confirmation for this revision is pending; previous user confirmations belong
to the earlier scene. The evidence does not establish exact ocular fixation,
continuous tracking of freely moving actors, arbitrary gesture composition, or
physical audio completion for every sample. The Windows JDK 8 source entry was
compiled with the new helper; actual NetBeans F6 interaction remains unverified.

Independent review found no code or render-evidence blocker. All 42 loaded graph
modules came from the complete runtime package. All twelve deployed files match
source and the final receipt, and all seven saved runtime baseline file hashes
match after cleanup. The runtime's 67 pre-existing Git status entries are
unchanged. The only new deployment mapping is
`greta/bin/GMIT/ListenerGaze.java` to `greta_2.1/bin/GMIT/ListenerGaze.java`.
Other changed deployed paths are `SmokeProbe.java`, `SeatedScene.java`, and
`Start-GMIT.ps1` under `bin/GMIT/`, and
`bin/Environments/GMIT/group-mi-room.xml`, each at the same runtime-relative path.

The final receipt is `deploy-20261027T154504069359Z/receipt.json`. To restore the
complete pre-task helper/configuration state, roll back all six task receipts in
reverse order from the development checkout; stop if a later edit is detected:

```sh
for receipt in \
  deploy-20261027T154504069359Z \
  deploy-20261027T153956647549Z \
  deploy-20261027T153526521970Z \
  deploy-20261027T153001106000Z \
  deploy-20261027T152502279485Z \
  deploy-20261027T152009167558Z
do
  python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
    --rollback "../greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```
