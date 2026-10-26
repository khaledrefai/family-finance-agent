"""Planner agent: cheaper family outings that fit the entertainment budget (week 7).

A parent says what the family would like ("something free this weekend", "indoor ideas for next month, the kids
love animals"). This agent only reads that into a typed ask: which month, what kinds of outing, and (with Claude on)
which outings on the core's list fit those wishes best. The core does everything else (POST /api/outings/plan): it
works out each outing's cost for this family from adult and child tickets, checks the pick against what is left of
the entertainment budget, leaves out outings that are closed that month or don't fit, and writes the facts.
This agent then says the core's facts. It never prices, adds up or compares anything itself.

Claude picks from the core's own list only; a key that isn't on it is ignored by the core. With Claude off or
unsure, the rule-based reader below picks the kinds and the core's engine picks the outings.
"""

import re

from .core_client import CoreClient, CoreError, Me, Role
from .llm import Llm, LlmResult, llm as default_llm

KINDS = ["FREE", "INDOOR", "OUTDOOR", "ANIMALS", "NATURE", "BEACH", "CULTURE", "PLAY"]

SYSTEM = """You help a UAE family plan cheaper outings in Dubai. You get the parent's words and a list of outings,
each with a key, what kind it is, a line about it and whether it is open that month. Pick up to 4 outings from the
list that best match what the parent asked for (one a weekend), keeping in mind the family wants to spend less.
Only use keys from the list. Never invent a place. Do not add up or compare any prices: the app checks whether
the pick fits the budget. Also say which month the parent means: THIS_MONTH (this weekend, this week, this month),
NEXT_MONTH, or ANY if they didn't say. If the words are not about outings, set understood to false."""

SCHEMA = {
    "type": "object",
    "properties": {
        "understood": {"type": "boolean"},
        "when": {"type": "string", "enum": ["THIS_MONTH", "NEXT_MONTH", "ANY"]},
        "keys": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["understood", "when", "keys"],
    "additionalProperties": False,
}

_OUTING_WORDS = re.compile(
    r"\b(outings?|day out|days out|things to do|what to do|where to go|go out|going out|family fun|"
    r"weekend plans?|fun for the (kids|family)|cheaper (places|fun|entertainment)|entertainment ideas|"
    r"(plan|cheaper|free) (activities|weekends?))\b", re.I)
_THIS = re.compile(r"\b(this (weekend|week|month|saturday|sunday|friday)|today|tomorrow|tonight)\b", re.I)
_NEXT = re.compile(r"\bnext (month|weekend)s?\b", re.I)
_KIND_WORDS = {
    "FREE": r"\b(free|no money|costs? nothing|without spending)\b",
    "INDOOR": r"\b(indoors?|inside|air[- ]?con\w*|too hot|hot day)\b",
    "OUTDOOR": r"\b(outdoors?|outside|fresh air)\b",
    "ANIMALS": r"\b(animals?|zoo|safari|birds?)\b",
    "NATURE": r"\b(nature|gardens?|flowers?|desert|lakes?|parks?|picnic)\b",
    "BEACH": r"\b(beach|sea|swim\w*)\b",
    "CULTURE": r"\b(museums?|history|heritage|culture|learn\w*)\b",
    "PLAY": r"\b(playgrounds?|play|run around)\b",
}


def is_outings_question(message: str) -> bool:
    return bool(_OUTING_WORDS.search(message))


def read_when(message: str) -> str | None:
    if _NEXT.search(message):
        return "NEXT_MONTH"
    if _THIS.search(message):
        return "THIS_MONTH"
    return None


def read_kinds(message: str) -> list[str]:
    return [k for k, pattern in _KIND_WORDS.items() if re.search(pattern, message, re.I)]


def _prompt(message: str, board: dict, when: str) -> str:
    month = board["thisMonth"] if when == "THIS_MONTH" else board["nextMonth"]
    lines = [f"PARENT SAID: {message}", f"MONTH: {month}", "OUTINGS:"]
    for o in board["options"]:
        open_ = o["openThisMonth"] if when == "THIS_MONTH" else o["openNextMonth"]
        lines.append(f"- {o['key']}: {o['name']} ({', '.join(o['kinds']).lower() or 'any'}"
                     f"{', free' if o['cost']['minor'] == 0 else ''}){'' if open_ else ' CLOSED that month'}. {o['about']}")
    return "\n".join(lines)


def reply_for(plan: dict, also_free: dict | None = None) -> str:
    """The core's facts about the plan, in its order. No numbers of our own."""
    facts = plan.get("facts") or []
    picks = plan.get("picks") or []
    names = [p["name"] for p in picks]
    if not names:
        return " ".join(facts[:3])
    head = f"For {plan['monthLabel']}: " + (", ".join(names[:-1]) + " and " + names[-1] if len(names) > 1 else names[0]) + "."
    keep = [f for f in facts if f.startswith(("Together:", "All ", "Nothing is left", "Left out", "Only ", "None of", "Spent on entertainment"))
            or "usual outing" in f and "would cost" in f]
    reply = " ".join([head] + keep)
    if also_free and also_free.get("picks"):
        free = [p["name"] for p in also_free["picks"]]
        reply += (f" For the rest of {also_free['monthLabel']}, free ones: "
                  + (", ".join(free[:-1]) + " and " + free[-1] if len(free) > 1 else free[0]) + ".")
    reply += " Prices are from our saved list, not live: check each one on its site before booking."
    return reply


def plan(core: CoreClient, me: Me, message: str, model: Llm | None = None,
         when: str | None = None) -> tuple[dict | None, str]:
    """(the core's plan, the words to say). `when` from the app's month toggle beats the words."""
    if me.role is not Role.PARENT:
        return None, ("Planning the family's outings is a job for your parents. When they save a plan, "
                      "you'll see it on your Home screen.")
    model = model or default_llm
    run_id = core.open_run("PLANNER", message)
    run = core.in_run(run_id)
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        board = run.outings_board()
        asked = when or read_when(message)
        nothing_left = board["leftThisMonth"]["minor"] == 0
        month = asked or ("NEXT_MONTH" if nothing_left else "THIS_MONTH")
        kinds = read_kinds(message)
        keys: list[str] = []
        result = model.ask(SYSTEM, _prompt(message, board, month), SCHEMA, max_tokens=300)
        if result.used and result.data and result.data.get("understood"):
            known = {o["key"] for o in board["options"]}
            keys = [k for k in result.data.get("keys") or [] if k in known][:4]
            if not asked and result.data.get("when") in ("THIS_MONTH", "NEXT_MONTH"):
                month = result.data["when"]
            if not keys:
                result.used, result.fallback_reason = False, "LLM picked nothing from the list"
        elif result.used:
            result.used, result.fallback_reason = False, "LLM did not read it as an outings question"
        chosen = run.outings_plan(month, kinds if not keys else [], keys)
        # Over budget this month and no month asked for: next month's plan, plus free ideas for now
        also = run.outings_plan("THIS_MONTH", kinds, []) if not asked and month == "NEXT_MONTH" and nothing_left else None
        reply = reply_for(chosen, also)
        core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return chosen, reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return None, f"I couldn't plan outings just now. {e.detail}."


def handle(core: CoreClient, me: Me, message: str, model: Llm | None = None) -> str:
    return plan(core, me, message, model)[1]
