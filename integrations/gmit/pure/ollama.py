"""Minimal native-Ollama chat client used by the standalone Pure runner."""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from typing import Any, Mapping


class OllamaClientError(RuntimeError):
    """Raised for an unavailable or malformed native Ollama response."""


class OllamaChatClient:
    """Use the local Ollama ``/api/chat`` endpoint without third-party imports."""

    provider = "ollama"

    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        base_options: Mapping[str, object],
        timeout_seconds: float,
        max_attempts: int = 6,
    ) -> None:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        if max_attempts <= 0:
            raise ValueError("max_attempts must be positive")
        self._url = base_url.rstrip("/") + "/api/chat"
        self._model = model
        self._base_options = dict(base_options)
        self._timeout_seconds = timeout_seconds
        self._max_attempts = max_attempts

    def chat(self, messages: list[dict[str, str]], *, temperature: float, **options: object) -> str:
        request_options = dict(self._base_options)
        request_options["temperature"] = temperature
        request_options.update(options)
        payload = {
            "model": self._model,
            "messages": messages,
            "stream": False,
            "options": request_options,
        }
        request = urllib.request.Request(
            self._url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        last_error: BaseException | None = None
        for attempt in range(self._max_attempts):
            try:
                with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                    raw = response.read()
                break
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as exc:
                last_error = exc
                if attempt + 1 == self._max_attempts:
                    raise OllamaClientError(f"native Ollama chat request failed after {self._max_attempts} attempts: {exc}") from exc
                # Match the source router's bounded transient retry intent while
                # retaining an explicit stdlib-only transport and per-call timeout.
                time.sleep(min(8.0, 0.8 * (2 ** attempt)))
        else:  # pragma: no cover - the loop either breaks or raises.
            raise OllamaClientError(f"native Ollama chat request failed: {last_error}")
        try:
            decoded = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise OllamaClientError("native Ollama chat response was not JSON") from exc
        content = decoded.get("message", {}).get("content") if isinstance(decoded, dict) else None
        if not isinstance(content, str) or not content.strip():
            raise OllamaClientError("native Ollama chat response has no message content")
        return content
