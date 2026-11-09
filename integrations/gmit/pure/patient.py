# SPDX-License-Identifier: MIT
# Ported from GMIT src/gmit/agents/patient.py; see LICENSE and generation.PORT_PROVENANCE.
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Dict, List, Optional

from .taxonomy import (
    PATIENT_INTENT_DEFS,
    PatientAct,
    PatientStyle,
    PatientType,
    normalize_patient_category,
)
from .json_utils import extract_first_json_obj, read_category_and_text
from .protocols import LLMRouter
from .text import too_similar, trim_to_complete_utterance


MAX_PATIENT_WORDS = 28

_READINESS_DEFAULTS: Dict[PatientType, float] = {
    PatientType.OPEN: 0.65,
    PatientType.RESISTANT: 0.20,
    PatientType.RECEPTIVE: 0.40,
}

_READINESS_INCREASES = {
    "Reflection",
    "Empathic Reaction",
    "Experience Normalization and Reassurance",
    "Progress Acknowledgment and Encouragement",
    "Planning with the Patient",
}
_READINESS_DECREASES = {"Give Solution", "Invite to Shift Outlook"}
_REACTANCE_STREAK_ACTS = {
    "Reflection",
    "Invite to Shift Outlook",
    "Give Solution",
    "Empathic Reaction",
    "Ask for Information",
    "Progress Acknowledgment and Encouragement",
    "Planning with the Patient",
}


def _trailing_streak(recent_therapist_acts: Optional[List[str]], act_name: str) -> int:
    """Count the length of the current trailing streak for a therapist act."""
    if not recent_therapist_acts:
        return 0

    streak = 0
    for act in reversed(recent_therapist_acts):
        if act != act_name:
            break
        streak += 1
    return streak


def _reactance_streak_act(recent_therapist_acts: Optional[List[str]]) -> Optional[str]:
    """Return the repeated act name when a reactance-triggering streak is present."""
    if not recent_therapist_acts or len(recent_therapist_acts) < 3:
        return None

    last3 = recent_therapist_acts[-3:]
    if len(set(last3)) != 1:
        return None

    repeated_act = last3[0]
    if repeated_act in _REACTANCE_STREAK_ACTS:
        return repeated_act
    return None


def _trim_to_word_limit(text: str, max_words: int = MAX_PATIENT_WORDS) -> str:
    return trim_to_complete_utterance(text, max_words)


def _resolve_patient_acts(allowed_acts: Optional[List[str]]) -> List[PatientAct]:
    """Resolve configured patient act strings into canonical taxonomy values."""
    if not allowed_acts:
        return list(PatientAct)

    resolved: List[PatientAct] = []
    for act in allowed_acts:
        if isinstance(act, PatientAct):
            resolved.append(act)
            continue
        try:
            resolved.append(PatientAct(act))
        except Exception:
            resolved.append(normalize_patient_category(str(act)))
    return resolved


def _format_patient_catalog(acts: List[PatientAct]) -> str:
    lines = []
    for act in acts:
        definition = PATIENT_INTENT_DEFS.get(act, "")
        lines.append(f"- {act.value}: {definition}")
    return "\n".join(lines)


