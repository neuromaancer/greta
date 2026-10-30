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

The user subsequently confirmed this revision's seven-utterance replay
(translated from Chinese): "Hands, body, gaze, audio, and lip synchronization
are all normal." This adds human audiovisual acceptance of the tested tabletop
posture and speaker-following gaze to the rendered and software-channel checks.
The evidence does not establish exact ocular fixation,
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

## Clasped hands, 2026-10-27

The seated scene now uses individually calibrated shoulder, elbow, and wrist
angles to bring both hands together, with explicit finger rotations for a
clasp. The existing room XML contains the pose for LENA, EDDY, and LAURA; no
mesh, skeleton, voice, or production JAR is replaced. Launch commands and the
NetBeans Group MI / Replay profiles are unchanged.

Each `seat` can contain optional entries such as:

```xml
<arm-joint name="l_index1_flexion" degrees="-25.0"/>
```

The name must be an allowed arm, wrist, or finger BAP. Duplicate names,
unsupported child elements, missing/invalid degrees, nonfinite values, and
angles outside [-180, 180] are rejected before room installation. These entries
override the seat's arm attributes and persist across speech body frames.
`-DisableTabletopArms` (or `-Dgmit.pose.arms=false` in NetBeans) disables the
whole arm/hand overlay while preserving seated legs. Enabled fixed poses own
the arm/finger channels; blending arbitrary concurrent arm gestures is not
established. Face, lips, blinking, and listener gaze retain their own channels.

This is an authored pose calibrated against the rendered Autodesk skeletons,
not a physical hand-contact, inverse-kinematics, or collision system during
playback. Local calibration helpers and close-up captures remain ignored.
Eight Java 8 parser cases passed without starting Modular: one valid finger
entry, plus rejection of root/head joints, duplicate names, NaN, Infinity,
181-degree values, and an unsupported seat child.
Two additional JVM checks used the runtime's actual `BAPFrame` class without
starting the GUI. Enabled arm overrides changed the test finger to 45 degrees;
disabled overrides preserved its incoming 12-degree value. Both retained the
seated hip pose, and the incoming head channel was unchanged.

The final silent close-ups are in the local run
`hand-calibration-20261027-174348-435`. Compared with the earlier spread palms,
they show interleaved fingers and folded thumbs for all three actors. The hands
were lowered after earlier close-ups exposed a visible gap. Lowest finger-bone
centers are 0.7118-0.7127 m above the scene floor, near the existing tabletop at
approximately 0.7078 m. Bone centers are not skin surfaces or collision probes;
the close-ups provide the visual evidence, and sub-millimeter contact and unseen
mesh intersections are not established.

Normal replay `replay-20261027-174503-355` then exited 0 with seven dispatches,
seven audio callbacks, seven elapsed schedule intervals, and 21 gaze-state
checks. The F-A-B-F-A-B-F order was preserved; all three actors supplied 696
face samples each. Seated-arm/finger persistence and inactive-mouth checks
passed. Seven WAVs and nine render captures remain local. All seven saved
runtime baseline hashes and its 67 pre-existing Git status entries were
unchanged after cleanup. Independent review accepted the hand-pose evidence.
The user subsequently reported remaining chair/pelvis intersection and asked
for more natural speaker gaze and a 200 ms gaze/speech transition offset.
Those are separate follow-up requirements; this is not overall seated-scene
user acceptance.

Only two runtime paths changed in this task, each deployed to the same relative
path under `greta_2.1`: `bin/GMIT/SeatedScene.java` and
`bin/Environments/GMIT/group-mi-room.xml`. All twelve allowlisted files match
source and the final receipt `deploy-20261027T164310616923Z/receipt.json`.
No new public file or deployment mapping was added. To restore the pre-clasp
pose and helper, roll back all four task receipts in reverse order; stop if a
subsequent edit is detected:

```sh
for receipt in \
  deploy-20261027T164310616923Z \
  deploy-20261027T164124444295Z \
  deploy-20261027T163012096741Z \
  deploy-20261027T162103001737Z
do
  python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
    --rollback "../greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```

## Chair fit and conversational gaze, 2026-10-27

Side views confirmed that the original cushions intersected the avatars' pelvis.
Each chair node now uses vertical scale 0.88 around its existing floor origin.
The table, avatar roots, seated limbs, and calibrated clasped hands retain their
positions. Room nodes accept optional positive scale components up to 4;
missing components default to 1. Six no-GUI scale checks passed (one valid and
five invalid inputs). Side captures in `seat-calibration-20261027-175916-499`
show the earlier deep cushion intersection removed. This remains a visual fit,
with no physical collision, cushion compression, or exact weight-bearing contact
claim.

