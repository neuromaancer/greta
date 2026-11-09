# SPDX-License-Identifier: MIT
# Ported from GMIT src/gmit/agents/therapist_nlg.py; see LICENSE and generation.PORT_PROVENANCE.
from __future__ import annotations

import os
import pathlib
from typing import Dict, List, Optional

from .taxonomy import TherapistAct
from .protocols import LLMRouter
from .text import strip_markdown_fences, trim_to_complete_utterance

MIN_WORDS, MAX_WORDS = 3, 32
ALIGNMENT_ENV_VAR = "GMIT_THERAPIST_ALIGNMENT_MODE"


def _alignment_prompt_block(alignment_mode: str) -> str:
    mode = (alignment_mode or "none").strip().lower()

    if mode == "none":
        return ""

    if mode == "lexical":
        return (
            "\n## Lexical Alignment\n"
            "In most responses, reuse one short word or phrase from the immediately previous speaker's utterance.\n"
            "Use only 1-5 words as a light anchor.\n"
            "Do not copy a full sentence.\n"
            "Do not copy more than 5 consecutive words.\n"
            'Do not say "you said".\n'
            "Do not make the reused phrase sound like endorsement.\n"
            "Keep the required therapist act as the priority.\n"
            "Skip this only when the previous utterance is empty, unclear, or when lexical reuse would sound unnatural.\n"
        )

    raise ValueError(
        f"Unsupported alignment_mode={alignment_mode!r}; expected 'none' or 'lexical'."
    )


def _group_collaboration_prompt(
    theme: Optional[str], strong_group_coherence_enabled: bool
) -> str:
    topic = (theme or "the session topic").strip() or "the session topic"
    prompt = (
        "\n## Group Coherence\n"
        f"Keep both participants connected to the same behavior-change topic: {topic}.\n"
        "Invite them to notice overlaps, support each other, or build on each other's ideas when natural.\n"
    )
    if strong_group_coherence_enabled:
        prompt += (
            "## Strong Shared Objective Mode\n"
            f"Treat Patient A and Patient B as sharing one concrete change objective: {topic}.\n"
            "Patient A is the primary human participant. Patient B is peer context, not the main target of the facilitator response.\n"
            "Do not frame them as having two separate problems. They may have different barriers, but the goal is shared.\n"
            "When Patient B just spoke after Patient A, connect Patient B's contribution back to Patient A's latest concrete point.\n"
            "When natural, ask them to compare barriers, respond to each other's ideas, or choose one shared next step.\n"
            "Use language like 'both of you', 'shared goal', 'does that show up for you too', or 'what might help you both' when it fits the required act.\n"
        )
    return prompt


def _resolve_alignment_mode(alignment_mode: str) -> str:
    mode = (alignment_mode or "none").strip().lower()
    if mode == "none":
        env_mode = os.getenv(ALIGNMENT_ENV_VAR, "").strip()
        if env_mode:
            mode = env_mode.lower()
    _alignment_prompt_block(mode)
    return mode


