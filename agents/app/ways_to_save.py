"""Planner agent: the words for this month's "Ways to save" card.

The core's engine picks up to 3 tips from the family's recurring costs (subscriptions, outings over budget, rent,
phone and internet) and gives each one its facts. Once a month this agent rewrites each tip in a warmer sentence,
using only the numbers in that tip's facts, and saves the words in the core. The core checks the numbers again
before it keeps them. With Claude off, the engine's own sentences are saved as they are, so the card is written
once a month either way. A parent dismisses or snoozes a tip in the app; the agent never can.

Information only, not financial advice.
"""

from .core_client import CoreClient, CoreError, Me, Role, WaysToSave
from .llm import Llm, LlmResult, llm as default_llm, numbers_ok

SYSTEM = """You are the Planner in a UAE family finance app. Each month you write a few gentle tips on ways to save.
For each tip, write one or two short sentences for a busy parent. Warm, plain English, no pressure, no jargon.
Never name a product, a provider to switch to, or anything to buy or invest in.
Use ONLY the numbers in that tip's FACTS, written exactly as they appear there (for example "AED 1,520").
Never add, subtract or work out any new number. If a number is not in the tip's FACTS, leave it out."""


def _schema(keys: list[str]) -> dict:
    return {
        "type": "object",
        "properties": {"tips": {"type": "object", "properties": {k: {"type": "string"} for k in keys},
                                "required": keys, "additionalProperties": False}},
        "required": ["tips"],
        "additionalProperties": False,
    }


def _prompt(card: WaysToSave) -> str:
    parts = [f"MONTH: {card.monthLabel}"]
    for t in card.tips:
        parts.append(f"\nTIP {t.key} ({t.title})\nFACTS:\n" + "\n".join("- " + f for f in t.facts))
    return "\n".join(parts) + "\n\nWrite one short text for each tip key."


def write(core: CoreClient, me: Me, model: Llm | None = None) -> WaysToSave:
    """This month's card, with the agent's words saved the first time it is asked for."""
    if me.role is not Role.PARENT:
        raise CoreError(403, "Only a parent sees ways to save")
    card = core.ways_to_save()
    if not card.needsWords or not card.tips:
        return card
    model = model or default_llm
    run_id = core.open_run("PLANNER", f"Ways to save, {card.monthLabel}")
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        result = model.ask(SYSTEM, _prompt(card), _schema([t.key for t in card.tips]), max_tokens=700)
        texts = ((result.data or {}).get("tips") or {}) if result.used else {}
        words, failed = [], 0
        for t in card.tips:
            text = (texts.get(t.key) or "").strip()
            if text and numbers_ok(text, t.facts + [t.title]):
                words.append({"key": t.key, "text": text[:400], "writtenBy": "LLM"})
            else:
                failed += bool(text)
                words.append({"key": t.key, "text": t.text, "writtenBy": "RULES"})
        if result.used and failed:
            result.fallback_reason = f"number check failed on {failed} tip(s)"
        saved = core.in_run(run_id).save_tip_words(words)
        core.finish_run(run_id, True, " | ".join(t.text for t in saved.tips), result.model, result.used,
                        result.fallback_reason, result.latency_ms, result.input_tokens, result.output_tokens)
        return saved
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return card