The user's requested timing is **new speech starts, then listeners begin turning
200 ms later**. Replay uses the emitted Audio's Greta Timer timestamps, rather
than the BML dispatch time. Listener motion starts on the first 25 fps frame at
or after that deadline (200-239 ms for an on-time schedule). Existing turn gaps
keep the preceding utterance finished before the new transition. Schedule logs
report the actual frame-derived offset; they do not measure acoustic device
latency or when sound reaches the listener.

The speaker initially looks at one of the two listeners. For utterances lasting
at least 3.5 seconds, the speaker changes to the other listener at the midpoint,
using a 28-frame (1.12-second) smooth transition. This is a change within the
same utterance. Shorter utterances retain one peer target. Both listeners keep
looking at the active speaker throughout; the speaker's midpoint switch does
not redirect them. The first peer alternates across playback turns.
The probe captures both speaker phases and checks their actual neck/eye values.

Gaze-only neck and eye frames cover the bounded speech interval (at most 15
seconds). This prevents earlier queued speech animation from restoring old gaze
after the initial transition. Incoming live frames use the same gaze timeline.
An actual `APFrameList` fixture verified gaze replacement at frame 100, beyond
the former 24-frame transition, while preserving jaw, eyelid, arm, finger,
seated hip, and root values. The normal replay keeps checking those channels.

Final replay `replay-20261027-181009-897` exited 0 after the original seven
utterances in F-A-B-F-A-B-F order. It recorded seven audio callbacks, seven
elapsed schedule intervals, seven within-utterance speaker switches, and 28
gaze-phase verifications (both speaker targets and both listeners per turn).
Listener schedule offsets were 230, 202, 210, 207, 206, 216, and 232 ms after
audio start. Each actor supplied 688 face samples; inactive-mouth, blinking,
seated-limb, and clasp-persistence checks passed. Sixteen PNGs include each
speaker's first and second target; seven WAVs remain local. No new model call
or GMIT source change was involved.

The user confirmed the final replay (translated from Chinese): "I can see the
switch within the same utterance; everything else is normal." This supplies
human acceptance of the visible gaze switch and the other requested appearance
and audiovisual checks. It does not measure a physical 200 ms acoustic delay.
Java 8 compiled and executed the deployed helpers. The NetBeans launch profiles
use the same helpers and remain unchanged; direct NetBeans F6 interaction was
not repeated during this task.

All twelve deployed files match source and the final receipt. All seven saved
runtime baseline hashes and its 67 pre-existing Git status entries remained
unchanged. Four existing paths changed at the same relative runtime locations:
`bin/GMIT/SeatedScene.java`, `bin/GMIT/ListenerGaze.java`,
`bin/GMIT/SmokeProbe.java`, and `bin/Environments/GMIT/group-mi-room.xml`.
No new public file or deployment mapping was added. To restore the accepted
clasp-only revision, roll back these four follow-up receipts in reverse order:

```sh
for receipt in \
  deploy-20261027T170926531064Z \
  deploy-20261027T170118677665Z \
  deploy-20261027T165716931179Z \
  deploy-20261027T165426856134Z
do
  python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
    --rollback "../greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```

Stop rollback on any subsequent-edit warning. To also undo the clasped hands,
continue with the four earlier receipts listed in the preceding section.

## Wider upper arms and forearms, 2026-10-30

The three avatars now open their elbows outward instead of holding the upper
arms close to the torso. An ignored calibration helper used the actual Ogre
skeletons to preserve the clasp while increasing each elbow's outward projection
in actor-local coordinates. After the user requested more width than the first
6 cm version, the final solutions increased that projection by 6.98-8.99 cm
relative to the original pose. Elbows also move vertically and fore/aft; this
is not a pure lateral translation. The largest wrist-position change from the
original was approximately 1.2 mm. These are bone measurements, not skin-contact
or collision guarantees.

The room XML changes 48 shoulder, elbow, and wrist angle values. Wrist rotations
compensate for the changed upstream arm rotations to retain the original hand
position and palm orientation. All finger and thumb values, avatar roots,
seated legs, furniture, and camera settings remain unchanged. The existing
fixed arm overlay and its disable switch still apply; no runtime IK, model,
speech, or gaze behavior was added.

The baseline preview was `group-mi-20261030-132816-620`. The original and final
joint measurements are in local calibration runs
`wide-arm-calibration-20261030-133129-710` and
`wide-arm-calibration-20261030-134031-828`. Helpers and captures remain ignored;
this change adds no generated dialogue or model weights to Git.

