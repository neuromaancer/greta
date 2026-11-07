# MeaningMiner semantic gestures for three actors

This opt-in extension uses Greta's English MeaningMiner pipeline for facilitator,
patient A, and patient B. Each speaker uses its existing Planner, lexicon, and
Realizer to select and realize semantic gestures, including two-handed gestures.
Pure, RL, and imported transcripts share this playback layer. Original text,
actor order, and dialogue decisions are preserved.

## Speech and behavior routing

`MeaningMinerBridge` calls the real `ImageSchemaExtractor.processText_2` API.
It retains image-schema and ideational-unit intentions instead of reducing them
to a sorted list of labels. The bundled Stanford tokenizer supplies character
offsets; time markers are inserted without rewriting the original text. The
original intention references and speech markers are resolved by Greta's
Planner and TTS. A normalized text hash checks that extraction preserves speech.

Each actor has an independent extractor, CharacterManager, Planner, lexicon,
Realizer, and speech chain. The enabled route sends exactly one speech intention
with its semantic intentions through that actor's Planner. It replaces the
direct BML-reader dispatch for that turn. A guarded Planner-to-Realizer connection
rejects duplicate speech or duplicate dispatch callbacks. It does not add a
second FML/TTS route. Ordinary replay continues to use its existing BML reader.

The existing runtime lexicons contain 27 image-schema behavior sets for each
actor: LENA and EDDY use `lexicon_Camille.xml`, and LAURA uses
`lexicon_Laura.xml`. The Planner selects gestures from these resources; the bridge
does not replace them with hand-written gesture profiles. Empty semantic output
means speech without an invented gesture.

The optional semantic admission policy runs before Planner, not after a gesture
has reached the Realizer. `all` preserves every extracted image-schema intention.
`ideational-main` keeps only the image-schema explicitly named as the `main`
anchor of each MeaningMiner ideational unit, while retaining speech and all
non-image-schema intentions unchanged. It reduces activation through a genuine
extractor relation, not by sorting labels, clipping a hand, or inventing a
confidence score (the extractor assigns the relevant intentions importance
`1.0`). Whole gesture signals remain intact, including two-handed signals. Raw
intentions, schema IDs, anchors, admitted IDs, and counts are audited before the
Planner. A missing or unresolved anchor fails open for the complete turn rather
than silently dropping semantics.

The precedence is `gmit.meaningminer.gesture.admission.<actor>`, then
`gmit.meaningminer.gesture.admission`, then the room's `gesture_admission`, then
`all` if the seated helper is unavailable. Valid values are `all` and
`ideational-main`. The current three room seats use `ideational-main`.

## Seated adaptation and ownership

The stock realization supplies the actual BAP arm and finger values and frame
times. `SeatedScene` captures those frames and blends them from the calibrated
actor rest, retaining both arms and the selected hand shapes. Shoulder and elbow
motion is retargeted as a delta from the first observed stock value per joint,
with a base seated gain of 0.24 and per-style left/right multipliers; wrist and finger values retain the library's absolute
hand shape without that gain reduction. This reference
is an engineering approximation of stock neutral, not a measured contact pose.
Styled playback uses explicit geometry instead of the old angle-sign flexion
clamp and fixed outward shoulder offset. The room's `gesture-arm-offset` entries
add constant shoulder abduction, flexion, twisting, and elbow flexion offsets.
An offline wrist-position fit places the open hands forward above the table;
it does not replace the selected gestures
with an authored gesture template. Missing raw channels keep their seated value
plus the explicit workspace offset, without borrowing a future joint value.
The current resting poses are specific to the three current avatars: the
facilitator uses close centered palms-down hands, patient A uses wider palms-down
hands, and patient B uses asymmetric palms-down hands. Workspace offsets were
recalibrated for those poses. These are scene calibrations, not general
hand-contact rules or a physical resting-contact simulation.

The seat's `gesture_style` selects an engineering expression profile. The
Planner chooses signals for the admitted semantic intentions; the profile adjusts
Greta's existing SPC/TMP/FLD/PWR expressivity parameters before realization and
scales the captured proximal-arm deltas afterward. It does not change references,
text, or word-marker links, and does not assign a GMIT patient personality.

