"""Planner agent: the long-term savings calculator in Ask ("what if we put AED 1,000 a month at 6% for 15 years?").

This step only reads the parent's words into a typed ask: the amount a month, what is saved already, the yearly
rate, how many years, and which family goal they mean. The core works out every figure
(POST /api/plan/calculator) and this agent repeats the core's own lines. It never works out a number itself.

Education only: the rate is the parent's example, and the reply never names a fund, a product, gold or a time to buy.
With no amount a month and no goal named, it starts from the Set aside for investing bucket.
"""

import re

from .core_client import CoreClient, CoreError, Me, Role

_CALC_WORDS = re.compile(
    r"\b(calculator|what if (we|i) (put|save|invest|set aside)|grow|compound\w*|in \d+ years|over \d+ years|"
    r"for \d+ years|after \d+ years|long[- ]term|invest\w*)\b", re.I)
_NUMBER = r"(\d[\d,]*(?:\.\d{1,2})?)"
_MONTHLY = re.compile(rf"(?:aed|dhs?|dirhams?)?\s*{_NUMBER}\s*(?:aed|dhs?|dirhams?)?\s*(?:a|per|each|every)\s+month", re.I)
_START = re.compile(rf"(?:start\w* (?:with|from)|already (?:have|saved)|have saved|we have)\s*(?:aed|dhs?)?\s*{_NUMBER}",
                    re.I)
_RATE = re.compile(r"(\d{1,2}(?:\.\d{1,2})?)\s*(?:%|percent)", re.I)
_YEARS = re.compile(r"\b(\d{1,2})\s*years?\b", re.I)

CHILD_REPLY = ("The savings calculator is for your parents for now. You can ask me how close you are to your own "
               "savings goal.")


def is_calculator_question(message: str) -> bool:
    return bool(_CALC_WORDS.search(message))


def read_ask(message: str) -> dict:
    """The parent's words as the core's CalculatorBody. Only what was said; the core fills the rest."""
    ask: dict = {}
    if m := _MONTHLY.search(message):
        ask["monthly"] = m.group(1)
    if m := _START.search(message):
        ask["start"] = m.group(1)
    if m := _RATE.search(message):
        ask["rate"] = m.group(1)
    if m := _YEARS.search(message):
        ask["years"] = int(m.group(1))
    return ask


def _mentions(message: str, name: str) -> bool:
    words = [w for w in re.findall(r"[a-z]+", name.lower()) if len(w) > 3]
    return any(re.search(rf"\b{w}\b", message, re.I) for w in words)


def handle(core: CoreClient, me: Me, message: str) -> str:
    if me.role is not Role.PARENT:
        return CHILD_REPLY
    run_id = core.open_run("PLANNER", message)
    run = core.in_run(run_id)
    try:
        ask = read_ask(message)
        goal = next((g for g in run.goals() if _mentions(message, g.name)), None)
        if goal is not None:
            ask["goalId"] = str(goal.id)
        elif "monthly" not in ask and "start" not in ask:
            ask["from"] = "INVEST_ASIDE"
        c = run.calculator(ask)
        reply = words(c)
        core.finish_run(run_id, True, reply, None, False, "rule-based", None, None, None)
        return reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, None, False, "rule-based", None, None, None)
        return f"I couldn't work that out. {e.detail}."


def words(c: dict) -> str:
    """The core's answer as a short reply: where it started, each year mark, the target if any, one caution."""
    p = c["projection"]
    lines = []
    if c.get("fromLine"):
        lines.append(c["fromLine"])
    lines.append(p["summary"])
    lines.append("\n".join(f"In {m['year']} {'year' if m['year'] == 1 else 'years'}: {m['total']['text']} "
                           f"({m['putIn']['text']} put in)" for m in p["marks"]))
    if p.get("reach"):
        lines.append(p["reach"]["line"])
    lines.append(p["notes"][0] + " Try other rates in Plan, Savings calculator.")
    return "\n\n".join(lines)
