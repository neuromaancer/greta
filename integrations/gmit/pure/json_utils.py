# src/gmit/utils/json_utils.py
# SPDX-License-Identifier: MIT
# Ported from GMIT src/gmit/utils/json_utils.py; see LICENSE and generation.PORT_PROVENANCE.
import json
import re
from typing import Any, Dict, Optional, Tuple


def extract_first_json_obj(text: str) -> Optional[Dict[str, Any]]:
    """
    Robustly extracts the first valid JSON object from a string,
    ignoring extra text before or after (including multiple concatenated JSONs).
    """
    if not text:
        return None

    # 1. Fast path: The whole text is valid JSON
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # 2. Heuristic: Find the first '{'
    start_idx = text.find("{")
    if start_idx == -1:
        return None

    # 3. Stack-based parser to find the matching closing '}'
    bracket_count = 0
    in_string = False
    escape = False

    for i in range(start_idx, len(text)):
        char = text[i]

        if in_string:
            if char == "\\" and not escape:
                escape = True
            elif char == '"' and not escape:
                in_string = False
            else:
                escape = False
        else:
            if char == '"':
                in_string = True
            elif char == "{":
                bracket_count += 1
            elif char == "}":
                bracket_count -= 1

                # Found the closing bracket for the outer object
                if bracket_count == 0:
                    candidate = text[start_idx : i + 1]
                    try:
                        return json.loads(candidate)
                    except json.JSONDecodeError:
                        pass

    # 4. Fallback: Try regex (non-greedy) if stack failed
    try:
        match = re.search(r"(\{.*?\})", text, re.DOTALL)
        if match:
            return json.loads(match.group(1))
    except Exception:
        pass

    return None


def read_category_and_text(obj: Dict[str, Any]) -> Tuple[str, str]:
    """Helper to extract standard fields from the JSON."""
    cat = obj.get("Category") or obj.get("category") or obj.get("Act") or "UNSPECIFIED"

    txt = (
        obj.get("Patient's Utterance")
        or obj.get("patient_utterance")
        or obj.get("Utterance")
        or obj.get("text")
        or ""
    )

    return str(cat), str(txt)
