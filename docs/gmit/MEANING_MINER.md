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
does not cap a turn at one schema or replace them with two hand-written profiles.
Empty semantic output means speech without an invented gesture. Selection is
content-dependent, so enabling the library does not make every sentence or both
hands move continuously.

## Seated adaptation and ownership

The stock realization supplies the actual BAP arm and finger values and frame
times. `SeatedScene` captures those frames and blends them from the calibrated
clasp, retaining both arms and the selected hand shapes. Shoulder and elbow
motion is retargeted as a delta from the first observed stock value per joint,
with a base seated gain of 0.35 and per-style left/right multipliers; wrist and finger values retain the library's absolute
hand shape without that gain reduction. This reference
is an engineering approximation of stock neutral, not a measured contact pose.
Styled playback uses explicit geometry instead of the old angle-sign flexion
clamp and fixed outward shoulder offset. The room's `gesture-arm-offset` entries
add constant shoulder abduction, flexion, twisting, and elbow flexion offsets.
An offline wrist-position fit places the open hands forward above the table;
it does not replace the selected gestures
with an authored gesture template. Missing raw channels keep their seated value
plus the explicit workspace offset, without borrowing a future joint value.
The resting clasp is raised approximately 20 mm through shoulder/elbow changes
to clear the tabletop proxies; root, furniture, wrists, and finger angles stay
unchanged. These poses are specific to the three current avatars.

The seat's `gesture_style` selects an engineering expression profile. The
Planner still chooses all semantic signals; the profile adjusts Greta's existing
SPC/TMP/FLD/PWR expressivity parameters before realization and scales the captured
proximal-arm deltas afterward. It does not change references, text, or word-marker
links, and does not assign a GMIT patient personality.

| Actor | Style | Left/right proximal gain | SPC / TMP / FLD / PWR |
| --- | --- | --- | --- |
| facilitator | `balanced` | 0.35 / 0.35 | 0.50 / 0.50 / 0.65 / 0.40 |
| patient-a | `reserved` | 0.2275 / 0.2975 | 0.35 / 0.40 / 0.40 / 0.25 |
| patient-b | `animated` | 0.4725 / 0.4025 | 0.65 / 0.65 / 0.70 / 0.55 |

At 25 fps, the entry envelope first releases the fingers (frames 0–3), moves
the arms into the separated workspace (3–9), then blends into the current stock
wrist/finger and arm targets (9–18). This adapts the first 720 ms of captured
motion; original BAP frame numbers, speech text, and word references are retained.
The facilitator's `gesture_release_lead="0.3"` starts 30% of the workspace
movement during finger release, avoiding the calibrated fingers crossing while
the arms remain stationary. The same checked path is used in reverse.
The return lasts at most 24 frames (960 ms). It approaches the verified separated
pose, requires an exact join, then reverses the cached opening path into the
clasp. The next speaker waits for that return.

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
other checks. Closing uses the same thresholds in reverse. This bounds contact
depth but does not require the same finger pairs to remain in contact.
All 74 owned arm/hand channels, including finger pivots and twists, participate.
Finger chains include tip bones, and three intermediate poses are sampled
between adjacent frames. Separate hard nonnegative margins cover palm volumes,
fingers and forearms against torso/upper-arm capsules, plus upper arms against
torso and tabletop. A forearm and its own upper arm share an elbow and are not a
self-collision pair; only the proximal eighth of the upper arm is omitted from
its torso check at the shoulder attachment. The finite rectangular tabletop
boundary comes from the rendered room node and conservatively covers the round
table's footprint. It includes an 8 mm buffer; torso/upper-arm buffers are 10/8 mm.
Captured root rotation remains in FK; external avatar translation is added once.
Body/table checks never inherit the negative clasp finger-contact thresholds.

These skeleton-derived proxies are conservative engineering approximations, not
a skin/cloth mesh collision engine or a continuous geometric proof. Fine thumb
contact and the naturalness of the adapted workspace still need visual review.
The nine deterministic opening projections are cached before speech.
During playback, a 12-frame rolling window is refreshed by new BAP batches and
the 100 ms observer, covering holds and the return without repeatedly solving
the whole future utterance. Per-turn caches are bounded and discarded between
turns. Local traces distinguish projected frames from observed frame samples.

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

The existing deployment mappings copy these source files to the same relative
paths under `greta_2.1`: `bin/GMIT/MeaningMinerBridge.java`,
`bin/GMIT/MeaningGesture.java`, `bin/GMIT/HandCollision.java`, `bin/GMIT/SmokeProbe.java`,
`bin/GMIT/SeatedScene.java`, `bin/GMIT/Start-GMIT.ps1`, and
`bin/Environments/GMIT/group-mi-room.xml`. The room adds separate gesture offsets
and actor styles. Six proximal resting joint values per actor lift the clasp by
approximately 20 mm; wrist/finger targets and furniture are retained. No NLP JAR,
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
