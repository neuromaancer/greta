# SPDX-License-Identifier: MIT
"""MIT port of the private GMIT default Pure benchmark control path.

Only the silent strict round-robin route is supported here.  RL, DREAM, CLI,
CSV metrics, Rich rendering, and private asset loading stay outside this module.
"""

from __future__ import annotations

import json
import pathlib
from collections import Counter
from typing import Any, Dict, List, Mapping, Optional, Sequence

from .patient import Patient
from .protocols import LLMRouter
from .taxonomy import THERAPIST_ACTS, PatientStyle, PatientType, TherapistAct
from .therapist_nlg import generate_therapist_utterance


class _SilentConsole:
    """Avoid a Rich dependency; production uses the port's silent mode."""

    def print(self, *_args: object, **_kwargs: object) -> None:
        return None

    def rule(self, *_args: object, **_kwargs: object) -> None:
        return None


console = _SilentConsole()


def validate_round_robin_rows(rows: Sequence[Mapping[str, Any]], *, expected_patient_turns: int | None = None) -> dict[str, int]:
    if not rows:
        raise ValueError("round-robin episode has no rows")
    speakers = [str(row.get("speaker", "")) for row in rows]
    expected_cycle = ("Therapist", "Patient A", "Patient B")
    for index, speaker in enumerate(speakers):
        expected = expected_cycle[index % len(expected_cycle)]
        if speaker != expected:
            raise ValueError(f"invalid round-robin floor order at row {index}: expected {expected!r}, got {speaker!r}")
    if speakers[-1] != "Therapist":
        raise ValueError("round-robin episode must end with a Therapist turn")
    counts = Counter(speakers)
    if counts["Patient A"] != counts["Patient B"]:
        raise ValueError("Patient A and Patient B must have equal turn counts")
    if counts["Therapist"] != counts["Patient A"] + 1:
        raise ValueError("a complete episode must have one extra Therapist turn")
    patient_turns = counts["Patient A"] + counts["Patient B"]
    if expected_patient_turns is not None and patient_turns != expected_patient_turns:
        raise ValueError(f"expected {expected_patient_turns} patient turns, got {patient_turns}")
    return dict(counts)


def _render_turn(*_args: object, **_kwargs: object) -> None:
    # The adapter calls this port with silent=True; public replay owns rendering.
    return None

_ACT_CATALOG = "\n".join(
    f"- {act.full_name}: {act.description}"
    for act in THERAPIST_ACTS
    if act != TherapistAct.NO_ACT
)


_SELECTABLE_THERAPIST_ACT_NAMES = [
    act.full_name for act in THERAPIST_ACTS if act != TherapistAct.NO_ACT
]


_THERAPIST_ACT_RESPONSE_FORMAT = {
    "type": "json_schema",
    "json_schema": {
        "name": "therapist_act_selection",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "act": {
                    "type": "string",
                    "enum": _SELECTABLE_THERAPIST_ACT_NAMES,
                }
            },
            "required": ["act"],
            "additionalProperties": False,
        },
    },
}


_THERAPIST_ACT_SYSTEM = f"""You are a Group Motivational Interviewing (GMI) therapist.
Your spirit is a Partnership, built on Acceptance, Compassion, and Evocation.

## YOUR PERSONA
- You are choosing the NEXT therapist dialogue act, not writing the full response.
- Group: The group is small: only "Patient A" and "Patient B".
- Focus: Focus on positives, solutions, and the future more than on problems or the past.

## AVAILABLE DIALOGUE ACTS
{_ACT_CATALOG}

## INSTRUCTIONS
1. Read the conversation context.
2. Choose the SINGLE most appropriate dialogue act from the list above.
3. GOAL: Elicit "Change Talk" (reasons/desire to change) and reduce "Sustain Talk" (reasons to stay the same).
4. Prefer acts that move the session forward without becoming formulaic.
5. If the session is just starting, select "Greeting or Closing".

You MUST reply with ONLY a JSON object:
{{
  "act": "<exact act name>"
}}
"""


