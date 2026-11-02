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
with a seated gain of 0.35; wrist and finger values retain the library's absolute
hand shape without that gain reduction. This reference
is an engineering approximation of stock neutral, not a measured contact pose.
The adapter also limits lowering shoulder/elbow flexion beyond the calibrated
tabletop base and adds up to six degrees of outward shoulder clearance during
stock motion. These seated bounds can reduce the extent of a directional gesture.
They do not detect mesh intersections. It returns to the clasp
after the speech and gesture tail; the next speaker waits for that return.
Audio timestamps bound the turn without replacing the original gesture timing.

The seated root, legs, gaze/head, lips, blinking, and optional MODIFF eyebrows
keep their existing owners. Standing-body channels are filtered from the stock
gesture output; this integration does not authorize stock torso or head behavior
to overwrite the seated pose or the verified gaze schedule. This is the complete
English semantic-to-gesture route with a seated arm adaptation, not unrestricted
full-body animation. It is not a general collision or hand-contact solver.

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
`bin/GMIT/MeaningGesture.java`, `bin/GMIT/SmokeProbe.java`,
`bin/GMIT/SeatedScene.java`, and `bin/GMIT/Start-GMIT.ps1`. No NLP JAR,
dictionary, checkpoint, voice, mesh, or calibrated room XML is replaced. Tests,
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