| Actor | Style | Current intent |
| --- | --- | --- |
| facilitator | `balanced` | restrained neutral movement |
| patient-a | `reserved` | lower-amplitude movement |
| patient-b | `animated` | comparatively more animated movement |

At 25 fps, the entry envelope first releases the fingers (frames 0–3), moves
the arms into the separated workspace (3–9), then blends into the current stock
wrist/finger and arm targets (9–18). This adapts the first 720 ms of captured
motion; original BAP frame numbers, speech text, and word references are retained.
The facilitator's `gesture_release_lead="0.3"` starts 30% of the workspace
movement during finger release.
The current three seats use `gesture_return="direct"`. After both audio and
captured stock arm motion finish, this mode has a 160 ms finishing hold, then
a 2.4 s minimum-jerk return, moving continuously from the final semantic pose
to rest without an intermediate workspace stop. This requires separated resting
hands with the full normal cross-hand and finger-palm air gaps. Every return
frame still passes the ordinary projection and sampled-path checks; at the end,
the solver must reach the exact rest within its rate limits or fail explicitly.
Only the nominal interpolation is monotone; safety projection may adjust it.
The next speaker waits for completion.

The backward-compatible `workspace` mode remains the default for other room
configurations. It retains the original 24-frame return, including 12 frames
toward the verified separated pose, then exact reverse indices into the cached
opening poses and their matching clearance weights. It does not resample the
contact path. Contact/clasp rests must use that guarded route.

With MeaningMiner enabled, `HandCollision` captures the actual Autodesk bone
hierarchy and BAP mapper rotations on the Ogre thread. It retains immutable
numbers only. Candidate frames are evaluated in Java without moving the live
skeleton. Startup checks forward-kinematics positions against native bone
positions, including the arm and forearm roll bones. An incompatible mapping
stops playback before dispatch.

The correction checks paired palm, finger, and forearm capsule proxies, plus
body and tabletop boundaries. It adjusts shoulder abduction/flexion and elbow
flexion, each within ±16 degrees relative to the seated gesture candidate.
Correction changes are bounded to 6 degrees per frame, including every axis in
sparse search seeds. The bounded search uses single/paired-axis seeds, then all
64 rate-bounded six-axis corners if those probes fail, and finally at most 768
additional beam candidates if still needed. It keeps
the original wrist/finger targets after the entry envelope. Unresolved
projections fail explicitly; they are not counted as successful playback.

The first three release frames retain the calibrated clasp's proxy-contact
threshold, then request an avatar-scaled air gap (about 5 mm for these assets).
Finger-to-opposite-palm contact has a separate baseline so it cannot weaken the
other checks. Where the new resting hands are already separated, the rest
threshold is capped at the normal air gap rather than inheriting negative clasp
contact. Closing uses the corresponding cached clearance weights in reverse.
This bounds contact depth but does not require the same finger pairs to remain
in contact.
All 74 owned arm/hand channels, including finger pivots and twists, participate.
Finger chains include tip bones, and three intermediate poses are sampled
between adjacent frames. Separate hard nonnegative margins cover palm volumes,
fingers and forearms against torso/upper-arm capsules, plus upper arms against
torso and tabletop. A forearm and its own upper arm share an elbow and are not a
self-collision pair; only the proximal eighth of the upper arm is omitted from
its torso check at the shoulder attachment. The finite rectangular tabletop
boundary comes from the rendered room node and conservatively covers the round
table's footprint. Its default extra buffer is 8 mm; torso/upper-arm buffers are 10/8 mm.
Captured root rotation remains in FK; external avatar translation is added once.
Body/table checks never inherit the negative clasp finger-contact thresholds.