Final preview `wide-arm-calibration-20261030-134243-312` exited successfully with
eight render captures, including one hand close-up per actor. They show more
open elbows with the clasp retained; independent review found no gross new
hand/torso intersection in those views. This does not establish contact on
hidden surfaces or physical arm support by the table.

The ordinary launcher replay `replay-20261030-134355-430` exited 0 after seven
dispatches, seven intended-actor audio callbacks, and seven elapsed schedule
checks, preserving F-A-B-F-A-B-F. The fixed pose and inactive-mouth checks
passed; seven speaker gaze switches and 28 gaze checks passed. Listener motion
started 205-239 ms after the scheduled Greta audio start, consistent with the
200 ms offset rounded to 25 fps frames. Sixteen PNGs and seven WAVs remain
local. No model was called. Audio callbacks and renders do not independently
establish physical audio/lip acceptance. The user subsequently requested still
wider arms with lower elbows; the follow-up below supersedes this pose.
All six saved runtime scratch hashes and the pre-existing runtime Git status
were unchanged after cleanup.

Only `bin/Environments/GMIT/group-mi-room.xml` changed in the runtime, deployed
from the identical relative path in the development checkout. All twelve
allowlisted files were verified; the other eleven retained identical bytes.
Existing JARs, meshes, and voice resources were not replaced. The deployment
retains originals and the final receipt
`deploy-20261030T124224515904Z/receipt.json`. To restore the pre-task arm pose,
roll back both task receipts in reverse order:

```bash
for receipt in deploy-20261030T124224515904Z deploy-20261030T123326447835Z; do
  python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
    --rollback "../greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```

The rollback checks for later edits before replacing files. Existing launch
commands and the NetBeans Group MI / Replay profiles load the updated room XML
on the next launch.

## Lower elbows and forward hand placement, 2026-10-30

The final pose lowers elbow bone centers to 0.7547-0.7554 m, 37-93 mm below
the published `b0fce55b` pose. After reviewing the first low-elbow preview, the
user requested less elbow spread and hands farther forward. The final adjustment
brings each elbow inward by about 25 mm and moves each clasp forward by 38-47 mm
relative to that preview, keeping the lower height.

The local calibration solves both arms together, preserving wrist separation,
wrist heights, palm orientation, and the finger pose while allowing shared
clasp translation along the tabletop. Relative to `b0fce55b`, the hands remain
35-65 mm closer to the body; global hand positions are intentionally different.
The exported XML still contains only fixed joint angles; no solver runs during
ordinary playback.

Only the room XML's 48 shoulder/elbow/wrist values and explanatory comment
changed. Finger/thumb angles, avatar roots, seated legs, chairs, table, camera,
speech and gaze code remain unchanged. Calibration evidence is local run
`lower-elbow-calibration-20261030-142745-225` and
`lower-elbow-calibration-20261030-143531-022`. This is a visually calibrated
fixed pose, not a physical support or collision solver. Hidden surfaces remain
unverified.

Final silent preview `lower-elbow-calibration-20261030-143757-617` exited 0.
Its `02-gaze-patient-b.png` full-scene gaze capture and three named hand close-ups
show the lower elbows, reduced elbow spread, and clasp farther onto the table.
No gross new arm/torso/table intersection was observed in those views.

Ordinary replay `replay-20261030-143935-755` exited 0 with seven dispatches,
seven intended-actor audio callbacks, seven elapsed schedule checks, seven
speaker gaze switches, and 28 gaze verifications. Pose persistence and inactive
mouth checks passed. Listener transition delays were 211-239 ms; sixteen PNGs
and seven WAVs remain local. This used the existing transcript without a model
call. On 2026-10-30, the user confirmed that the refined posture (lower elbows,
narrower elbow spacing, and hands moved forward) was suitable. This records
human posture acceptance; audio/lip evidence for this final replay remains
programmatic. All six runtime scratch hashes and the pre-existing runtime Git
status were preserved.

Deployment changed only `bin/Environments/GMIT/group-mi-room.xml` at the same
relative path in `greta_2.1`. The other eleven allowlisted files retained their
bytes. To restore the published `b0fce55b` pose, roll back both task receipts
in reverse order:

```bash
for receipt in deploy-20261030T133712842014Z deploy-20261030T133010070964Z; do
  python3 integrations/gmit/deploy.py --runtime ../greta_2.1 \
    --rollback "../greta_2.1/bin/GMIT/local/$receipt/receipt.json" || break
done
```

The existing launch commands load this updated XML on the next start. Local
helpers, captures, transcripts, and deployment backups remain ignored.
