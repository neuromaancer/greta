# src/gmit/utils/text.py
# SPDX-License-Identifier: MIT
# Ported from GMIT src/gmit/utils/text.py; see LICENSE and generation.PORT_PROVENANCE.
from __future__ import annotations

import re
from typing import Optional, Tuple

# ---------- Base helpers ----------

_CODE_FENCE_RE = re.compile(r"^```.*?$|```$", flags=re.MULTILINE)


def strip_markdown_fences(text: str) -> str:
    s = _CODE_FENCE_RE.sub("", text or "")
    return s.strip()


def too_similar(a: str, b: str, jaccard_threshold: float = 0.8) -> bool:
    """
    Very lightweight similarity check by Jaccard on lowercased word sets.
    """
    wa = set(re.findall(r"\b\w+\b", (a or "").lower()))
    wb = set(re.findall(r"\b\w+\b", (b or "").lower()))
    if not wa or not wb:
        return False
    j = len(wa & wb) / max(1, len(wa | wb))
    return j >= jaccard_threshold


# ---------- Length control (optional; not required if you prefer prompt-only) ----------

_SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")
_SENT_END_RE = re.compile(r"[.!?][\"')\]]*$")
_DANGLING_FINAL_WORDS = {
    "and",
    "but",
    "or",
    "so",
    "because",
    "since",
    "when",
    "while",
    "although",
    "though",
    "if",
    "that",
    "to",
    "for",
    "with",
    "about",
    "before",
    "after",
    "without",
}


def shorten_conversational(
    text: str,
    max_sentences: int = 2,
    max_words: int = 25,
    strip_markdown: bool = True,
) -> str:
    """
    Keep up to `max_sentences` and `max_words` total; remove code fences; trim politely.
    """
    if not text:
        return text
    s = text
    if strip_markdown:
        s = strip_markdown_fences(s)

    # remove leading JSON-ish garbage if the model echoed keys
    s = re.sub(r"^[^{]*", "", s).strip()

    # split sentences, keep first N
    parts = _SENT_SPLIT.split(s)
    if len(parts) > max_sentences:
        parts = parts[:max_sentences]
    s = " ".join(p.strip() for p in parts if p.strip())

    # word cap
    words = re.findall(r"\S+", s)
    if len(words) > max_words:
        s = " ".join(words[:max_words]).rstrip(",;:") + "…"
    return s


def trim_to_complete_utterance(text: str, max_words: int) -> str:
    """Cap words without leaving a visibly unfinished sentence fragment."""
    s = re.sub(r"\s+", " ", str(text or "").strip())
    if not s:
        return s
    words = s.split()
    if len(words) <= max_words:
        return s

    capped_words = words[:max_words]
    capped = " ".join(capped_words).rstrip(",;:")
    sentence_matches = list(re.finditer(r"[.!?][\"')\]]*", capped))
    if sentence_matches:
        complete = capped[: sentence_matches[-1].end()].strip()
        if complete:
            return complete

    while capped_words and capped_words[-1].strip(".,;:!?").lower() in _DANGLING_FINAL_WORDS:
        capped_words.pop()
    capped = " ".join(capped_words).rstrip(",;:")
    if not capped:
        return ""
    if not _SENT_END_RE.search(capped):
        capped += "."
    return capped


# ---------- text normalization + JSON sanitization ----------

_TRANSLATION_TABLE = str.maketrans(
    {
        "“": '"',
        "”": '"',
        "„": '"',
        "«": '"',
        "»": '"',
        "‘": "'",
        "’": "'",
        "‚": "'",
        "‛": "'",
        "—": "-",
        "–": "-",
        "-": "-",  # unify dashes/hyphens
        "\u00a0": " ",  # NBSP
        "\u200b": "",  # zero width space
    }
)


def normalize_quotes(s: str) -> str:
    return (s or "").translate(_TRANSLATION_TABLE)


def normalize_text(s: str) -> str:
    """
    General-purpose normalizer used elsewhere in the codebase:
        - strip code fences
        - normalize curly quotes/dashes to ASCII
        - collapse excessive whitespace
    """
    if not s:
        return s
    s = strip_markdown_fences(s)
    s = normalize_quotes(s)
    # collapse horizontal whitespace runs; keep newlines but trim them
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    s = re.sub(r"\s*\n\s*", "\n", s)
    return s.strip()


def _find_first_braced_span(s: str) -> Optional[Tuple[int, int]]:
    """
    Find the first top-level {...} span using a simple brace stack.
    This avoids greedy regexes and copes with extra text around the JSON.
    """
    depth = 0
    start = None
    for i, ch in enumerate(s):
        if ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}":
            if depth > 0:
                depth -= 1
                if depth == 0 and start is not None:
                    return (start, i + 1)
    return None


