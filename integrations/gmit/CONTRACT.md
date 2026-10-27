# GMIT transcript replay contract

This adapter calls or reads the separately installed GMIT project. Private
prompts, checkpoints, generated dialogue, and imported transcripts stay outside
version control. Playback never calls an LLM and does not advance a simulation.

## Original events

UTF-8 JSONL contains one original utterance per row. Required fields are
`schema_version` (1), `session_id`, unique `event_id`, contiguous `sequence`
starting at 1, `actor_id`, and original `text`. Actors are `facilitator`,
`patient-a`, and `patient-b`; GMIT's Therapist/Patient A/Patient B map to them.
Preserve available `act`, `stage`, `source_turn`, and provenance metadata.
Source turn numbers can repeat and are not event identifiers.

Input order and text are authoritative. Reject unknown roles, empty text,
model-error placeholders, and ambiguous multi-session inputs. Never silently
select an episode, truncate dialogue, summarize speech, or infer missing acts.
Use explicit episode selection and `unknown` for unavailable annotations.

## Greta playback XML

Retain the existing `gmit-session` schema version 1 and `turn`/`text` shape.
The existing `synthetic-authored` / `scripted-f-a-b` fixture contract remains
unchanged. Imported playback uses:

- `provenance`: `gmit-generated` or `transcript-import`.
- `floor_mode`: `recorded`; preserve order instead of enforcing F-A-B cycles.
- Required root attributes: `schema_version`, `session_id`, `title`.
- Optional root `condition`: `pure`, `rl`, or `unknown`.
- Required turn attributes: `sequence`, `event_id`, `actor_id`, `stage`, `act`.
- Optional turn attributes: `source_event_id`, `source_turn`, `chunk_index`,
  `chunk_count`.
- One plain-text `text` child per turn; XML entities are escaped. DTDs and
  external entities are forbidden.

The renderer accepts 1–256 playback chunks, with unique event identifiers,
contiguous sequences, known actors, and 1–300 UTF-16 code units per text.
The importer splits long utterances conservatively at at most 160 Unicode
characters while also respecting that UTF-16 bound. Concatenating chunks for
an original event must recover its exact text, including boundary whitespace.
Chunks keep the same actor and original annotations. Their identifiers and
source-event links distinguish transport chunks from original utterances.

The existing 15-second synthesized-audio bound applies per chunk. An overlong
audio result fails explicitly; it is not truncated or automatically replayed.
Received, scheduled, and human-observed playback remain distinct evidence.

## Execution boundaries

Pure generation invokes the existing strict round-robin function for one
explicit patient pair and one bounded episode, then converts its returned rows.
This is authentic generation followed by replay, not token streaming or live
turn-by-turn control. Offline import needs neither a model service nor GMIT
Python dependencies. Receipts retain source/model/configuration identity and
output hashes locally; public documentation contains only evidence summaries.
