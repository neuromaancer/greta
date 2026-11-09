# SPDX-License-Identifier: MIT
# Ported from GMIT src/gmit/agents/taxonomy.py; see LICENSE and generation.PORT_PROVENANCE.
from __future__ import annotations

import re
from enum import Enum
from typing import Dict, List, Optional

# ===================== Patient Types (used by LLM prompt) =====================


class PatientType(str, Enum):
    OPEN = "Open-to-Change"
    RESISTANT = "Resistant-to-Change"
    RECEPTIVE = "Receptive"  # aka Hesitant / evolves during the session


class PatientStyle(str, Enum):
    GMIT = "gmit"
    DREAM = "dream"


# ===================== Patient dialogue acts (labels coming from LLM) =====================


class PatientAct(str, Enum):
    # Basic MI Acts
    PERSONAL_INFO = "Sharing personal information or Describe past event"
    CHANGE_FUTURE = "Changing unhealthy behavior in the future"
    SUSTAIN_FUTURE = "Sustaining unhealthy behavior in the future"
    NEG_FEELING = "Sharing negative feeling or emotion"
    POS_FEELING = "Sharing positive feeling or emotion"
    NEW_PERSPECT = "Understanding or New Perspective"
    GREET_CLOSE = "Greeting or Closing"
    BACKCHANNEL = "Backchannel"
    ASK_MED_INFO = "Asking for Medical Information"

    # Added Social/Collaborative Acts (from Therapist space)
    PATIENT_NORMALIZE = "Experience Normalization and Reassurance"
    PATIENT_ENCOURAGE = "Progress Acknowledgment and Encouragement"
    PATIENT_EMPATHY = "Empathic Reaction"


PATIENT_ACTS: List[PatientAct] = list(PatientAct)

PATIENT_INTENT_DEFS: Dict[PatientAct, str] = {
    # Basic MI
    PatientAct.PERSONAL_INFO: "Describes past events or shares personal/medical info.",
    PatientAct.CHANGE_FUTURE: (
        "Expresses a CONCRETE intention to change an unhealthy behavior. "
        "To qualify, the utterance MUST contain: (1) a specific action (not just 'I'll try'), "
        "(2) an implicit or explicit timeframe or context trigger, "
        "(3) acknowledgment that it will be difficult OR a personal reason that makes it meaningful. "
        "Hollow tokens like 'I'll give that a try', 'yeah I'll do that', 'let's do this' "
        "do NOT qualify — use POS_FEELING or NEW_PERSPECT instead."
    ),
    PatientAct.SUSTAIN_FUTURE: "Defends the status quo or intention to keep an unhealthy behavior.",
    PatientAct.NEG_FEELING: "States a negative emotion or distress.",
    PatientAct.POS_FEELING: "States a positive emotion or relief.",
    PatientAct.NEW_PERSPECT: "Shows new understanding or a perspective shift.",
    PatientAct.GREET_CLOSE: "Greets or closes the interaction.",
    PatientAct.BACKCHANNEL: "Brief acknowledgments (e.g., 'yeah', 'mm-hm').",
    PatientAct.ASK_MED_INFO: "Asks for medical/therapeutic information.",
    # Added Social/Collaborative
    PatientAct.PATIENT_NORMALIZE: "Normalizes another's experience; reassures by commonality.",
    PatientAct.PATIENT_ENCOURAGE: "Recognizes another's progress/effort and encourages them.",
    PatientAct.PATIENT_EMPATHY: "Expresses empathy explicitly toward another.",
}


def normalize_patient_category(raw: str) -> PatientAct:
    """
    Map noisy/free-form category strings to a canonical PatientAct.
    Tolerates case, punctuation, and minor wording differences.
    """
    if not raw:
        return PatientAct.PERSONAL_INFO

    # Exact match
    for act in PatientAct:
        if raw == act.value:
            return act

    x = raw.lower().strip()
    x = re.sub(r"[\*\_\-\~\`\"\']+", "", x)  # strip emphasis/fences
    x = re.sub(r"\s+", " ", x)

    if "change" in x and ("future" in x or "unhealthy" in x or "quit" in x):
        return PatientAct.CHANGE_FUTURE
    if any(w in x for w in ["sustain", "keep", "continue", "status quo"]):
        return PatientAct.SUSTAIN_FUTURE
    if any(w in x for w in ["negative", "anxiet", "sad", "frustrat", "dread", "angry"]):
        return PatientAct.NEG_FEELING
    if any(w in x for w in ["positive", "relief", "better", "hopeful", "glad"]):
        return PatientAct.POS_FEELING
    if any(
        w in x
        for w in ["understanding", "new perspective", "i see", "learned", "makes sense"]
    ):
        return PatientAct.NEW_PERSPECT

    # Check for new social acts
    if any(
        w in x
        for w in ["normalize", "reassure", "same for me", "i feel that too", "common"]
    ):
        return PatientAct.PATIENT_NORMALIZE
    if any(
        w in x
        for w in [
            "encourage",
            "good job",
            "you can do it",
            "great point",
            "acknowledge",
        ]
    ):
        return PatientAct.PATIENT_ENCOURAGE
    if any(
        w in x
        for w in [
            "empathy",
            "that sounds hard",
            "i understand",
            "sorry to hear",
            "empathic",
        ]
    ):
        return PatientAct.PATIENT_EMPATHY

    if "greet" in x or "closing" in x or x in {"hi", "hello", "bye", "goodbye"}:
        return PatientAct.GREET_CLOSE
    if "backchannel" in x or x in {"mm", "hmm", "yeah", "uh", "um", "ok", "okay"}:
        return PatientAct.BACKCHANNEL
    if any(w in x for w in ["medical", "explain", "what does", "info", "information"]):
        return PatientAct.ASK_MED_INFO
    if "personal" in x or "describe past" in x or "past event" in x:
        return PatientAct.PERSONAL_INFO

    # last-resort fuzzy contains of canonical labels
    for act in PatientAct:
        if act.value.lower() in x:
            return act
    return PatientAct.PERSONAL_INFO