The current seats opt into `tabletop_contact="true"` for intentional resting
contact. Only palm/finger tabletop padding tapers smoothly from zero at the
calibrated rest to the full 8 mm when any palm landmark moves 30 mm. The envelope
uses actual wrist/knuckle geometry, so translation or rotation restores the
ordinary moving-hand margin. Candidate and sampled-transition checks apply the
same rule. The physical table surface and capsule radii do not move, physical
penetration remains invalid, and forearms/upper arms retain their full padding.
Cross-hand and body checks are unchanged. Other room configurations keep constant
padding; the contact envelope requires separated direct-return resting hands.

These skeleton-derived proxies are conservative engineering approximations, not
a skin/cloth mesh collision engine or a continuous geometric proof. Fine thumb
contact and the naturalness of the adapted workspace still need visual review.
The nine deterministic opening projections are cached before speech.
During playback, a 12-frame rolling window is refreshed by new BAP batches and
the 100 ms observer, covering holds and the return without repeatedly solving
the whole future utterance. Per-turn caches are bounded and discarded between
turns. Local traces distinguish projected frames from observed frame samples.

The projector carries forward the preceding corrective offset instead of
repeatedly discarding and restoring it. When safe, it releases at most one degree
per joint per frame. If releasing every axis together is unsafe, a finite search
releases the safe subset with the greatest progress toward zero. It considers
only nonzero correction axes and preserves the same swept-path checks.
A newly required correction is refined toward the previous
offset using twelve bounded samples, with the same endpoint and swept-path
checks. The existing 16-degree magnitude and 6-degree entry-step limits remain;
the exact-rest frame rejects residual correction above the smaller one-degree
release limit. This prevents an endpoint reset from concealing a return jump.
These bounds address corrective motion, not arbitrary stock gesture acceleration.

The seated root, legs, gaze/head, lips, blinking, and optional MODIFF eyebrows
keep their existing owners. Standing-body channels are filtered from the stock
gesture output; this integration does not authorize stock torso or head behavior
to overwrite the seated pose or the verified gaze schedule. This is the complete
English semantic-to-gesture route with a seated arm adaptation, not unrestricted
full-body animation. It is not a general full-body collision or hand-contact solver.

Normal startup keeps MeaningMiner disabled. Missing resources, incompatible
APIs, changed text, duplicate speech, unbounded animation, and extraction timeouts
fail explicitly. English is the supported path. French/Python NLP services,
NVBG, ASR, cameras, and GPT branches are separate systems and are not enabled.
Semantic appropriateness and effects on MI outcomes remain unvalidated.

## Startup

In **Windows PowerShell**, after deploying the GMIT allowlist:

```powershell
Set-Location 'C:\Users\alafate abulimiti\research\workspace\greta_2.1'
& '.\bin\GMIT\Start-GMIT.ps1' -GroupMI -MeaningMiner
```

For an existing Pure, RL, or imported transcript:

```powershell
& '.\bin\GMIT\Start-GMIT.ps1' -Replay 'C:\absolute\path\to\replay.xml' `
  -MeaningMiner -StartDelaySeconds 15 -TurnGapSeconds 3 -EndHoldSeconds 10
```

The flag enables all three actors automatically. Add `-Muted` to run real TTS,
lip animation, semantic gestures, and timing checks with physical audio output
disconnected. `-GroupMI -Preview -MeaningMiner` checks layout and semantic
extraction only; it cannot demonstrate realized gestures without the speech
timing. Omit `-MeaningMiner` for ordinary playback. Keep tabletop arms enabled.
Hand clearance is enabled with MeaningMiner. `-DisableHandSafety` isolates the
geometry correction for diagnosis; it keeps the staged release/return envelope.

To combine it with MODIFF, run from the **development checkout**:

```powershell
Set-Location 'C:\Users\alafate abulimiti\research\workspace\greta'
& '.\integrations\gmit\Start-GMIT-Modiff.ps1' -MeaningMiner `
  -Replay 'C:\absolute\path\to\replay.xml'
```

MODIFF remains facilitator-only; MeaningMiner applies to all three speakers.

In NetBeans, use `GMIT Group MI` or `GMIT Replay Import` and add
`-Dgmit.meaningminer.enabled=true` to VM options. For silent animation checks,
also use `-Dgmit.audio.muted=true`. The child launcher forwards both properties.
For geometry isolation, it also forwards `-Dgmit.meaningminer.handSafety=false`.
Keep Windows 64-bit JDK 8 and the complete runtime working directory described in
`GROUP_MI.md`. IDE source execution requires its own runtime acceptance.

