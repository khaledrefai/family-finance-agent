"""The one place the agent service talks to Claude.

Rules for every LLM step:
  * Claude only writes words. Every number comes from the core API, and `numbers_ok` checks that each
    number in Claude's text appears in the facts we gave it. If one doesn't, we throw the text away.
  * Anything can fail (no key, timeout, refusal, bad JSON). Then `ask` returns None with a reason,
    and the caller uses its rule-based template instead. The demo never depends on the LLM.
  * Answers come back as JSON matching a schema (structured outputs), so we never parse free text.
"""

import json
import re
import time
from dataclasses import dataclass, field

from .settings import settings

_NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")


@dataclass
class LlmResult:
    """What happened, for the agent-run audit. `data` is None when the fallback must answer."""
    data: dict | None
    used: bool
    fallback_reason: str | None = None
    model: str | None = None
    latency_ms: int | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    extra: dict = field(default_factory=dict)


def enabled() -> bool:
    mode = settings.llm.lower()
    if mode == "off":
        return False
    if mode == "on":
        return True
    return bool(settings.anthropic_api_key)


def numbers_in(text: str) -> set[str]:
    """'AED 6,500 by 14 Nov' -> {'6500', '14'}"""
    out = set()
    for n in _NUMBER.findall(text):
        n = n.replace(",", "")
        if "." in n:
            n = n.rstrip("0").rstrip(".")
        whole, _, frac = n.partition(".")
        whole = whole.lstrip("0") or "0"  # "06 Nov" and "6 Nov" are the same number
        out.add(whole + ("." + frac if frac else ""))
    return out


def numbers_ok(text: str, facts: list[str]) -> bool:
    """True when every number in `text` also appears in one of the facts (so Claude did no maths)."""
    allowed: set[str] = set()
    for f in facts:
        allowed |= numbers_in(f)
    return numbers_in(text) <= allowed


class Llm:
    """Thin wrapper around the Anthropic SDK. Tests pass a fake `client`."""

    def __init__(self, client=None):
        self._client = client

    def _get_client(self):
        if self._client is None:
            import anthropic  # imported here so the service starts without the package when the LLM is off
            self._client = anthropic.Anthropic(api_key=settings.anthropic_api_key, timeout=settings.llm_timeout_s,
                                               max_retries=0)
        return self._client

    def ask(self, system: str, user: str, schema: dict, max_tokens: int = 1200) -> LlmResult:
        if not enabled():
            return LlmResult(None, used=False, fallback_reason="LLM off")
        started = time.monotonic()
        try:
            response = self._get_client().beta.messages.create(
                model=settings.llm_model,
                max_tokens=max_tokens,
                system=system,
                messages=[{"role": "user", "content": user}],
                output_config={"effort": "low", "format": {"type": "json_schema", "schema": schema}},
                # If the model declines, the API retries on Anthropic's recommended fallback model in the same call
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )
        except Exception as e:  # timeout, network, bad key, rate limit: the template answers instead
            return LlmResult(None, used=False, fallback_reason=f"LLM error: {type(e).__name__}"[:120],
                             model=settings.llm_model, latency_ms=_ms(started))
        usage = getattr(response, "usage", None)
        result = LlmResult(None, used=False, model=getattr(response, "model", settings.llm_model),
                           latency_ms=_ms(started),
                           input_tokens=getattr(usage, "input_tokens", None),
                           output_tokens=getattr(usage, "output_tokens", None))
        if response.stop_reason == "refusal":
            result.fallback_reason = "LLM refused"
            return result
        if response.stop_reason == "max_tokens":
            result.fallback_reason = "LLM answer cut off"
            return result
        text = "".join(b.text for b in response.content if getattr(b, "type", None) == "text")
        try:
            result.data = json.loads(text)
        except ValueError:
            result.fallback_reason = "LLM answer was not JSON"
            return result
        result.used = True
        return result


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


llm = Llm()