def _format_dream_catalog(acts: List[PatientAct]) -> str:
    """Render DREAM-style category bullets, extended with the 3 group acts."""
    dream_defs = {
        PatientAct.PERSONAL_INFO: (
            "The patient describes a past event (could describe a past unhealthy behavior) "
            "or give some personal or medical information."
        ),
        PatientAct.CHANGE_FUTURE: (
            "The patient talks about the future and shows an intention or action taken "
            "towards actively changing one of his behavior in the future."
        ),
        PatientAct.SUSTAIN_FUTURE: (
            "The patient talks about the future and shows an intention or action taken "
            "towards actively sustaining one of his behavior in the future or denying one "
            "of his behavior."
        ),
        PatientAct.NEG_FEELING: (
            "The patient describes a negative feeling he has, or the patient describes and "
            "explicitly acknowledge a specific negative emotion he is feeling."
        ),
        PatientAct.POS_FEELING: (
            "The patient describes a positive feeling he has, or the patient describes and "
            "explicitly acknowledge a specific positive emotion he is feeling."
        ),
        PatientAct.NEW_PERSPECT: (
            "The patient expresses that he learned or understood something about himself "
            "or about his situation or the patient take a new perspective on the situation"
        ),
        PatientAct.GREET_CLOSE: (
            "The patient initiates or concludes the conversation with a greeting or closing statement."
        ),
        PatientAct.BACKCHANNEL: (
            "The patient acknowledges that they've heard the therapist's words by interjecting "
            "with a brief response or acknowledgment or repeating exactly what the therapist said."
        ),
        PatientAct.ASK_MED_INFO: (
            "The patient asks for the therapist for medical or therapeutic information"
        ),
        PatientAct.PATIENT_NORMALIZE: (
            "The patient normalizes another patient's experience or reassures them by saying "
            "that their struggle is understandable or shared."
        ),
        PatientAct.PATIENT_ENCOURAGE: (
            "The patient acknowledges another patient's effort or progress and offers encouragement."
        ),
        PatientAct.PATIENT_EMPATHY: (
            "The patient explicitly expresses empathy or compassion toward another patient's "
            "feelings or situation."
        ),
    }
    return "\n".join(f"- {act.value}: {dream_defs[act]}" for act in acts)


def _dream_theme_prefix(theme: Optional[str]) -> str:
    if theme == "Smoking":
        return "Your are here to talk aboutyour smoking habbits.\n"
    if theme == "Drinking":
        return "Your are here to talk aboutyour drinking habbits\n"
    if theme == "Exercise":
        return "Your are here to talk aboutyour exercising habbits\n"
    if theme:
        return f"You are here to talk about {theme}.\n"
    return "You are here to talk about general well-being.\n"


def _group_coherence_prompt(theme: Optional[str], strong: bool = False) -> str:
    topic = (theme or "the session topic").strip() or "the session topic"
    prompt = (
        "## Group Coherence and Mutual Support\n"
        f"Patient A and Patient B must discuss the same behavior-change topic: {topic}.\n"
        "They must have similar, compatible, or shared goals. Do NOT introduce an unrelated goal or a separate problem.\n"
        "If the other patient has spoken, respond to their last statement before adding your own experience: "
        "acknowledge their difficulty, relate to it, encourage them, or build on their idea.\n"
        "Share similar experiences and encouragement when natural. If you mention an idea, frame it as what you tried or might try yourself, not as advice for the other patient.\n"
        "Avoid two independent monologues. The dialogue should show mutual support and topic coherence between Patient A and Patient B.\n"
    )
    if strong:
        prompt += (
            "## Strong Shared Objective Mode\n"
            f"Patient A and Patient B share the same concrete behavior-change objective: {topic}.\n"
            "You may have different barriers, but do not create a separate objective for yourself.\n"
            "If you are Patient B and Patient A has spoken anywhere in the recent context, connect naturally to one concrete detail from Patient A before adding your own experience.\n"
            "Do not start by formally naming or summarizing Patient A's point. Let the link appear through your own daily routine, similar barrier, or encouragement. If you mention an idea, frame it as what you tried or might try yourself, not as advice for Patient A.\n"
            "Do not imply Patient A and Patient B live together, cook together, shop together, eat together, or share the same schedule unless the visible dialogue says so.\n"
            "Address the facilitator and the group; do not only speak to Patient A, and do not ignore Patient A.\n"
        )
    return prompt


