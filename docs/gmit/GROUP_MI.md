# Synthetic group motivational interviewing demo

This short, authored conversation uses the existing shared Greta scene. A
facilitator and two fictional participants discuss reducing red meat. It is a
repeatable engineering demonstration, not generated GMIT output or evidence of
clinical effectiveness. No LLM, policy checkpoint, training, or evaluation is run.

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
Its **Stop synthetic run** button requests cancellation of this run. The
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
them. The scene, avatar resources, blinking configuration, and runtime JARs are
the same as the accepted P1 setup.

`-Smoke` still runs the original four-line F-A-B-F test. A launch without either
switch still opens the scene for its original four manual readers. Choose one
mode per launch; `-GroupMI`, `-Smoke`, and `-Original` cannot be combined.

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

## Deployment and evidence

The allowlist adds the scenario XML and updates the existing launcher and probe.
Every listed file maps to the identical relative path in `greta_2.1`; the other
P1 assets remain unchanged. Use `integrations/gmit/deploy.py` to preview and apply,
as documented in [PROJECT.md](PROJECT.md#explicit-deployment-and-recovery).
Each deployment retains originals and a SHA-256 receipt, with rollback that
refuses to overwrite later edits.

Local runs are stored under `bin/GMIT/local/group-mi-*/`, including logs,
generated BML, WAV audio, and render-texture PNGs. These artifacts stay ignored.
An audio callback and elapsed schedule establish software behavior; they do not
establish sound heard from the physical speakers. Render-texture evidence and
human visual/audible observation are reported separately. Listener gaze,
head gestures, and live GMIT generation remain unverified.

## Verified on 2026-10-27

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

To restore the previous P1 launcher/probe and remove the new scenario, run from
the development checkout:

```sh
python3 integrations/gmit/deploy.py --runtime ../greta_2.1 --rollback ../greta_2.1/bin/GMIT/local/deploy-20261027T123142489436Z/receipt.json
```

The original four-turn smoke mode was retained but was not separately replayed
during this extension. Stop-button interaction and forced watchdog expiry were
not exercised in the completed run. This acceptance does not validate live GMIT,
gaze/head/gesture behavior, an updated source-built Greta runtime, or MI quality
against a clinical rating instrument.