def _select_therapist_act(
    llm: LLMRouter,
    context: str,
    theme: str,
    turn_idx: int = 0,
    fail_on_error: bool = False,
) -> TherapistAct:
    """
    Choose the next therapist act, leaving surface realization to therapist_nlg.
    """
    sys_prompt = _THERAPIST_ACT_SYSTEM
    if turn_idx > 15:
        sys_prompt += (
            "\n\n[LATE STAGE WARNING]: The session is advanced. "
            "Favor brief closing-oriented or planning-oriented acts when appropriate."
        )

    msgs = [
        {"role": "system", "content": sys_prompt},
        {
            "role": "user",
            "content": f"Theme: {theme}\nContext (Last 10 turns):\n{context}\n\nNext therapist act (JSON):",
        },
    ]

    last_error: Optional[Exception] = None
    for attempt in range(3):
        try:
            chat_kwargs = {}
            if getattr(llm, "provider", "").lower() == "vllm":
                chat_kwargs["response_format"] = _THERAPIST_ACT_RESPONSE_FORMAT
            raw = llm.chat(msgs, temperature=0.4, **chat_kwargs)
            return _parse_therapist_act(raw)

        except Exception as e:
            last_error = e
            if attempt == 2:
                console.print(f"[yellow]Therapist act selection failed: {e}[/yellow]")

    if fail_on_error:
        raise RuntimeError(
            "therapist act selection failed after 3 attempts"
        ) from last_error
    return TherapistAct.BACKCHANNEL


def _parse_therapist_act(raw: str) -> TherapistAct:
    """Extract the first recognized therapist act from an LLM response."""
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("empty therapist act response")

    decoder = json.JSONDecoder()
    selectable_acts = [act for act in THERAPIST_ACTS if act != TherapistAct.NO_ACT]
    parsed_objects = 0
    for start, char in enumerate(raw):
        if char != "{":
            continue
        try:
            obj, _ = decoder.raw_decode(raw, start)
        except json.JSONDecodeError:
            continue
        if not isinstance(obj, dict):
            continue

        parsed_objects += 1
        act_name = obj.get("act")
        if not isinstance(act_name, str) or not act_name.strip():
            continue
        normalized = act_name.strip().lower()

        for act in selectable_acts:
            if act.full_name.lower() == normalized:
                return act

        fuzzy_matches = [
            act
            for act in selectable_acts
            if normalized in act.full_name.lower()
            or act.full_name.lower() in normalized
        ]
        if len(fuzzy_matches) == 1:
            return fuzzy_matches[0]

    if not parsed_objects:
        lowered = raw.lower()
        text_matches = [
            act for act in selectable_acts if act.full_name.lower() in lowered
        ]
        if len(text_matches) == 1:
            return text_matches[0]

    if parsed_objects:
        raise ValueError("no recognized therapist act in JSON response")
    raise ValueError("no unique therapist act in non-JSON response")


GMI_STAGES = ["Engaging", "Focusing", "Evoking", "Planning", "Termination"]


_STAGE_SYSTEM = """You are a GMI stage classifier. Based on the conversation context,
identify the current GMI stage. Reply with ONLY one of: Engaging, Focusing, Evoking, Planning, Termination."""


def _estimate_stage(llm: LLMRouter, context: str) -> str:
    """Simple LLM-based GMI stage estimation."""
    msgs = [
        {"role": "system", "content": _STAGE_SYSTEM},
        {"role": "user", "content": f"Conversation:\n{context}\n\nCurrent stage:"},
    ]
    try:
        raw = llm.chat(msgs, temperature=0.2).strip()
        for stage in GMI_STAGES:
            if stage.lower() in raw.lower():
                return stage
    except Exception:
        pass
    return "Engaging"