def patient_act_to_valence(act: PatientAct) -> float:
    """
    Graded reward mapping for MI patient dialogue acts.
    Positive values indicate therapeutic progress; negative indicate resistance.
    """
    _VALENCE_MAP = {
        PatientAct.CHANGE_FUTURE: +1.0,
        PatientAct.NEW_PERSPECT: +0.5,
        PatientAct.POS_FEELING: +0.25,
        PatientAct.PATIENT_ENCOURAGE: +0.25,
        PatientAct.PATIENT_NORMALIZE: +0.15,
        PatientAct.PATIENT_EMPATHY: +0.15,
        PatientAct.PERSONAL_INFO: +0.1,
        PatientAct.BACKCHANNEL: 0.0,
        PatientAct.GREET_CLOSE: 0.0,
        PatientAct.ASK_MED_INFO: 0.0,
        PatientAct.NEG_FEELING: -0.1,
        PatientAct.SUSTAIN_FUTURE: -1.0,
    }
    return _VALENCE_MAP.get(act, 0.0)


# ===================== Therapist dialogue acts (your discrete action set) =====================


class TherapistAct(Enum):
    """
    Enum for therapist dialogue acts.
    The value is a tuple: (full_name, short_name_for_file, description)
    """

    # Enum Member          = (full_name, file_code, description)
    REFLECTION = (
        "Reflection",
        "r",
        "Conveys understanding without judging, interpreting, or advising.",
    )
    ASK_INFO = (
        "Ask for Information",
        "qi",
        "Seeks further details about events/statements/background.",
    )
    INVITE_SHIFT = (
        "Invite to Shift Outlook",
        "qper",
        "Offers a fresh outlook or invites imagining alternatives.",
    )
    ASK_EMOTION = (
        "Ask about current Emotions",
        "qe",
        "Asks to identify/rate current emotions.",
    )
    GIVE_SOLUTION = (
        "Give Solution",
        "ps",
        "Provides direct, concrete suggestions.",
    )
    PLAN_WITH_PATIENT = (
        "Planning with the Patient",
        "p",
        "Co-creates a specific action plan.",
    )
    NORMALIZE_REASSURE = (
        "Experience Normalization and Reassurance",
        "n",
        "Normalizes experience; reassures by commonality.",
    )
    EDUCATE_GUIDE = (
        "Medical Education and Guidance",
        "gt",
        "Provides psychological/medical information.",
    )
    GREET_CLOSE = (
        "Greeting or Closing",
        "g",
        "Opens or closes politely.",
    )
    BACKCHANNEL = (
        "Backchannel",
        "ack",
        "Brief acknowledgments (e.g., 'mm-hm').",
    )
    ASK_CONSENT = (
        "ask for consent or validation",
        "qp",
        "Requests permission/validation before proceeding.",
    )
    ACK_ENCOURAGE = (
        "Progress Acknowledgment and Encouragement",
        "pr",
        "Recognizes progress/effort and encourages.",
    )
    EMPATHIC_REACT = (
        "Empathic Reaction",
        "emp",
        "Expresses empathy explicitly.",
    )
    NO_ACT = (
        "NoAct",
        "no_act",
        "Therapist intentionally stays silent; patients speak to each other.",
    )

    @property
    def full_name(self) -> str:
        """The full, human-readable name (e.g., 'Reflection')"""
        return self.value[0]

    @property
    def short_name(self) -> Optional[str]:
        """The short code for filenames (e.g., 'r')"""
        return self.value[1]

    @property
    def description(self) -> str:
        """The prompt description (e.g., 'Conveys understanding...')"""
        return self.value[2]

    def __str__(self) -> str:
        """Makes str(TherapistAct.REFLECTION) return 'Reflection'"""
        return self.full_name


THERAPIST_ACTS: List[TherapistAct] = list(TherapistAct)
