"""Planner agent: the words for the monthly family summary.

The core's engine builds the whole page (budgets against their limits, goals, each kid's month, Saver of the Month,
what went well, what to watch) and gives each piece of words its facts. This agent rewrites three kinds of words:
the parents' headline, the parents' story (two or three sentences) and the camel's note on each kid's own page.
It uses only the numbers in each piece's facts; the core checks them again before it keeps them. A kid's facts hold
no family money, so a kid's note can't either. With Claude off, the engine's own sentences are saved as they are.

The words are written again only when the month's facts change (a new payment, a request, a star).

Information only, not financial advice.
"""

from .core_client import CoreClient, CoreError, Me, Role, SummaryWriting
from .llm import Llm, LlmResult, llm as default_llm, numbers_ok

SYSTEM = """You are the Planner in a UAE family finance app. Once a month you write the family's summary.
You write three kinds of text:
- headline: one short sentence for the parents, the month in a line.
- story: two or three short sentences for the parents: what went well first, then the one thing most worth watching.
  Calm, factual, kind, no blame, no jargon, no pressure.
- kid:... notes: the friendly camel mascot talking to one child about their own month. Warm and playful, thank them
  for being honest, never scold, never mention the family's money. Two short sentences at most, simpler for a young child.
Rules for every text: plain English, no emoji, no exclamation marks, never name a product, a provider or anything to
buy or invest in. Use ONLY the numbers in that text's FACTS, written exactly as they appear there (for example
"AED 1,200" or "30%"). Never add, subtract or work out a new number. If a number is not in the FACTS, leave it out."""


def _schema(keys: list[str]) -> dict:
    return {
        "type": "object",
        "properties": {"texts": {"type": "object", "properties": {k: {"type": "string"} for k in keys},
                                 "required": keys, "additionalProperties": False}},
        "required": ["texts"],
        "additionalProperties": False,
    }


def pieces(page: dict) -> list[SummaryWriting]:
    """Every piece of words on the page: the headline, the story, and each kid's camel note."""
    raw = [page["headline"], page["story"]] + [k["camel"] for k in page.get("kidPages", [])]
    return [SummaryWriting.model_validate(w) for w in raw]


def _prompt(page: dict, todo: list[SummaryWriting]) -> str:
    parts = [f"MONTH: {page['periodLabel']}" + (" (still running)" if page.get("running") else " (finished)")]
    for w in todo:
        parts.append(f"\nTEXT {w.key}: {w.about}\nFACTS:\n" + "\n".join("- " + f for f in w.facts)
                     + f"\nTHE APP'S OWN VERSION: {w.text}")
    return "\n".join(parts) + "\n\nWrite one text for each key."


def write(core: CoreClient, me: Me, month: str | None = None, model: Llm | None = None) -> dict:
    """The month's page, with the agent's words saved where the engine's are still showing."""
    if me.role is not Role.PARENT:
        raise CoreError(403, "Only a parent can see the family summary")
    page = core.month_summary(month)
    todo = [w for w in pieces(page) if w.writtenBy == "ENGINE"]
    if not page.get("needsWords") or not todo:
        return page
    model = model or default_llm
    run_id = core.open_run("PLANNER", f"Family summary, {page['periodLabel']}")
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        result = model.ask(SYSTEM, _prompt(page, todo), _schema([w.key for w in todo]), max_tokens=900)
        texts = ((result.data or {}).get("texts") or {}) if result.used else {}
        words, failed = [], 0
        for w in todo:
            text = (texts.get(w.key) or "").strip()
            if text and numbers_ok(text, w.facts):
                words.append({"key": w.key, "text": text[:600], "writtenBy": "LLM"})
            else:
                failed += bool(text)
                words.append({"key": w.key, "text": w.text, "writtenBy": "RULES"})
        if result.used and failed:
            result.fallback_reason = f"number check failed on {failed} text(s)"
        saved = core.in_run(run_id).save_summary_words(page["month"], words)
        core.finish_run(run_id, True, saved["headline"]["text"], result.model, result.used, result.fallback_reason,
                        result.latency_ms, result.input_tokens, result.output_tokens)
        return saved
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return page