## Resources, deployment, and recovery

The complete runtime supplies MeaningMiner.jar, WordNet 3.0, the English Stanford
parser, OpenNLP chunker, image-schema dictionaries, and character gesture
lexicons. Local receipts identify actual module code sources and resource hashes.
The English Java path needs no Python NLP server, broker, LLM, camera, or
microphone. MODIFF remains a separate opt-in CPU worker.

The deployment allowlist remains sixteen files. The current eight changed
mappings copy these source files to the same relative paths under `greta_2.1`:
`bin/GMIT/MeaningMinerBridge.java`, `bin/GMIT/MeaningGesture.java`,
`bin/GMIT/SmokeProbe.java`, `bin/GMIT/SeatedScene.java`,
`bin/GMIT/HandCollision.java`, `bin/GMIT/ListenerGaze.java`, `bin/GMIT/Start-GMIT.ps1`, and
`bin/Environments/GMIT/group-mi-room.xml`. The room supplies the distinct rests,
semantic admission, and actor styles, with recalibrated proximal workspace
offsets. Root placement and furniture are retained. No NLP JAR,
dictionary, checkpoint, voice, or mesh is replaced. Tests,
documentation, NetBeans source, and the MODIFF wrapper remain in `greta`.

The parser/Planner can write legacy scratch files including `fml_output_mm.xml`,
`fml_output.xml`, and `test_fml.xml`. The bridge restores the MeaningMiner
diagnostic, and both launchers retain outer backups until their owned child
exits. This also covers a parser that ignores interruption until process exit.
Standalone `MeaningMinerBridge --preflight` similarly runs the parser in an owned
headless child JVM with a 75-second bound. Its parent restores the diagnostic
only after confirming that the child has exited, including on timeout.
Deployment receipts support reverse-order rollback as described in `PROJECT.md`.
Keep transcripts, logs, captures, compiled classes, and receipts local.

## Verification record

### Current source behavior, 2026-11-07

Windows JDK 8 compilation and focused bridge, gesture, geometry, and gaze tests
passed. They cover main-schema admission, bounded continuous return, exact-rest
restoration with residual-correction failure, seed reproducibility and diversity,
short-turn next-speaker priority, and independently disabling nods.

The initial audible runs completed programmatically, but human feedback rejected
their hand placement and facilitator return. Those runs are not final posture
acceptance. The revised scene moves the hands toward the table center: about
60 mm for the facilitator, 37 mm for patient A, and 50/60 mm for patient B.
This retains close/wide/asymmetric rests and uses the guarded direct return
described above. Limited reach and existing offset bounds constrain patient A;
these calibrated poses are not a proof of optimal placement or skin contact.

The user accepted the forward placement and continuous direct return in
`replay-20261107-135325-497`. A subsequent calibration lowered the hands about
9 mm while preserving their horizontal placement and orientation; the user
confirmed that the resting palms no longer appeared to float. The following
muted replay exposed return jitter, traced to alternating corrective offsets.

The continuity fix retains safe offsets, selectively releases them, and refines
new corrections. An exact replay of seven logged candidate sequences covered
1,401 frames with zero failures; all 74 channels were present and all 68
non-correction channels were preserved. Maximum correction/step were
14.785/5.5 degrees, within the 16/6 limits. Minimum hard endpoint/sampled-path
margins were 0.006158/0.007537 mm. On these identical candidate sequences,
within-return corrective direction reversals fell from 212 to 38, and peak
projected joint step from 6.723 to 4.394 degrees. These are numeric comparisons,
not a universal smoothness guarantee. A separate raw-frame reconstruction
passed seven trajectories and 1,402 frames.
Deterministic facilitator and patient-A regression cases also reject unsafe
all-axis release and verify safe single/paired-axis release, strict correction
reduction, and exact agreement with the accepted projected trajectory.

