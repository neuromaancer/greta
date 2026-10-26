# GMIT in Greta — Project Instructions

## Repository language

This is a public project. Write all repository contributions in English,
including project instructions, agent definitions, documentation, code comments,
configuration descriptions, examples, and commit messages. Translate user
feedback into English when documenting it, and label translated quotations.
Communicate with the user in their requested language; this does not change the
English-only repository policy.

## Goal and current scope

Create one facilitator and two patients in a shared Greta scene, driven by the
existing GMIT automatic simulation. Preserve basic speech, lip synchronization,
blinking, and verified gaze, head movement, and gestures. Do not enable VAP,
MODIFF, OpenFace, ASR, cameras, or advanced expression prediction in the initial
phase.

Workspace roles:

- `greta`: development source in the personal fork; task branch
  `feat/gmit-multiparty`.
- `greta_2.1` (also called `greta2.1` by the user): the complete runtime package.
  Preserve its additional checkpoints, characters, voices, DLLs, JARs, and other
  resources.
- GMIT: the separate private dependency repository at
  `/home/abulimiti/workspace/gmit` in WSL.

The lead agent owns interfaces, directory boundaries, integration, deployment,
and final acceptance. Complete reversible work and necessary verification within
the user's current authorization. Ask only when essential information is
inaccessible, a consequential semantic choice remains unresolved, or the next
action exceeds authorization. Explain the specific reason. Do not stop at a plan.

## Workspace and version control

First read the applicable `AGENTS.md` files in each directory and inspect actual
absolute paths, Git roots, HEADs, branches, uncommitted changes, OS/shell, and
launch commands. Do not infer versions from directory names. Preserve existing
changes; never use `reset --hard` or `clean -fdx` to tidy a runtime environment.

Develop from `greta`. Check the existing local and remote
`feat/gmit-multiparty` branches; do not recreate, reset, or overwrite them.
`origin` is the personal fork `neuromaancer/greta`; `upstream` is `isir/greta`.
When updating the development baseline, fetch current remote state, synchronize
the fork's `master` with `upstream/master`, and merge it into the feature branch
while preserving published development history. Do not substitute a stale local
master for the current upstream branch.

The feature branch also contains the original Greta 2.1 Sam VAP/MODIFF
integration. Resolve overlapping code, resources, and renames by preserving
valid behavior and checking dependencies, module registrations, and graph
connections. Do not select an entire side with ours/theirs. Synchronizing source
branches does not upgrade the complete runtime package; distinguish the source
baseline from the runtime JARs actually used for acceptance.

Supply local paths, model/checkpoint locations, endpoints, and credentials through
local configuration or the environment. Use paths appropriate to each shell.
Do not treat Windows UNC paths as Linux paths or assume WSL and Windows share
the same localhost.

## Directory responsibilities

`greta` is the canonical source for this project's configuration and code changes.
Prefer `integrations/gmit/` for the general Python adapter,
`bin/Configurations/Greta/GMIT/` for new scene configurations, and
`bin/Examples/GMIT/` for synthetic behavior examples. Follow existing conventions
where necessary, but record ownership before changing locations.

Use `greta_2.1` for execution. Deploy only explicitly listed new configurations,
examples, or designated build artifacts. Do not synchronize all of `bin`, copy
entire resource trees, or overwrite original configurations. Record each
source-to-runtime mapping on first deployment. Back up existing artifacts before
replacement and retain a recovery method. After source changes, verify the JARs
actually loaded by the runtime.

GMIT is initially read-only. Inspect its implementation and import or call it
from the adapter; do not copy private source, prompts, checkpoints, or real
transcripts into the public Greta fork. Preserve its existing `AGENTS.md` and
research-service boundaries. Do not modify the `gmit_eval` service for this demo.
If a core observer hook is necessary, explain the smallest change and its
justification, then follow the user's authorized scope.

