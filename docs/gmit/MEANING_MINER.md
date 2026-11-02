# Facilitator MeaningMiner gestures

This opt-in extension extracts image-schema intentions from the facilitator's
original English utterances with Greta MeaningMiner. It adds one bounded
right-hand gesture per eligible utterance in the seated scene. Pure, RL, and
imported transcripts share this playback layer; text, actor order, dialogue
decisions, and the existing per-actor speech path are unchanged.

## Behavior and limits

`MeaningMinerBridge` calls the real `ImageSchemaExtractor.processText_2` API.
It keeps only `imageschema` intention types, deduplicates and sorts them, and
does not connect an intention performer, Planner, FML reader, or TTS. Empty
semantic output produces no gesture. One facilitator extractor analyzes the
facilitator turns before playback; this layer does not analyze the patients.

The first sorted schema selects one of two conservative authored tabletop
profiles: force/path/process labels select the slightly larger profile, and
other labels select a smaller hand offer. This is an utterance-level semantic
cue, not word-aligned realization or the full MeaningMiner gesture dictionary.
Different sentences can select the same motion. Semantic appropriateness and
effects on MI outcomes have not been established.

Actual Greta Audio timestamps determine gesture timing. Utterances shorter than
2.4 seconds do not gesture. Only the facilitator's right shoulder, elbow, wrist,
and fingers move, then return to the calibrated clasp before speech ends.
Existing seated legs/root, left arm, gaze, lips, blink, and optional MODIFF brows
retain their owners. The hand profiles are not a collision solver.

Normal startup keeps this feature disabled. English is the supported language.
Missing resources, incompatible APIs, parser failures, and 30-second extraction
timeouts fail explicitly; there is no keyword-based substitute for the parser.

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

`-GroupMI -Preview -MeaningMiner` renders one semantic hand gesture without
speech, using an explicitly synthetic preview interval. Omit `-MeaningMiner`
for ordinary playback. Tabletop arms must remain enabled.

To combine it with MODIFF, run from the **development checkout**:

```powershell
Set-Location 'C:\Users\alafate abulimiti\research\workspace\greta'
& '.\integrations\gmit\Start-GMIT-Modiff.ps1' -MeaningMiner `
  -Replay 'C:\absolute\path\to\replay.xml'
```

In NetBeans, use `GMIT Group MI` or `GMIT Replay Import` and add
`-Dgmit.meaningminer.enabled=true` to VM options. The launcher forwards this
option and compiles the additional helpers. Keep Windows 64-bit JDK 8 and the
complete runtime working directory described in `GROUP_MI.md`. IDE source
execution requires its own runtime acceptance.

## Resources, deployment, and recovery

The complete runtime provides MeaningMiner.jar, WordNet 3.0, the English Stanford
parser, OpenNLP chunker, and image-schema dictionaries. Local receipts identify
the actual module code source and resource hashes. The English Java path needs
no Python NLP server, broker, LLM, camera, or microphone. MODIFF remains separate.

New mappings are `greta/bin/GMIT/MeaningMinerBridge.java` and
`greta/bin/GMIT/MeaningGesture.java` to the same relative paths in `greta_2.1`.
Existing `SmokeProbe.java`, `SeatedScene.java`, and `Start-GMIT.ps1` mappings are
updated. No NLP JAR, dictionary, checkpoint, voice, or mesh is replaced. Tests,
documentation, NetBeans source, and the MODIFF wrapper remain in `greta`.

The legacy parser writes `fml_output_mm.xml` in the working directory. The bridge
restores its previous contents/existence after extraction; both launchers also
back it up and restore it after their owned child exits. A timed-out parser can
ignore interruption, so restoration must wait for child termination. Deployment
receipts support reverse-order rollback as described in `PROJECT.md`. Keep logs,
captures, generated classes, and receipts local.

## Verification

On 2026-11-02, Windows JDK 8 compiled the helpers and NetBeans launcher. Focused
tests passed for marker filtering, XML/text boundaries, gesture timing, bounded
motion, and return to the calibrated pose. A headless check loaded the existing
runtime `Common/Lib/Internal/lib/MeaningMiner.jar` and extracted `object` and
`small` from a fixed public sentence, without GUI or speech. Existing WordNet
direct-access/line-ending and duplicate SLF4J-binding warnings were printed;
the check still returned genuine semantic intentions. This establishes parser
readiness, not gesture quality or audiovisual acceptance.

The silent preview `group-mi-20261102-164547-031` extracted real schemas from
five facilitator utterances, scheduled one preview gesture, observed its actual
arm BAP values, and verified restoration. The combined recorded replay
`replay-20261102-165156-652` then completed seven dispatches, seven intended audio
callbacks, and seven elapsed schedules in F-A-B-F-A-B-F order. All three
facilitator gestures were observed and restored; 28 gaze checks passed. MODIFF
accepted 103 replies, rejected one stale reply, and observed nonzero eyebrow
channels during 1,772 sampled frames. The renderer and CPU worker exited with
status 0. Higher-resolution render captures were inspected for hand placement.

During that combined replay, the user confirmed on 2026-11-02 (translated):
"The gestures and return look natural; everything else is normal." The question
explicitly covered hand motion/restoration, speech, lip synchronization, gaze,
and eyebrow feedback. This is audiovisual acceptance of the bounded three-actor
replay, not validation of the complete gesture dictionary or scientific efficacy.

The default-off silent preview `group-mi-20261102-165449-808` completed with nine
gaze checks and no MeaningMiner or MODIFF activity. All 15 deployed allowlist
files matched their source hashes. The runtime retained its 67 pre-existing Git
status entries, and the recorded scratch/checkpoint hashes were unchanged.
The seven-turn replay exercised the smaller offer profile; the larger profile
has numerical bounds/return tests but no separate human visual acceptance yet.
NetBeans launcher compilation passed; interactive IDE execution was not repeated.

Deployment receipts for this change, newest first, are under the runtime's
`bin/GMIT/local/`: `deploy-20261102T155044028400Z/receipt.json`,
`deploy-20261102T154526404340Z/receipt.json`, and
`deploy-20261102T154431347035Z/receipt.json`. Use the existing `deploy.py
--rollback` command in that order to recover the previous helper files.