The final audible run `replay-20261107-144004-514` completed seven dispatches,
intended audio schedules, and restorations in F/A/B/F/A/B/F order. All 1,402
projected frames passed; 483 actual BAP samples were observed, with 31 gaze-phase
checks and three exclusive listener nods. The minimum projected body/table
margin was 0.001213 mm; the largest queue batch was 41 ms. This does not
establish sustained 25 fps. Its maximum corrective entry step remained 5.5
degrees; the one-degree limit applies to releasing offsets, not every movement.
Raw extraction retained 27 image schemas, nine main schemas were admitted, and
Planner selected nine gestures, compared with 26 in the November 3 replay of
the same text. This count is specific to this transcript and selection run.
The optional MODIFF worker accepted 108 responses with observed nonzero brow
output. No dialogue model or live GMIT generation was run.

The user confirmed that lowering the hands looked natural and the jitter was
resolved. This final human observation concerns the return and tabletop contact;
earlier November 7 observations cover the other conversational changes.
WordNet CR/LF diagnostics and disconnected Furhat/ActiveMQ sender errors remain
in the logs; completion is not a claim of an exception-free log.
Earlier November 3 acceptance below applies to its then-deployed configuration.

A final compatibility check restored the legacy workspace mode's original
24/12-frame timing and exact cached reverse path. Three matched historical
trajectories (405 frames) pass both the original `d681d91d` implementation and
the restored mode. The accepted direct mode was checked separately: all 74
candidate and projected channel values were identical across seven trajectories
and 1,402 frames before and after this compatibility repair. No additional GUI
replay was run for that isolated change.

All sixteen allowlisted source/runtime hashes matched after each deployment.
The launcher compiles the helper sources against the existing runtime JARs.
Receipts under runtime `bin/GMIT/local/` retain originals:

- `deploy-20261107T121639065524Z/receipt.json`: seven mappings listed above,
  excluding HandCollision.
- `deploy-20261107T123651108279Z/receipt.json`: room XML only.
- `deploy-20261107T125314443861Z/receipt.json`: room XML, SeatedScene, and
  MeaningGesture for forward hand placement and direct return.
- `deploy-20261107T133942565146Z/receipt.json`: room XML, SeatedScene, and
  HandCollision for calibrated tabletop contact and continuous correction.
- `deploy-20261107T134736840154Z/receipt.json`: MeaningGesture's legacy
  workspace compatibility repair; the accepted direct path is unchanged.

Restore the full pre-session deployment in reverse order, from the development
checkout in WSL:

```sh
for receipt in deploy-20261107T134736840154Z deploy-20261107T133942565146Z deploy-20261107T125314443861Z deploy-20261107T123651108279Z deploy-20261107T121639065524Z; do
  python3 integrations/gmit/deploy.py --runtime '/mnt/c/Users/alafate abulimiti/research/workspace/greta_2.1' --rollback "/mnt/c/Users/alafate abulimiti/research/workspace/greta_2.1/bin/GMIT/local/$receipt/receipt.json"
done
```

### Historical body/table guard, 2026-11-03

The replacement body/table guard and actor profiles on 2026-11-03 compiled with
Windows JDK 8; focused gesture, bridge, and geometry tests passed. Tests include
palm/table and upper-arm/table collisions, connected-elbow exclusion, and sparse
seed rate bounds. Seven captured styled trajectories were checked against the
exact public sources and XML: 1,133 frames, all 74 arm channels present, all 68
non-correction channels of each retargeted candidate retained, corrective deltas
at most 16 degrees and their
steps at most 6 degrees. The smallest hard endpoint/sampled-path margins were
0.486/2.225 mm beyond the configured proxy buffers. This is engineering geometry
evidence, not a mesh or continuous-collision proof.

The source-only guarded muted replay `muted-20261103-144006-167` completed seven
dispatches, seven intended audio schedules, 28 gaze checks, and seven gesture
restorations. All 1,135 projected frames passed; 365 actual BAP samples were
observed. The minimum projected body/table margin was 0.795 mm. Per-turn solver
totals were 218–1,240 ms and the largest queue batch was 315 ms; this does not
establish sustained 25 fps or imperceptible latency. Inspected captures showed
separated hands in front of the body and different actor motions. Human
acceptance was collected in the later audible run below.