The lead agent normally owns runtime writes, GUI, audio, ports, and deployment.
It may explicitly hand one runtime integration session to a worker, which must
return control afterward. Multiple agents must not control the same running
instance. Routine ownership handoffs do not require additional user approval.

## Launch, deployment, and local artifacts

The three-character entry point is
`bin/Configurations/Greta/GMIT/GMIT - 3 characters - CereProc.xml`. Run
`bin/GMIT/Start-GMIT.ps1` from the complete runtime package. A normal launch opens
the scene for manual dispatch through the four numbered readers; `-Smoke` runs
the bounded synthetic F -> A -> B -> F sequence. Commands and optional
observation delays are documented in `docs/gmit/PROJECT.md`. Do not describe this
synthetic smoke test as a real GMIT simulation.

The existing Ogre/CereProc path was verified with Windows 64-bit JDK 8. Do not
blindly inherit system `JAVA_HOME` or the JDK used to run NetBeans itself. The
launcher accepts an explicit `-JavaHome`. In NetBeans, open `application/Modular`
and configure the project's JDK, complete runtime `bin` working directory, scene
argument, and Win64 DLL path separately. Replace other developers' absolute paths
in local settings. IDE source execution and the accepted runtime JARs come from
different builds; compilation success does not establish runtime acceptance.

`integrations/gmit/deployment.json` is the deployment allowlist. `deploy.py`
prints a plan by default and deploys only with `--apply`. Review the purpose and
source-to-runtime mapping of every allowlist change. Retain originals, SHA-256
receipts, and rollback commands for each deployment. Restore the full previous
state by rolling back this session's receipts in reverse order; do not overwrite
subsequent edits. Do not include pre-existing runtime-package changes in
development-repository commits.

Under `bin/GMIT/local/`, commit only `.gitignore`. Keep run logs, PNG/WAV captures,
compiled classes, deployment backups, and receipts local. Public documentation
should contain necessary evidence summaries and verification limits, without
private runtime data or credentials. For instruction/documentation maintenance
or Git publication alone, check syntax, links, and actual diffs; do not rerun
GUI, speech, or models solely for those changes.

## Agent responsibilities

Run at most two implementation workers concurrently:

- `greta_engineer`: the shared three-character scene, independent actor chains,
  Greta input, and basic behavior.
- `gmit_adapter`: the real simulation entry point, structured events, external
  adapter, and single-episode runner.

Use `integration_reviewer` for independent review of diffs, behavior semantics,
and evidence. It is read-only by default and does not control GUI or audio.
Project role definitions live in `.codex/agents/*.toml`; preserve existing
definitions and global rules when maintaining them. Writing a definition does
not prove that the current chat has hot-loaded it.

The lead agent maintains the shared event contract, actor mapping, task state,
and deployment manifest. Before parallel editing, assign each worker a baseline,
file scope, shared interface, and acceptance criteria. Workers must not edit the
same contract file independently or assume their workspaces are isolated.

Initial investigation may run in parallel; runtime execution is serial. If
subagents are unavailable, the lead chat performs the same responsibilities in
sequence. Do not create separate development chats for the three virtual actors.

## Three actors and basic behavior

Start by inspecting the local version of:

`bin/Configurations/Greta/Basics/Greta - 2 characters - CereProc.xml`

This reference was verified remotely, but local TTS and resources still need
inspection. Copy it into a new configuration and create three independent
CharacterManager/input/Planner/Realizer/TTS/lip/audio/animation chains connected
to one Environment. Display all three characters in that shared scene.

Module instance IDs, connection targets, logical actor IDs, avatar resource
names, and scene object IDs have different meanings. Renaming labels is
insufficient. Adjust real 3D positions, orientations, and camera framing rather
than only moving nodes in the Modular editor.

First verify synthetic facilitator -> patient A -> patient B -> facilitator
speech. Only the intended actor should speak and move its mouth each turn;
adding an actor must not affect existing actors. Reuse available models and
voices; three new voices are not a prerequisite.