@dataclass
class Patient:
    name: str  # e.g., "Patient A"
    patient_type: PatientType  # e.g., PatientType.OPEN
    llm: LLMRouter
    style: PatientStyle = PatientStyle.GMIT
    allowed_acts: Optional[List[str]] = None
    readiness_score: float = 0.5  # initialized properly in __post_init__
    use_readiness: bool = False  # When False, skip readiness/reactance prompt injection
    open_persona_version: str = "v1"
    group_coherence_enabled: bool = True
    strong_group_coherence_enabled: bool = False

    def __post_init__(self):
        self._recent_texts: List[str] = []
        self.readiness_score = _READINESS_DEFAULTS.get(self.patient_type, 0.45)
        self.open_persona_version = (self.open_persona_version or "v1").strip().lower()
        if self.open_persona_version not in {"v1", "v2"}:
            raise ValueError(
                f"Unsupported open_persona_version={self.open_persona_version!r}; expected 'v1' or 'v2'."
            )

    def update_readiness(
        self,
        therapist_act_name: str,
        recent_therapist_acts: Optional[List[str]] = None,
    ) -> None:
        """Adjust readiness based on the therapist act that just occurred."""
        if not self.use_readiness:
            return
        if self.style == PatientStyle.DREAM:
            return

        current_streak = _trailing_streak(recent_therapist_acts, therapist_act_name)

        # Helpful acts can improve readiness, but repeated use quickly feels circular.
        if therapist_act_name in _REACTANCE_STREAK_ACTS and current_streak >= 3:
            delta = -0.06
        elif therapist_act_name in _REACTANCE_STREAK_ACTS and current_streak == 2:
            delta = 0.0
        elif therapist_act_name in _READINESS_INCREASES:
            delta = +0.06
        elif therapist_act_name in _READINESS_DECREASES:
            delta = -0.04
        else:
            delta = 0.0
        self.readiness_score = max(0.0, min(1.0, self.readiness_score + delta))

    def reset_readiness(self) -> None:
        """Reset readiness and recent text history (call at episode start)."""
        self.readiness_score = _READINESS_DEFAULTS.get(self.patient_type, 0.45)
        self._recent_texts.clear()

    def _gmit_patient_persona(
        self,
        patient_type: PatientType,
        n_turn: int = 0,
        total_turns: int = 10,
    ) -> str:
        """
        Returns a nuanced persona string.
        """
        base_instruction = (
            "You are a realistic therapy patient. Do NOT just repeat your stance on change. "
            "Share your feelings, personal stories, and struggles. "
            "Be conversational, not robotic."
        )

        if patient_type == PatientType.OPEN:
            if self.open_persona_version == "v2":
                return (
                    f"{base_instruction}\n"
                    "**Your Personality:** You are 'Open to Change'. You are willing to change your "
                    "behavior or way of thinking, and you are open to new ideas or suggestions. "
                    "Change still takes effort, so stay realistic, but do NOT default to resistance. "
                    "If a suggestion seems practical or personally meaningful, you may build on it rather "
                    "than immediately pushing it away. "
                    "**Key Behavior:** You can express NEW_PERSPECT and tentative CHANGE_FUTURE earlier "
                    "than other patient types. Small, concrete next steps are appropriate even before full "
                    "confidence, as long as they sound plausible for you."
                )
            return (
                f"{base_instruction}\n"
                "**Your Personality:** You are 'Open to Change' but change is genuinely hard for you. "
                "You want to quit (or change) but carry specific fears: "
                "stress without your coping habit, social pressure from friends, fear of failing again. "
                "You do NOT immediately agree to commit. You process advice critically — "
                "if you hear a suggestion, you first ask yourself 'can I actually do that?' "
                "before expressing any intention. "
                "**Key Behavior:** Feeling hopeful (POS_FEELING) is NOT the same as being committed. "
                "Express ambivalence or a personal barrier before — and often instead of — any CHANGE_FUTURE. "
                "CHANGE_FUTURE is only appropriate after you have voiced a specific, realistic plan "
                "(what, when, how) that feels personally meaningful to you."
            )

        if patient_type == PatientType.RESISTANT:
            return (
                f"{base_instruction}\n"
                "**Your Personality:** You are 'Resistant to Change' but NOT hostile without cause. "
                "You value your autonomy and hate being told what to do. You believe your behavior helps you cope with stress. "
                "You feel annoyed when pushed, but you might open up if the therapist validates your feelings. "
                "**Key Behavior:** Defend your choices (Sustain Talk), but also explain WHY (Personal Info) and express annoyance (Negative Feeling) if pushed."
            )

        if patient_type == PatientType.RECEPTIVE:
            progress = n_turn / max(1, total_turns)
            if progress < 0.33:
                state = "Skeptical and hesitant. You are not sure if you belong here."
            elif progress < 0.66:
                state = "Curious but conflicted. You see the pros and cons of changing (Ambivalence)."
            else:
                state = "Cautiously optimistic. You are willing to try small steps."

            return (
                f"{base_instruction}\n"
                f"**Your Personality:** You are 'Receptive' (evolving). Current State: {state} "
                "You fluctuate between wanting to change and staying the same. "
                "**Key Behavior:** Ask questions, express doubt, and slowly warm up to the group."
            )

        return "You are a patient in a therapy session."

    def _dream_type_definition(
        self, patient_type: PatientType, n_turn: int, total_turns: int
    ) -> str:
        del total_turns  # DREAM used a fixed turn threshold in the type-conditioning path.
        if patient_type == PatientType.RESISTANT:
            return (
                "You are a patient resistant to change. You are not willing to change "
                "your behavior or your way of thinking. You are not open to new ideas "
                "or suggestions."
            )
        if patient_type == PatientType.OPEN:
            return (
                "You are a patient open to change. You are willing to change your "
                "behavior or your way of thinking. You are open to new ideas or suggestions. "
            )
        if patient_type == PatientType.RECEPTIVE:
            type_def = (
                "You are a patient receptive to therapy. You change your mind during "
                "the conversation. "
            )
            if int(n_turn) >= 20:
                return (
                    type_def
                    + "This is the end of the conversation. You are now more open to "
                    "change. You are willing to change your behavior or your way of "
                    "thinking. You are open to new ideas or suggestions."
                )
            return (
                type_def
                + "This is the beginning of the conversation. You are resistant to "
                "change. You are not willing to change your behavior or your way of "
                "thinking. You are not open to new ideas or suggestions."
            )
        return "You are a patient in therapy."

    def _build_messages_gmit(
        self,
        context: str,
        n_turn: int,
        total_turns: int,
        theme: Optional[str],
        fewshot_pairs: Optional[List[Dict[str, str]]] = None,
        recent_therapist_acts: Optional[List[str]] = None,
    ) -> List[Dict[str, str]]:
        persona = self._gmit_patient_persona(self.patient_type, n_turn, total_turns)

        readiness_note = ""
        reactance_signal = ""

        if self.use_readiness:
            r = int(self.readiness_score * 10)
            if r < 7:
                readiness_note = (
                    f"\n**Your current readiness to commit: {r}/10.** "
                    "You are NOT yet ready for a firm commitment. "
                    "Express ambivalence, personal barriers, or fears first.\n"
                )
            elif r < 9:
                readiness_note = (
                    f"\n**Your current readiness: {r}/10.** "
                    "You are cautiously warming up. A specific commitment is possible "
                    "only if you can name exactly what you will do and why it matters.\n"
                )
            else:
                readiness_note = (
                    f"\n**Your current readiness: {r}/10.** "
                    "You feel genuinely ready. A concrete CHANGE_FUTURE is appropriate "
                    "if it names a specific action, not just agreement.\n"
                )

            repeated_act = _reactance_streak_act(recent_therapist_acts)
            if repeated_act:
                if repeated_act == "Reflection":
                    reactance_signal = (
                        "\n**REACTANCE:** The therapist has used 'Reflection' 3 times in a row. "
                        "Real patients can start to feel stuck, mirrored, or like the conversation is not moving. "
                        "Lean toward skepticism, frustration, or clarifying what is still missing. "
                        "Do NOT become more cooperative just because the therapist keeps reflecting.\n"
                    )
                else:
                    reactance_signal = (
                        f"\n**REACTANCE:** The therapist has repeated '{repeated_act}' 3 times in a row. "
                        "Real patients feel talked-at when this happens. "
                        "Lean toward NEG_FEELING, SUSTAIN_FUTURE, or skepticism. "
                        "Do NOT respond positively to repetitive reframing or advice.\n"
                    )

        style_rules = (
            "## STRICT OUTPUT RULES ##\n"
            "1.  **SINGLE UTTERANCE:** You MUST respond with only ONE utterance.\n"
            f"2.  **LENGTH:** Keep it low-pressure: 1 short sentence preferred, 2 short sentences only if needed, {MAX_PATIENT_WORDS} words MAXIMUM.\n"
            "3.  **STYLE:** The dialogue is spoken. Use natural phrasing, short pauses, or optional tags [sighs]. Do not use em dashes or en dashes.\n"
            "4.  **FORMAT:** You MUST return a SINGLE, valid JSON object. Do NOT write any text or thoughts before or after the JSON. Your entire response MUST be the JSON object and nothing else."
        )

        peer_style_prompt = (
            "\n## Peer / Friend Style for Patient B ##\n"
            "When you are Patient B, sound like a peer or friend in the same conversation, not a second therapist or facilitator.\n"
            "Do not analyze, diagnose, or interpret Patient A's psychology. Do not quote Patient A verbatim.\n"
            "Do not start with formulaic frames like 'when you said', 'you mentioned', 'hearing you say', or 'I hear you'.\n"
            "Connect to one concrete detail naturally through your own daily routine, similar barrier, small encouragement, or practical shared idea.\n"
            "Speak mostly from first-person experience. If you offer an idea, make it sound like something you tried or might try, not something Patient A should do.\n"
            "Keep your life separate from Patient A's life. Do not imply shared meals, shared shopping, shared cooking, a shared household, or a shared schedule unless the dialogue explicitly says so.\n"
            "Do not output visible labels 'Patient A' or 'Patient B'. If you need to address Patient A, say A sparingly.\n"
            "If the latest human turn is only a greeting, very short, or unclear, answer briefly and gently return to the shared topic without inventing a barrier.\n"
        )

        repetition_rule = ""
        if self._recent_texts:
            last_k = self._recent_texts[-3:]
            joined = "; ".join(last_k)
            repetition_rule = (
                "\n5.  **REPETITION:** Do NOT repeat or paraphrase any of these recent phrases: "
                f"{joined}. Produce fresh wording and content."
            )

        acts = _resolve_patient_acts(self.allowed_acts)
        catalog = "\n".join(
            f'- "{act.value}": {PATIENT_INTENT_DEFS.get(act, "")}' for act in acts
        )

        coherence_prompt = (
            _group_coherence_prompt(theme, self.strong_group_coherence_enabled)
            if self.group_coherence_enabled
            else ""
        )

        system = (
            f"You are {self.name}, a patient in a small group motivational interviewing therapy session.\n"
            f"The group consists *only* of: Therapist, Patient A, and Patient B.\n"
            f"You MUST ONLY speak as {self.name}. Do NOT invent or speak for other patients (e.g., Patient C, Patient D, etc.).\n\n"
            f"## Your Persona:\n{persona}\n"
            f"{readiness_note}"
            f"{reactance_signal}\n"
            f"## Session Theme:\n{theme or 'General well-being'}\n"
            f"{coherence_prompt}\n"
            f"Turn {n_turn + 1} of {total_turns}.\n\n"
            f"## Allowed Acts (You MUST choose one category from this list):\n{catalog}\n\n"
            f"{style_rules}"
            f"{peer_style_prompt}"
            f"{repetition_rule}\n"
        )

        if fewshot_pairs:
            k = len(fewshot_pairs)
            ex_lines: List[str] = []
            for i in range(k):
                pair = fewshot_pairs[i]
                if isinstance(pair, dict):
                    ip = str(pair.get("input", "")).strip()
                    op = str(pair.get("output", "")).strip()
                elif isinstance(pair, (list, tuple)) and len(pair) >= 2:
                    ip = str(pair[0]).strip()
                    op = str(pair[1]).strip()
                else:
                    continue
                if ip and op:
                    ex_lines.append(
                        f"Example {i + 1} Input:\n{ip}\nExample {i + 1} Output:\n{op}"
                    )
            if ex_lines:
                system += "\nFew-shot formatting cues:\n" + "\n\n".join(ex_lines)

        user = (
            f"<<< Context : {context}\n"
            'Generate the patient\'s next utterance. YOU MUST RESPOND WITH ONLY A SINGLE JSON OBJECT like: {"Category": [category], "Patient\'s Utterance": [utterance]} >>>'
        )

        return [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]

    def _build_messages_dream(
        self,
        context: str,
        n_turn: int,
        total_turns: int,
        theme: Optional[str],
        fewshot_pairs: Optional[List[Dict[str, str]]] = None,
        recent_therapist_acts: Optional[List[str]] = None,
    ) -> List[Dict[str, str]]:
        del recent_therapist_acts  # DREAM baseline does not condition on therapist streaks.

        type_def = self._dream_type_definition(self.patient_type, n_turn, total_turns)
        acts = _resolve_patient_acts(self.allowed_acts)
        catalog = _format_dream_catalog(acts)
        coherence_prompt = (
            _group_coherence_prompt(theme, self.strong_group_coherence_enabled)
            if self.group_coherence_enabled
            else ""
        )

        system = f""" You are a patient talking with a therapist. Your task is to generate the patient's next utterance and respect the intent given from one of the following predefined categories:
{catalog}
You are of one of the following type, this type gives your personality:
- Resistant to change : You are not willing to change your behavior or your way of thinking. You are not open to new ideas or suggestions and are really difficult to convince.
- Open to change : You are willing to change your behavior or your way of thinking. You are open to new ideas or suggestions. You are easy to convince.
-Hesitant : You are a patient receptive to therapy, you change you mind during the conversation. You are in need to take a new perspective to change your mind.
 The dialogue is happening orally, use a oral style language with hesitation, repetition, and deviation.
You will only respond with one patient's utterance. Do not provide explanations or notes. Make only one proposition. Keep it low-pressure: 1 short sentence preferred, 2 short sentences only if needed, {MAX_PATIENT_WORDS} words maximum. Use [laughter] to signal a patient laughter, [laughs] to signal a patient laugh, [sighs] to signal a patient sigh, [gasps] to signal a patient gasps, [clears throat] to signal a patient clears throat, short pauses, or CAPITALIZATION for emphasis of a word. Do not use em dashes or en dashes. If the therapist does not respect basic dialog rules like greeting at the beginning of the conversation or answering questions then add [WRONG] at the beginning of your utterance.
This is a small group session with only Therapist, Patient A, and Patient B. You are {self.name} and must only speak as {self.name}. If the therapist explicitly leaves space, you may respond to the other patient's last utterance.
{coherence_prompt}
You MUST return a SINGLE valid JSON object and nothing else.
"""

        msgs: List[Dict[str, str]] = [{"role": "system", "content": system}]

        if fewshot_pairs:
            for pair in fewshot_pairs:
                if isinstance(pair, dict):
                    ip = str(pair.get("input", "")).strip()
                    op = str(pair.get("output", "")).strip()
                elif isinstance(pair, (list, tuple)) and len(pair) >= 2:
                    ip = str(pair[0]).strip()
                    op = str(pair[1]).strip()
                else:
                    continue
                if ip and op:
                    msgs.append({"role": "user", "content": ip})
                    msgs.append({"role": "assistant", "content": op})

        theme_prefix = _dream_theme_prefix(theme)
        user = (
            theme_prefix
            + " " + f"""
    Type : {type_def} This is your starting personality but you can change your mind if the therapist builds a good relationship with you and is convincing enough.
    <<< Context : {context}
    Generate the patient 's next utterance and its category in the form {{"Category": [category], "Patient's Utterance": [utterance]}}
    The context represent the full state of the dialogue.
    If the therapist does not respect basic dialogue rules such as beginning with a greeting or answering questions then add the tag [WRONG] at the begining of the patient utterance.
>>>"""
        )
        msgs.append({"role": "user", "content": user})
        return msgs

    def _build_messages(
        self,
        context: str,
        n_turn: int,
        total_turns: int,
        theme: Optional[str],
        fewshot_pairs: Optional[List[Dict[str, str]]] = None,
        recent_therapist_acts: Optional[List[str]] = None,
    ) -> List[Dict[str, str]]:
        if self.style == PatientStyle.DREAM:
            return self._build_messages_dream(
                context,
                n_turn,
                total_turns,
                theme,
                fewshot_pairs,
                recent_therapist_acts,
            )
        return self._build_messages_gmit(
            context,
            n_turn,
            total_turns,
            theme,
            fewshot_pairs,
            recent_therapist_acts,
        )

    def next(
        self,
        context: str,
        temperature: float = 0.7,
        n_turn: int = 0,
        total_turns: int = 10,
        theme: Optional[str] = None,
        fewshot_pairs: Optional[List[Dict[str, str]]] = None,
        requested: Optional[str] = None,
        allowed_dialogue_acts: Optional[List[str]] = None,
        recent_therapist_acts: Optional[List[str]] = None,
    ) -> Dict[str, str]:
        # 1. Base Message
        msgs = self._build_messages(
            context, n_turn, total_turns, theme, fewshot_pairs, recent_therapist_acts
        )
        original_system_prompt = msgs[0]["content"]

        attempt = 0
        max_retries = 5

        while attempt < max_retries:
            current_temp = (
                temperature if attempt == 0 else min(1.0, temperature + 0.1 * attempt)
            )
            # Escalate repetition penalty on retries
            current_rep_pen = 1.05 + (0.1 * attempt) if attempt > 0 else 1.05

            try:
                raw = self.llm.chat(
                    msgs, temperature=current_temp, repetition_penalty=current_rep_pen
                )
                obj = extract_first_json_obj(raw)

                if obj:
                    cat, txt = read_category_and_text(obj)
                    txt = _trim_to_word_limit(txt)
                    cat = normalize_patient_category(cat).value

                    # --- Repetition Check ---
                    if self._recent_texts and any(
                        too_similar(txt, prev) for prev in self._recent_texts[-5:]
                    ):
                        print(
                            f"[Patient] Repetition detected (attempt {attempt}). Retrying..."
                        )
                        avoid = "; ".join(self._recent_texts[-5:])
                        warning = (
                            f"**SYSTEM ERROR:** You just repeated a previous phrase. "
                            f"Do NOT use these phrases: {avoid}. Generate something new.\n\n"
                        )
                        msgs[0]["content"] = warning + original_system_prompt
                        attempt += 1
                        continue

                    self._recent_texts.append(txt)
                    return {"category": cat, "text": txt}

                else:
                    print(
                        f"[Patient] JSON Parse Failed (Attempt {attempt}). Retrying..."
                    )
                    warning = (
                        "**SYSTEM ERROR:** Your last response was not valid JSON. "
                        'You MUST return strictly one JSON object: {"Category": ..., "Patient\'s Utterance": ...}\n\n'
                    )
                    msgs[0]["content"] = warning + original_system_prompt
                    attempt += 1

            except Exception as e:
                print(f"[Patient] LLM Error (Attempt {attempt}): {e}. Retrying...")
                time.sleep(1)
                attempt += 1

        # --- FALLBACK (Break the loop) ---
        print("[Patient] Max retries reached. Returning fallback response.")
        return {"category": "Backchannel", "text": "..."}