The first replacement deployment receipt is
`bin/GMIT/local/deploy-20261103T134646766525Z/receipt.json` in the runtime.
All 16 allowlisted hashes matched; only the seven mappings above changed.
The following audible run `replay-20261103-144948-163` stopped during the third
turn: a newly selected patient-B trajectory could not satisfy the sampled
transition check despite clear endpoint geometry. This failed run is not
acceptance evidence. Its seven legacy scratch files were restored after exit.
The missing coordinated six-axis case was added to the bounded search without
changing limits. Both actual failed transitions then passed with a 2.055 mm
minimum endpoint/sampled-path margin. Rechecking the seven earlier complete
traces and this newly captured prefix covered 1,262 frames, preserving all 68
non-correction channels and the 16-degree/6-degree bounds. The aborted run's
unrecorded later motion was not covered by this offline regression.
The corrective follow-up receipt is
`bin/GMIT/local/deploy-20261103T140205205393Z/receipt.json`; only
`bin/GMIT/HandCollision.java` changed, and all 16 source/runtime hashes matched.
To restore the state before this replacement, roll back these two receipts in
reverse order using `integrations/gmit/deploy.py --runtime <runtime> --rollback
<receipt>`. Keep both backup directories until that recovery is no longer needed.

The final audible replay `replay-20261103-150231-035` completed all seven turns
in F/A/B/F/A/B/F order, with seven intended audio schedules, seven restorations,
and 28 verified gaze phases. All 1,132 projected frames passed; 363 actual BAP
samples were observed. The smallest projected body/table margin was 0.913 mm
beyond the configured buffers. The 26 selected semantic gestures received their
actor profiles (facilitator 10, patient A 9, patient B 7). MODIFF accepted all 109
responses; 1,857 sampled frames contained nonzero facilitator brows. Both owned
processes exited successfully, and all seven legacy scratch files were restored.
Per-turn solver totals were 187-1,454 ms; the largest queue batch was 150 ms.
The earlier 315 ms batch remains a measured limit, so no sustained 25 fps bound
is claimed.

During this run the user confirmed (translated): "No obvious intersections,
distinct styles, everything else normal," in response to a question covering
body/table clearance, motion fluency, sound, lips, gaze, and eyebrows. This is
acceptance of the observed replay, not proof for every lexicon gesture, avatar,
clothing mesh, or continuous trajectory. Independent integration review found
no deployment blocker. Ordinary replay still requires `-MeaningMiner` to enable
this extension; the three room profiles apply automatically when it is enabled.

The following paragraphs describe the rejected earlier candidate, not the
replacement's geometry or acceptance status.

On 2026-11-03, the experimental helpers, focused tests, and NetBeans launcher compiled
with Windows JDK 8. The tests include partial BAP masks, immutable return paths,
non-arm channel rejection, and the intersection of global/rate correction bounds.
A local driver compiled the actual public helpers and read the actual room XML;
all seven captured trajectories passed complete opening, motion, and return
checks using exactly the runtime's 74 arm channels. Earlier experiments with an
incomplete channel set are not acceptance evidence.

The deployed muted replay `replay-20261103-132021-855` completed seven dispatches,
seven intended audio schedules, and seven checked gesture restorations in
F-A-B-F-A-B-F order. All three native skeleton calibrations passed. The guard
recorded 1,138 projected frames and 389 observed frame samples. Total solver time
per turn was 87–167 ms; the largest queued batch took 46 ms on this run. These
are engineering observations, not a real-time guarantee. Render captures showed
separated hands and the restored clasp, but did not establish body clearance.