P1 currently uses four speech-only BML files in `bin/Examples/GMIT/`, each sent
through its BML File Reader directly to the corresponding Realizer. The original
FML route was observed to synthesize the same speech twice through the
intentions/signals paths. Do not switch back without verification. Keep Planners
available for later behavior extensions. The facilitator, patient-a, and
patient-b map to LENA, EDDY, and LAURA at center, left, and right respectively.
Preserve independent CharacterManagers and enabled blinking.

The global IniManager loads `./Greta.ini` from the runtime working directory.
The current graph does not need an additional Ini Manager UI node: its explicit
`setDefinition` previously triggered a `languageIniLoader` null reference.
Legacy registry discovery messages do not prove that a module was instantiated.
Check actual loaded modules and behavior when changing these paths.

Use Greta Timer for probe scheduling; do not mix it with Unix wall time. Each
utterance must produce one audio callback from its intended actor, while other
actors' mouths remain inactive. Record render-texture evidence, audio-buffer
state, and human visual/audible observations separately. Keep bounded waits and
failure exits; do not automatically replay an utterance.

Enable basic behavior in layers:

1. Speech, lip synchronization, blinking, and the existing natural idle pose.
2. Listener gaze toward the active speaker using real scene-target bindings.
3. Small nods, head movements, or beat gestures using existing FML/BML and each
   actor's own lexicon.

Text input does not guarantee suitable gestures. Use small, explicit,
configurable behavior rules while preserving the original text and GMIT
decisions. Do not add emotion-inference models or an LLM that freely generates
behavior. Each behavior channel must be independently switchable so failures
can be isolated.

## Reusing the real GMIT simulation

Inspect the local `src/gmit/env/group_env.py`, `src/gmit/env/round_robin.py`,
`src/gmit/env/factory.py`, and `regenerate_round_robin.py`.

Do not treat the `services/gmit_service` human_text/human_role flow as a complete
automatic two-patient simulation. It serves human evaluation; use the full
simulation environment for this integration.

Record the floor mode explicitly:

- The legacy base environment emits the facilitator followed by one alternating
  patient.
- `FacilitatorABRoundRobinWrapper` provides an evaluation F -> A -> B mode with
  a fixed patient-turn budget, a final facilitator turn, and its own semantics.

Reuse an explicitly selected existing configuration. If a new demo has no
specified order, explicitly select the existing round-robin evaluation wrapper.
Preserve legacy behavior and document that this is not an equivalent replacement
for the training environment or reward. Do not rewrite the scheduler.

Preserve checkpoints, strict loading, sampled/greedy policy mode, context,
personas, stage estimation, and recurrent hidden/cell state. Do not reset policy
state every turn. If RL is absent or fails to load, do not silently fall back to
Pure LLM.

Existing scripts may iterate over multiple patient pairs. The integration runner
must explicitly select one pair and one short episode. A smoke test must not
accidentally start publication-scale batch evaluation or training.

## Output adaptation and execution

The minimum event contract includes `schema_version`, `session_id`, an independent
`event_id`, increasing `sequence`, `actor_id`, and original `text`. Preserve
`act`, `stage`, `source_turn`, and similar fields when available. Source turns
are not necessarily unique and must not serve directly as event IDs.

Structured rows accumulate during execution, but public `info` may expose them
only at termination. An external wrapper may read newly added rows after
reset/step. If it depends on `_da_rows` or another internal interface, pin the
compatible revision, validate fields, and document that dependency. Do not parse
Rich console output as a stable protocol.

Preserve the opening generated by reset. Queue all utterances from a step in
order, with bounded buffering and explicit advancement conditions. The first
integration is per-utterance/per-step, not token streaming. Error placeholder
text must not count as a successful simulated utterance.

Verify actor control with file readers first, then choose an existing local
transport. ActiveMQ FML/BML receivers are one option; each actor needs a correctly
bound receiver and an independent destination. Do not share one topic and assume
automatic actor routing, or use one queue that randomly distributes actors.