# Patterns to canonicalize keys and remove bold/italics around them (inside JSON only)
_KEY_CANONICALIZERS = [
    # Remove ** or __ immediately around quoted keys
    (re.compile(r'\*\*\s*(?="Category"\s*:)', flags=re.I), ""),
    (re.compile(r'(?<="Category")\s*\*\*', flags=re.I), ""),
    (re.compile(r'\*\*\s*(?="Patient\'s Utterance"\s*:)', flags=re.I), ""),
    (re.compile(r'(?<="Patient\'s Utterance")\s*\*\*', flags=re.I), ""),
    (re.compile(r'__\s*(?="Category"\s*:)', flags=re.I), ""),
    (re.compile(r'(?<="Category")\s*__', flags=re.I), ""),
    (re.compile(r'__\s*(?="Patient\'s Utterance"\s*:)', flags=re.I), ""),
    (re.compile(r'(?<="Patient\'s Utterance")\s*__', flags=re.I), ""),
    # Canonicalize possibly unquoted / variably spaced keys (after quote normalization)
    (re.compile(r'["]?\s*Category\s*["]?\s*:', flags=re.I), '"Category":'),
    (
        re.compile(r'["]?\s*Patient\'s\s+Utterance\s*["]?\s*:', flags=re.I),
        '"Patient\'s Utterance":',
    ),
]

_TRAILING_COMMA_OBJ = re.compile(r",\s*}")
_TRAILING_COMMA_ARR = re.compile(r",\s*\]")


def _fix_trailing_commas(s: str) -> str:
    s = _TRAILING_COMMA_OBJ.sub("}", s)
    s = _TRAILING_COMMA_ARR.sub("]", s)
    return s


def sanitize_model_json_text(raw: str) -> str:
    """
    Best-effort cleaner that:
      - strips code fences/backticks around JSON
      - normalizes quotes/dashes
      - extracts the first {...} block
      - removes Markdown emphasis ONLY around the JSON keys
      - canonicalizes the two keys
      - fixes trailing commas
    Returns the cleaned JSON *string* (not parsed). If no braces are found,
    returns a minimally cleaned original string.
    """
    if not raw:
        return raw

    # 1) outer clean
    s = strip_markdown_fences(raw)
    s = normalize_quotes(s)

    # 2) find a {...} JSON-looking span
    span = _find_first_braced_span(s)
    if not span:
        return s.strip()

    start, end = span
    frag = s[start:end]

    # 3) remove code ticks inside fragment (rare but safe)
    frag = frag.replace("```", "").replace("`", "")

    # 4) remove **/__ around keys and canonicalize key tokens
    for pat, repl in _KEY_CANONICALIZERS:
        frag = pat.sub(repl, frag)

    # 5) compact whitespace (but do not touch spaces inside quoted strings aggressively)
    frag = re.sub(r"[ \t\r\f\v]+", " ", frag)
    frag = re.sub(r"\s*\n\s*", " ", frag).strip()

    # 6) fix trailing commas that break JSON
    frag = _fix_trailing_commas(frag)

    return frag


# ---------- inline Markdown stripping for utterance values ----------

# Simple non-greedy emphasis removers (safe for short conversational text)
_MD_PATTERNS = [
    (re.compile(r"\*\*(.+?)\*\*"), r"\1"),  # **bold**
    (re.compile(r"\*(.+?)\*"), r"\1"),  # *italic*
    (re.compile(r"__(.+?)__"), r"\1"),  # __bold__
    (re.compile(r"_(.+?)_"), r"\1"),  # _italic_
    (re.compile(r"`+(.+?)`+"), r"\1"),  # `code` or ```code```
]


def strip_inline_markdown(s: str) -> str:
    if not s:
        return s
    out = s
    for pat, repl in _MD_PATTERNS:
        out = pat.sub(repl, out)
    # collapse double spaces introduced by removals
    out = re.sub(r"\s{2,}", " ", out)
    return out.strip()


def clean_utterance_text(s: str) -> str:
    """
    Clean ONLY the patient's utterance value:
      - normalize curly quotes/dashes
      - drop inline markdown (*…*, **…**, _…_, backticks)
      - trim whitespace
    """
    if not s:
        return s
    s = normalize_quotes(s)
    s = strip_inline_markdown(s)
    # avoid space before punctuation introduced by normalization
    s = re.sub(r"\s+([,.!?;:])", r"\1", s)
    return s.strip()
