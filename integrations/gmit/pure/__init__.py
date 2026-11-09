"""Standalone Pure-LLM group-session generation.

This package is a focused, MIT-derived migration of the Pure episode control
flow from the private GMIT project (Copyright (c) 2025 Alafate).  It contains
no private checkpoint, transcript, or few-shot example.  Production runs use
an explicit local few-shot asset, which is deliberately kept outside this
public package.
"""

from .generation import (
    DEFAULT_FEWSHOT_PATH,
    FIXED_OPENING_TEXT,
    REQUIRED_FEWSHOT_PAIR_COUNT,
    PureGenerationError,
    load_fewshot_asset,
    package_provenance,
)
from .episode import run_round_robin_episode
from .ollama import OllamaChatClient

__all__ = [
    "DEFAULT_FEWSHOT_PATH",
    "FIXED_OPENING_TEXT",
    "REQUIRED_FEWSHOT_PAIR_COUNT",
    "OllamaChatClient",
    "PureGenerationError",
    "load_fewshot_asset",
    "package_provenance",
    "run_round_robin_episode",
]