Verify the broker connector and protocol. Java's default `tcp://...:61616` is not
automatically a Python STOMP endpoint. Add only a minimal bridge if existing
mechanisms are unsuitable; do not begin by building a general service platform.

Distinguish received/accepted, scheduled, and observed playback. A normal ACK or
scheduled completion callback does not prove physical audio completion. Mark
unavailable information as unknown or estimated. Make failures, timeouts,
cancellation, and resends explicit; do not blindly repeat speech.

## Local resources

The target machine has a Core Ultra 7 165H, 32 GB RAM, and an RTX 3000 Ada Laptop
GPU with 8 GB VRAM. Inspect actual drivers, GPU utilization, memory, and installed
software before making performance claims.

Three actors do not require three copies of LLM weights. Reuse GMIT's configurable
model service and its existing serial call order. Each actor retains independent
state; sharing a service does not merge histories or personas.

Prefer the existing CPU sentence encoder and try explicitly configured CPU SAC
inference to leave GPU capacity for rendering or one model service. Do not promise
smooth concurrent LLM execution based solely on 8 GB VRAM; measure weights, KV
cache, execution overhead, and rendering together.

Start with an existing accessible backend. Treat a fully local model deployment
as a separate configuration acceptance task. If the model, quantization, context,
or output limit changes, label it an engineering test configuration rather than
an exact reproduction of the original experiment.

## Milestones and evidence

- P0: verify the three directories, branches, resources, launch method, GMIT
  entry point, and model/checkpoint dependencies. Check Greta assets, the GMIT
  SAC checkpoint, sentence encoder, and LLM service separately. Greta multimodal
  weights are not GMIT policy weights.
- P1: three visible actors in one scene, independent synthetic F -> A -> B -> F
  speech, and working basic lip synchronization and blinking.
- P2: verify gaze/head/gesture with real target bindings and replay authentic
  GMIT output, checking text, actors, and order.
- P3: drive all three actors with one bounded live GMIT episode while preserving
  the existing headless execution path.
- P4: validate local LLM execution, performance, and startup convenience as needed.

Advance within the user's authorized scope without requiring fresh approval at
every milestone. Report synthetic, authentic replay, and live execution
separately. Also distinguish static existence, completed configuration, process
startup, and human visual/audible observations. The eventual goal is live
simulation; fixed dialogue is an intermediate milestone.

Maintain a concise `docs/gmit/PROJECT.md` with the factual baseline, interfaces,
actor mapping, deployment relationships, acceptance evidence, and next step.
Reuse an equivalent existing record when available. Follow GMIT's continuity
rules when work involves it, subject to the user's current scope; do not put
private content in public documentation.

Run only key checks that address current risks: actor/content/order preservation,
duplicates and failures, actual loaded artifacts, and isolation of basic
behavior. Do not add excessive tests for simple configuration changes or perform
an unrelated repository-wide refactor.

## Commits and pushes

The user has authorized automatic publication of completed changes: after
implementation or documentation changes pass appropriate verification, create a
focused Conventional Commit and push the task branch without asking again.
Use English messages such as `feat(scope): ...`, `fix(scope): ...`,
`docs(scope): ...`, or `chore(scope): ...`. Newly created merge commits follow
the same convention. Do not manufacture changes for read-only questions.

Inspect the actual diff before committing, stage this task's files explicitly,
and preserve unrelated unfinished work. Do not commit credentials, private GMIT
data, or local runtime output. Do not force-push or rewrite published history.
After pushing, verify the remote SHA and local status. If authentication,
conflicts, or verification block publication, report completed steps and the
specific remaining issue. A later explicit instruction not to commit or push
takes precedence. Commit/push authorization does not expand runtime deployment,
training, or live LLM scope.

Report concrete changes, rationale, commands, verification evidence, and unresolved
items to the user in their requested language.