The subsequent combined audible replay completed seven turns and 1,117 projected
frames; the optional MODIFF worker accepted 109 requests. Human review rejected
the result: the moving hands still intersected the body and the gestures appeared
repetitive. Torso and same-side upper-arm geometry are outside this guard's
coverage. Constant workspace offsets also change the visible arm posture, so
passing the existing proxy checks does not establish acceptable adaptation.
The selected-gesture audit found 24 signals across nine library references;
16 signals used an OBJECT variant. This establishes repeated semantic selection,
not identical complete trajectories or a measured cause of perceived similarity.
Per-actor expressive styles have not been configured.
Runtime character configuration maps LENA and EDDY to the same Camille lexicon
and gestuary. LAURA uses separate files, but their 27 image-schema behavior sets
and all nine gesture definitions selected in this replay are structurally
identical to the corresponding Camille entries. Distinct actor chains and avatar
meshes therefore do not imply distinct gesture repertoires.

The runtime deployment was rolled back after this rejection. All 16 allowlisted
paths matched their original receipt hashes (the added helper was absent again),
and all seven tracked scratch-file hashes matched the pre-run snapshot. Source
changes and private diagnostic evidence remain an unpublished experiment. Do not
redeploy this candidate or describe it as accepted collision prevention.

The hand-protection deployment receipt is
`deploy-20261103T122005082501Z/receipt.json` under runtime `bin/GMIT/local/`.
It has already been rolled back; do not apply its rollback a second time.

On 2026-11-02, the helpers and NetBeans launcher compiled with Windows JDK 8.
Focused checks cover text/marker boundaries, duplicate speech/callback rejection,
per-actor BAP capture, late partial frames, seated adaptation, and return to the
clasp. Owned-child timeout tests cover both existing and absent diagnostic files.
The real headless parser check extracted `object` and `small` from a fixed public
sentence. Existing WordNet direct-access/line-ending and duplicate SLF4J warnings
remain; they did not prevent genuine semantic output.

The seven-turn muted run `replay-20261102-180438-530` completed all seven
dispatches, audio callbacks, movement observations, and restorations, with 28
gaze checks. It selected 25 gestures across nine library references. Render
inspection led to the final tabletop clearance constraints. The subsequent
four-turn layout excerpt `replay-20261102-180938-761` passed with all three actors;
captures were inspected for the final shoulder/elbow adaptation.

The final combined audible run `replay-20261102-181133-816` completed seven
dispatches, seven intended audio callbacks, seven elapsed schedules, seven arm
movement/restoration checks, and 28 gaze checks in F-A-B-F-A-B-F order. The
Planner selected 26 gestures across ten references: 10 facilitator gestures,
8 patient A gestures, and 8 patient B gestures, including left, right, and both
hand variants. Post-Realizer snapshots contained resolved gesture phases and
concrete time markers. MODIFF accepted 107 responses, discarded one stale
response, and observed nonzero eyebrows in 1,831 sampled frames. Both the
renderer and the CPU worker exited with status 0. Original text hashes matched.

Human confirmation of this full-library version's naturalness, physical audio,
and lip synchronization is pending. The earlier facilitator-only prototype's
human acceptance does not establish acceptance of this expanded version.
These short runs do not validate every gesture in all 27 schema sets, every
possible hand collision, other languages, or scientific MI effectiveness.
Interactive NetBeans execution was not repeated; launcher compilation passed.

The default-off silent preview `group-mi-20261102-181607-909` passed with nine
gaze checks and no MeaningMiner or MODIFF activity. Runtime launches preserved
all seven recorded scratch files and the package's 67 pre-existing Git status
entries. No checkpoint, voice, mesh, dictionary, or JAR was replaced.

Successful deployment receipts for this extension are under the runtime's
`bin/GMIT/local/`. Roll back in reverse order using `deploy.py --rollback`:
`deploy-20261102T172207339802Z/receipt.json`,
`deploy-20261102T170922703823Z/receipt.json`,
`deploy-20261102T170420377856Z/receipt.json`,
`deploy-20261102T170021485918Z/receipt.json`,
`deploy-20261102T164938368355Z/receipt.json`, and
`deploy-20261102T163952390546Z/receipt.json`.
A source-change race interrupted one intermediate deployment before playback;
its two partial writes were restored from verified originals, with a separate
local recovery receipt. It is not part of this rollback sequence.
