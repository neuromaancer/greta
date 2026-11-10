# Six controlled transcript drafts

The 2 x 3 pilot crosses facilitator task/social behavior with patient interaction
orientation. The current six transcripts are controlled synthetic stimuli
authored by the chat assistant and delegated authoring agents. They are not raw
local-Qwen outputs, participant data, or validated experimental results.

## Conditions

| ID | Facilitator | A | B |
| --- | --- | --- | --- |
| C1 | Task + social | Buddy | Buddy |
| C2 | Task + social | Buddy | Individualist |
| C3 | Task + social | Individualist | Individualist |
| C4 | Task only | Buddy | Buddy |
| C5 | Task only | Buddy | Individualist |
| C6 | Task only | Individualist | Individualist |

Both patients are OPEN to change. Buddy/individualist is a separate interaction
orientation: buddies connect to their peer's concrete contribution through
their own experience or support; individualists focus on their own barriers and
choices while remaining polite and willing to change. A task-only facilitator
does not disable patient buddy behavior. The mixed cell fixes A=buddy and
B=individualist; this is one draft per cell, not counterbalanced replication.

## User-supplied Table 5 split

| Figure label | Existing `TherapistAct` member | Group |
| --- | --- | --- |
| Ask for Consent | `ASK_CONSENT` | Task |
| Reflection | `REFLECTION` | Task |
| Ask for Information | `ASK_INFO` | Task |
| Ask about Emotion | `ASK_EMOTION` | Task |
| Invite to Shift Outlook | `INVITE_SHIFT` | Task |
| Medical Education and Guidance | `EDUCATE_GUIDE` | Task |
| Give Solution | `GIVE_SOLUTION` | Task |
| Planning with the participant | `PLAN_WITH_PATIENT` | Task |
| Empathic Reaction | `EMPATHIC_REACT` | Social/meta |
| Greeting / Closing | `GREET_CLOSE` | Social/meta |
| Backchannel | `BACKCHANNEL` | Social/meta |
| Normalization/Reassurance | `NORMALIZE_REASSURE` | Social/meta |
| Progress Acknowledgment/Encouragement | `ACK_ENCOURAGE` | Social/meta |
| NoAct | `NO_ACT` | Social/meta |

Task-only bodies use the eight task acts. Task+social drafts illustrate all six
social/meta acts as well as task acts. All share the unchanged fixed opening,
explicitly outside the manipulated body. Each task+social draft has one empty
NoAct facilitator position in cycle eight: it cedes the next floor and never
becomes a spoken placeholder. The final task+social act is Greeting/Closing;
task-only ends with a concluding Planning task summary instead of a social
closing act or a new question.

## Length and matched content

The inspected `gmit_eval` configuration sets `default_max_human_turns=15`,
counting human inputs rather than total messages. These drafts use one fixed
Greta facilitator opening followed by fifteen A-B-F cycles: 46 positions,
including fifteen contributions from each patient. The three task+social drafts
have 45 spoken positions plus one silence; the task-only drafts have 46 spoken
positions. This matches the numeric fifteen-round budget, not the evaluation
service's dynamic human-input floor or its treatment implementations.

The topic is reducing red meat. A starts with four beef meals on Monday through
Thursday, a ten-minute preparation budget, and independent Monday shopping.
A's chosen experiment replaces Tuesday/Thursday with bean-and-rice wraps,
keeps beef Monday/Wednesday, and uses a ready-made bean wrap as backup.
B starts with three beef burgers on Wednesday/Friday/Saturday and independent
Tuesday shopping. B keeps Saturday's beef burger with friends, replaces
Wednesday/Friday with a ready-cooked chicken burger in the familiar bun, and
uses a chicken takeaway order as backup. No shared household, cooking schedule,
or vegetarian requirement is introduced. These are fictional stimulus facts.

The common agenda covers habits, reasons, barriers, valued meal features,
alternatives, time, social meals, budget, option comparison, frequency, days,
obstacles, backup plans, confidence/review, and a concluding recap. Facilitator
rows are matched within each mode, and patient rows within each orientation
are matched across facilitator modes. They are authored
controls, not independent stochastic samples or causal evidence.

## Files, provenance, and verification limits

The six readable Markdown files, per-cell JSON, manifest and ZIP remain under
ignored `integrations/gmit/local/conditions-20261110-01/six-controlled-transcripts-v2/`.
Every file identifies assistant authorship and disclaims raw-Qwen provenance.
Chat-generation temperature and seed were not captured and are not invented.
The local packager verifies the 46-position floor, actor and act enums, task-only
restrictions, common opening, silent NoAct placement, terminal acts, and hashes.
Text review separately checks meaning, orientation, factual consistency and
coherence. User acceptance of these scripts is pending.

The earlier local Qwen 4-bit long-session attempts are retained separately as
rejected prompt diagnostics. Structural completion did not establish quality:
review found semantic loops, topic drift and unsupported narrative details.
The unpublished generator prototype is backed up locally; it is not released
as a successful six-condition generation path. The accepted normal Pure source,
its model configuration and fifteen-pair dependency remain unchanged. No
runtime deployment, GUI playback or six-video export was performed for these
drafts. Structural NoAct rows need an explicit playback timing contract before
being imported as animated sessions.
