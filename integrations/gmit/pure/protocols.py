"""Minimal local typing surface for the migrated Pure path."""

from __future__ import annotations

from typing import Protocol


class LLMRouter(Protocol):
    """Compatibility surface used by the Pure prompt/episode modules."""

    provider: str

    def chat(self, messages: list[dict[str, str]], temperature: float = 0.7, **kwargs: object) -> str:
        """Return one complete model response."""