def build_therapist_messages(
    context: str,
    act: TherapistAct,
    theme: Optional[str] = None,
    prompt_dir: Optional[pathlib.Path] = None,
    use_examples: bool = True,
    prev_acts: Optional[List[str]] = None,
    alignment_mode: str = "none",
    group_coherence_enabled: bool = True,
    strong_group_coherence_enabled: bool = False,
) -> List[Dict[str, str]]:
    catalog = "\n".join(
        [
            f"- {act.full_name}: {act.description}"
            for act in TherapistAct
            if act != TherapistAct.NO_ACT
        ]
    )
    system_prompt = ""
    example_prompt = ""
    if prompt_dir:
        # 1. Load main system prompt
        system_prompt_file = prompt_dir / "therapist" / "therapist_system_prompt.txt"
        if system_prompt_file.exists():
            with open(system_prompt_file, "r", encoding="utf-8") as file:
                system_prompt = file.read()

        if use_examples:
            short_name = act.short_name
            if short_name:
                example_file_path = prompt_dir / "therapist" / f"{short_name}.txt"
                if example_file_path.exists():
                    try:
                        with open(example_file_path, "r", encoding="utf-8") as file:
                            examples = file.read().strip()
                            if examples:
                                # Use .full_name for the prompt
                                example_prompt = (
                                    f"## Examples for {act.full_name}:\n{examples}\n"
                                    "(These examples show the FUNCTION of this act. "
                                    "Do NOT copy their wording or sentence openers. Use your own words.)\n"
                                )
                    except Exception as e:
                        print(
                            f"[therapist_nlg] WARNING: Could not read example file {example_file_path}: {e}"
                        )
    if not system_prompt:
        system_prompt = (
            "You are a therapist utterance generator for a group motivational interviewing session."
        )
    alignment_prompt = _alignment_prompt_block(alignment_mode)
    group_prompt = (
        _group_collaboration_prompt(theme, strong_group_coherence_enabled)
        if group_coherence_enabled
        else ""
    )

    system = (
        f"{system_prompt}\n"
        f"Theme: {theme or 'General health'}.\n"
        f"Allowed acts (for reference):\n{catalog}\n\n"
        f"{example_prompt}"
        "Speak in oral style with brief, plain sentences and empathy. Keep it "
        "low-pressure: use 1-2 short sentences, never a paragraph.\n"
        "There are exactly two group members. Never output 'Patient A' or 'Patient B'. "
        "Use 'Participant A' or 'Participant B' only when clarity requires it; "
        "otherwise speak naturally to the group or to the person who just spoke.\n"
        f"Your required act now: {act.value}.\n"
        "Two-sentence coherence contract: Sentence 1: ground your response in Participant A's latest utterance; "
        "acknowledge, answer, or briefly paraphrase what A actually said without inventing motivation, readiness, "
        "emotion, agreement, or commitment. Sentence 2: realize the required dialogue act in a natural way. "
        "Local coherence overrides act realization. If the required act does not fit the latest utterance, "
        "adapt the act minimally or ask a grounding question instead of forcing it.\n"
        "RESPONSE RULES: RESPOND to what the patient just said. "
        "NEVER start with 'Did you know'. Vary your sentence openers every turn. "
        "When advice would help, give one brief, concrete, low-pressure suggestion or menu of options tied to the latest barrier. "
        "You may include a relevant practical fact if it helps the participant decide. "
        "Ask at most one question, and keep advice collaborative rather than prescriptive. "
        "Do not say 'frustrating' unless a participant explicitly used frustration language. "
        "Do not mention wrapping up, closing, pausing, or final questions unless the context says this is the final facilitator message. "
        "Never output placeholder text such as '...' or an empty response. "
        f"{group_prompt}"
        f"{alignment_prompt}"
        f"Hard limits: one utterance, {MIN_WORDS}–{MAX_WORDS} words; no JSON; no meta commentary."
    )
    # Build previous act warning if available
    prev_act_hint = ""
    if prev_acts:
        acts_str = " → ".join(prev_acts)
        repetitions = [a for a in prev_acts if a == act.full_name]
        if repetitions:
            prev_act_hint = (
                f"\n⚠ COHERENCE REMINDER: Your last {len(prev_acts)} acts were [{acts_str}]. "
                f"You are being asked to do '{act.full_name}' again. "
                f"You MUST vary your phrasing completely — different sentence structure, different opener, different angle."
            )
        else:
            prev_act_hint = (
                f"\n⚠ COHERENCE REMINDER: Your last {len(prev_acts)} acts were [{acts_str}]. "
                f"Your new act is '{act.full_name}'. Make sure your utterance flows naturally from the previous exchange."
            )

    user = (
        f"<<< Context >>>\n{context}\n"
        f"{prev_act_hint}\n"
        f">>> Respond now with a single {act.full_name} utterance."
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def generate_therapist_utterance(
    llm: LLMRouter,
    context: str,
    act: TherapistAct,
    theme: Optional[str],
    prompt_dir: Optional[pathlib.Path] = None,
    use_examples: bool = True,
    prev_acts: Optional[List[str]] = None,
    alignment_mode: str = "none",
    group_coherence_enabled: bool = True,
    strong_group_coherence_enabled: bool = False,
) -> str:
    if act == TherapistAct.NO_ACT:
        return " "  # Returns a space to represent silence/floor-yielding
    # Safety check: Ensure prompt_dir is a Path object if it was passed as a string
    if prompt_dir and not isinstance(prompt_dir, pathlib.Path):
        prompt_dir = pathlib.Path(prompt_dir)
    alignment_mode = _resolve_alignment_mode(alignment_mode)
    msgs = build_therapist_messages(
        context=context,
        act=act,
        theme=theme,
        prompt_dir=prompt_dir,
        use_examples=use_examples,
        prev_acts=prev_acts,
        alignment_mode=alignment_mode,
        group_coherence_enabled=group_coherence_enabled,
        strong_group_coherence_enabled=strong_group_coherence_enabled,
    )
    raw = llm.chat(
        msgs,
        temperature=0.7,
        repetition_penalty=1.15,
        frequency_penalty=0.3,
        presence_penalty=0.3,
    )
    text = strip_markdown_fences(raw).strip()
    return trim_to_complete_utterance(text, MAX_WORDS)
