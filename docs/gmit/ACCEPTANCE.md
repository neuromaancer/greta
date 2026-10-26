# P0/P1 evidence — 2026-10-26

P0 investigation and P1 acceptance are complete. After the first automatic run,
the user reported seeing it but being unsure about audio. A deliberately slower
second playback added a 10-second countdown, three-second inter-turn gaps and
a 10-second ending hold. The user then confirmed (translated from Chinese):
“I can hear it; the order and the corresponding characters are correct.”
This supplements program/render evidence with actual visible/audible observation;
fine-grained perceptual synchronization and naturalness were not separately rated.
This is synthetic speech, not GMIT replay or live simulation.

## Human-confirmed observation replay

`greta_2.1/bin/GMIT/local/run-20261026-171221-063/` exited 0 and contains 155
PNGs and four WAVs. It passed the same routing, duplicate-audio and inactive-jaw
checks, with all four mixer buffer positions ending at -2. The user's audible
order/person confirmation refers to this replay. All four protected runtime
scratch/config files were restored byte-for-byte afterward.

The final review then identified a timeout-budget edge case at the maximum
observation parameters. The launcher now derives the watchdog from the bounds
(maximum 278 seconds) and the per-turn margin includes its configured gap.
This final timing-budget-only adjustment was compile/syntax/bounds checked and
redeployed; no additional audiovisual replay was necessary or performed.

## Accepted run

Runtime evidence directory:
`greta_2.1/bin/GMIT/local/run-20261026-170058-884/`.
The directory name is the Windows runtime clock label, not an inferred timezone.

`Start-GMIT.ps1 -Smoke` exited 0 using existing 64-bit JDK 8 and runtime JARs.
`stdout.log` contains `COMPLETE synthetic_sequence=F,A,B,F`. It records the
loaded JAR paths: notably some classes resolve through
`Common/Lib/Internal/lib/`, while CereProc and Ogre resolve through their
top-level runtime JARs. No JAR was rebuilt or replaced.

| Turn | Actor | Synthesized PCM duration | Audio events | Mixer state after wait |
| --- | --- | ---: | ---: | --- |
| 1 | facilitator | 3.876 s | 1 | -2 (finished in Greta) |
| 2 | patient-a | 3.613 s | 1 | -2 |
| 3 | patient-b | 3.631 s | 1 | -2 |
| 4 | facilitator | 4.065 s | 1 | -2 |

All four WAV files are mono, 48 kHz. Only the expected actor produced audio
events during each turn. Advancement uses Greta's own clock, with a one-second
gap after its scheduled end; it does not compare Greta's relative clock with
Unix wall time. The probe aborts on duplicate/missing/wrong-actor audio,
inactive-actor jaw movement, stalled clock, or a timeout. A mixer-finished flag
does not prove sound reached physical speakers.

There are 109 captured PNGs (initial scene plus 108 turn frames). The capture
uses Greta's existing OgreRenderTexture and the live player's same camera and
scene. The lead inspected the scene and representative speaking frames:

- `00-scene.png`: patient A left (purple shirt), facilitator center (green),
  patient B right (turquoise), all visible in the same rendered environment.
- `01-facilitator-007.png`: facilitator speaking.
- `02-patient-a-014.png`: patient A turn.
- `03-patient-b-011.png`: patient B speaking.
- `04-facilitator-015.png`: final facilitator turn and eyelid movement sample.

Per actor, 108 FAP samples were collected. Jaw ranges were facilitator 0–67,
patient A 0–47, patient B 0–69. The probe required every inactive actor's jaw
to remain zero. Eyelid ranges were 0–940, 0–626, and 0–940 respectively;
each actor's original blinking flag was true. These establish functioning
facial-animation channels and role isolation at the sampled frames. They are
not a human judgment of naturalness or exact audiovisual synchronization.

A subsequent pixel comparison of fixed mouth regions against the initial frame
found visible changes only in the current speaker: maximum mean RGB differences
were 8.250 (F), 8.433 (A), 7.596 (B), 7.067 (F). Other mouths stayed at 0,
apart from sub-0.01 rendering variation at the facilitator. This corroborates
the FAP routing checks using the rendered images themselves.

## Focused checks

- 42 unique graph modules, 44 connections; all module types and connector IDs
  exist in the installed Modular registry, with no dangling references.
- Four speech-only BML files parse and preserve the intended short texts.
- Three distinct CharacterManager objects were observed in the runtime.
- Probe compiled with the installed JDK; PowerShell launcher executed.
- All eight deployed files read back byte-for-byte equal to the source files.
- Temporary fixture test exercised deployment, original-file backup,
  refusal to overwrite a later user edit, and rollback restoring originals and
  removing only newly deployed files. This rollback check used `/tmp`, not the
  user's running environment.
- Source diff whitespace check passed. No existing tracked Greta runtime
  source/resource changes were added beyond the pre-session dirty state.
- The launcher restores its recorded runtime scratch/configuration files;
  local receipts, backups and captured evidence remain available.
- Final byte checks confirmed restoration of `ModularSave.xml`,
  `fml_to_bml.xml`, `Log/Ogre.log` and generated `Plugins_OpenGL.cfg` against
  this accepted run's saved originals. Runtime Git status retains the 63
  pre-existing rows and adds only the three GMIT directories.

## Rejected runs and limitations

Earlier runs are retained for diagnosis and are not acceptance evidence:

- `165028`: PowerShell native compiler invocation handling failed; corrected.
- `165148`: unrelated newer JAVA_HOME failed legacy Ogre loading; launcher now
  selects existing JDK 8. The optional Ini Manager UI node also raised a null
  reference; the new scene now relies on the existing global IniManager.
- `165253`: FML produced duplicate speech; the probe aborted. Replaced only the
  new synthetic input route with existing BML readers. Window-buffer capture
  returned black, so the probe uses the renderer's same-camera texture API.
- `165650`: camera was not ready; added a bounded initialization wait.
- `165753`: emitted four unique audios but an incorrect clock comparison
  advanced too early, overlapping animation. Rejected after frame review;
  fixed to Greta's clock and added inactive-jaw checks before the accepted run.

The original runtime registry reports XML ordering errors and missing optional
module/parameter registrations. Existing shaders emit warnings. CereProc's
inherited Furhat sender path reports a null sender; no broker was configured
by this task. These diagnostics remain in stderr/stdout and were not hidden or
fixed by overwriting unrelated runtime code.

The desktop Computer Use connection failed with
`sandboxCwd is not a local file URI: file:///mnt/c/.../greta` before any app
control. Therefore the captured scene is application-rendered evidence, not a
desktop screenshot, and the assistant has not directly heard the Windows
speakers. The user's later observation supplies the audible/role-order
confirmation. Blinking and mouth isolation are also supported by the captured
rendered frames and FAP samples above.

P2/P3 remain outside this session: verified gaze/head/gesture, authentic GMIT
replay, a single bounded live episode, model/checkpoint setup and performance.