def run_round_robin_episode(
    episode_id: int,
    theme: str,
    turns: int,
    patient_a_type: PatientType,
    patient_b_type: PatientType,
    patient_style: PatientStyle,
    llm_patients: LLMRouter,
    llm_therapist: LLMRouter,
    llm_stage: Optional[LLMRouter],
    prompt_dir: Optional[pathlib.Path],
    patient_fewshot: List[Dict[str, str]],
    use_readiness: bool = False,
    open_persona_version: str = "v1",
    silent: bool = False,
) -> List[Dict]:
    """Run a pure-LLM episode with a strict Therapist/A/B floor.

    ``turns`` is the total patient-utterance budget. A value of 30 yields 15
    A/B cycles and 46 rows: the fixed opening, 15 A/B pairs, and one therapist
    response after every pair (including the final response).
    """
    if turns <= 0 or turns % 2:
        raise ValueError("strict round-robin turns must be a positive even integer")

    patient_a = Patient(
        name="Patient A",
        patient_type=patient_a_type,
        llm=llm_patients,
        style=patient_style,
        use_readiness=use_readiness,
        open_persona_version=open_persona_version,
    )
    patient_b = Patient(
        name="Patient B",
        patient_type=patient_b_type,
        llm=llm_patients,
        style=patient_style,
        use_readiness=use_readiness,
        open_persona_version=open_persona_version,
    )

    context: List[str] = []
    da_rows: List[Dict] = []
    recent_t_acts: List[str] = []

    if not silent:
        console.print()
        console.rule(
            f"[bold cyan]Episode {episode_id}: "
            f"{patient_a_type.name} + {patient_b_type.name}[/bold cyan]"
        )

    def append_therapist(turn: int, *, opening: bool = False) -> TherapistAct:
        full_ctx = "\n".join(context[-10:])
        if opening:
            act = TherapistAct.GREET_CLOSE
            text = "Welcome everyone. Let's begin by hearing what's on your mind."
        else:
            patient_turns_elapsed = turn * 2
            act = _select_therapist_act(
                llm_therapist,
                full_ctx,
                theme,
                turn_idx=patient_turns_elapsed,
                fail_on_error=True,
            )
            text = generate_therapist_utterance(
                llm=llm_therapist,
                context=full_ctx,
                act=act,
                theme=theme,
                prompt_dir=prompt_dir,
                prev_acts=recent_t_acts[-3:],
            )

        context.append(f"Therapist ({act.full_name}): {text}")
        recent_t_acts.append(act.full_name)
        stage = (
            _estimate_stage(llm_stage, "\n".join(context[-30:]))
            if llm_stage
            else "Unknown"
        )
        if not silent:
            _render_turn(
                "Therapist", act.full_name, text, turn, stage, is_therapist=True
            )
        da_rows.append(
            {
                "episode": episode_id,
                "turn": turn,
                "speaker": "Therapist",
                "act": act.full_name,
                "text": text,
                "stage": stage,
                "patient_a_type": patient_a_type.name,
                "patient_b_type": patient_b_type.name,
            }
        )
        return act

    def append_patient(
        patient: Patient,
        patient_name: str,
        patient_turn: int,
        cycle: int,
        active_act: TherapistAct,
    ) -> None:
        patient.update_readiness(
            active_act.full_name,
            recent_therapist_acts=recent_t_acts[-10:],
        )
        try:
            output = patient.next(
                context="\n".join(context[-30:]),
                n_turn=patient_turn,
                total_turns=turns,
                theme=theme,
                fewshot_pairs=patient_fewshot,
                recent_therapist_acts=recent_t_acts[-10:],
            )
            category = output.get("category", "Backchannel")
            text = output.get("text", "...")
        except Exception as exc:
            console.print(f"[red]{patient_name} LLM error: {exc}[/red]")
            category = "Backchannel"
            text = "..."

        context.append(f"{patient_name} ({category}): {text}")
        stage = (
            _estimate_stage(llm_stage, "\n".join(context[-30:]))
            if llm_stage
            else "Unknown"
        )
        if not silent:
            _render_turn(patient_name, category, text, cycle, stage)
        da_rows.append(
            {
                "episode": episode_id,
                "turn": cycle,
                "speaker": patient_name,
                "act": category,
                "text": text,
                "stage": stage,
                "patient_a_type": patient_a_type.name,
                "patient_b_type": patient_b_type.name,
            }
        )

    active_act = append_therapist(0, opening=True)
    for cycle in range(turns // 2):
        append_patient(patient_a, "Patient A", cycle * 2, cycle, active_act)
        append_patient(patient_b, "Patient B", cycle * 2 + 1, cycle, active_act)
        active_act = append_therapist(cycle + 1)

    validate_round_robin_rows(da_rows, expected_patient_turns=turns)
    return da_rows
