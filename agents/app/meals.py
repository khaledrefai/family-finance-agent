"""Planner agent: the week's dinners and the shopping list.

A parent says what the week looks like ("plan dinners, Tuesday and Thursday are busy, the kids want pasta"). This
agent only reads that into a typed ask: which nights are delivery nights, and (with Claude on, or by matching words
without it) which of the core's saved recipes fit the family's wishes. The core does everything else
(POST /api/meals/plan): it leaves out recipes that don't suit someone (allergy rules, vegetarian, dislikes), fills
the other nights with the cheapest dinners that suit everyone, adds up the shopping list in whole packs at saved
prices and checks it against the week's grocery money. This agent then says the core's facts. It never prices, adds
up or compares anything itself, and never says a product is halal: the core's facts say to check the halal mark.
"""

import re
from datetime import date

from .core_client import CoreClient, CoreError, Me, Role
from .llm import Llm, LlmResult, llm as default_llm

SYSTEM = """You help a UAE family plan the week's dinners. You get the parent's words, the 7 nights of the week
and a list of saved family recipes, each with a key and a line about it. Only recipes that suit everyone in the family
are on the list. Pick up to 7 recipe keys that best match what the parent asked for, in the order of the cooking
nights; pick fewer if the parent only named a few wishes, and the app fills the rest with cheap dinners. Also list
the nights the parent said are busy or want delivery, as dates from the list of nights. Only use keys and dates from
the lists. Never invent a dish. Do not add up or compare any prices: the app does that. If the words are not about
dinners, meals or the shopping list, set understood to false."""

SCHEMA = {
    "type": "object",
    "properties": {
        "understood": {"type": "boolean"},
        "keys": {"type": "array", "items": {"type": "string"}},
        "deliveryDays": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["understood", "keys", "deliveryDays"],
    "additionalProperties": False,
}

_MEAL_WORDS = re.compile(
    r"\b(dinners?|suppers?|meal ?plan|meals? (for|this|next) (the )?week|weekly meals|what (should|can|do|shall) (we|i) "
    r"(cook|make|eat)|what to cook|grocery (plan|list)|shopping list|weekly shop(ping)?|cook this week)\b", re.I)
_BUSY = re.compile(r"\b(busy|delivery|deliver\w*|order (in|food)|takeaway|take-away|no time|late|working late|out)\b", re.I)
_DAYS = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6}
_DAY_WORD = re.compile(r"\b(mon|tues?|wed(nes)?|thu(rs)?|fri|sat(ur)?|sun)(day)?s?\b", re.I)
_WISH_WORDS = ["pasta", "chicken", "fish", "beef", "lamb", "soup", "rice", "eggs", "wraps", "lentil", "biryani",
               "shawarma", "machboos", "kofta", "bolognese", "halloumi", "tuna", "chickpea", "foul", "shakshuka"]


def is_meals_question(message: str) -> bool:
    return bool(_MEAL_WORDS.search(message))


def read_delivery_days(message: str, days: list[dict]) -> list[str]:
    """The busy nights the parent named ("Tuesday and Thursday are busy"), as dates from the core's week."""
    if not _BUSY.search(message):
        return []
    wanted: set[int] = set()
    for m in _DAY_WORD.finditer(message):
        wanted.add(_DAYS[m.group(1)[:3].lower()])
    if re.search(r"\btonight\b", message, re.I) and days:
        wanted.add(date.fromisoformat(days[0]["date"]).weekday())
    if re.search(r"\btomorrow\b", message, re.I) and len(days) > 1:
        wanted.add(date.fromisoformat(days[1]["date"]).weekday())
    return [d["date"] for d in days if date.fromisoformat(d["date"]).weekday() in wanted]


def read_wishes(message: str, board: dict) -> list[str]:
    """Without Claude: the saved recipes that suit everyone and whose name has a word the parent used, cheapest first."""
    words = [w for w in _WISH_WORDS if re.search(rf"\b{w}", message, re.I)]
    if re.search(r"\bvegetarian|meat[- ]free|no meat\b", message, re.I):
        words.append("vegetarian")
    keys: list[str] = []
    for w in words:
        for r in board["recipes"]:
            if not r["suits"] or r["key"] in keys:
                continue
            if (w == "vegetarian" and r["vegetarian"]) or w in r["name"].lower():
                keys.append(r["key"])
                break
    return keys[:7]


def _prompt(message: str, board: dict) -> str:
    lines = [f"PARENT SAID: {message}", "NIGHTS:"]
    lines += [f"- {d['date']}: {d['label']}" for d in board["days"]]
    lines.append("RECIPES:")
    for r in board["recipes"]:
        if r["suits"]:
            lines.append(f"- {r['key']}: {r['name']} ({r['main'].lower()}, {r['minutes']} min). {r['about']}")
    return "\n".join(lines)


def reply_for(plan: dict) -> str:
    """The core's facts about the week, in its order. No numbers of our own."""
    facts = plan.get("facts") or []
    nights = plan.get("nights") or []
    if not nights:
        return " ".join(facts[:2])
    said = []
    for n in nights:
        day = n["label"].split(" ")[0]
        said.append(f"{day} {n['name']}" if n["kind"] == "COOK" else f"{day} delivery ({n['name']})")
    head = "This week's dinners: " + ", ".join(said) + "."
    keep = [f for f in facts if f.startswith(("Shopping list:", "That is", "Swapping", "Left out", "Only "))]
    return " ".join([head] + keep + ["Prices are from our saved list, not live. Check the halal mark and pack labels when you shop."])


def plan(core: CoreClient, me: Me, message: str, model: Llm | None = None,
         delivery_days: list[str] | None = None) -> tuple[dict | None, str]:
    """(the core's plan, the words to say). Busy nights picked in the app beat the words."""
    if me.role is not Role.PARENT:
        return None, ("Planning dinners is a job for your parents. When they save the week, "
                      "you'll see what's for dinner on your Home screen.")
    model = model or default_llm
    run_id = core.open_run("PLANNER", message)
    run = core.in_run(run_id)
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        board = run.meals_board()
        known_days = {d["date"] for d in board["days"]}
        busy = [d for d in (delivery_days or []) if d in known_days] if delivery_days is not None \
            else read_delivery_days(message, board["days"])
        keys = read_wishes(message, board)
        result = model.ask(SYSTEM, _prompt(message, board), SCHEMA, max_tokens=300)
        if result.used and result.data and result.data.get("understood"):
            suits = {r["key"] for r in board["recipes"] if r["suits"]}
            picked = [k for k in result.data.get("keys") or [] if k in suits][:7]
            if picked:
                keys = picked
            if delivery_days is None:
                said = [d for d in result.data.get("deliveryDays") or [] if d in known_days]
                busy = sorted(set(busy) | set(said))
        elif result.used:
            result.used, result.fallback_reason = False, "LLM did not read it as a dinners question"
        chosen = run.meals_plan(keys, busy[:3])
        reply = reply_for(chosen)
        core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return chosen, reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return None, f"I couldn't plan dinners just now. {e.detail}."


def handle(core: CoreClient, me: Me, message: str, model: Llm | None = None) -> str:
    return plan(core, me, message, model)[1]
