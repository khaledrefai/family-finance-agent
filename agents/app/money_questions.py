"""Planner agent: quick money questions a parent asks in "Ask the accountant", answered from the core's own numbers.

  * "How much is free until pay day?"  -> GET /api/plan/cashflow (what is free until the next pay, and why)
  * "I got a AED 5,000 bonus"          -> GET /api/plan/cashflow, /api/plan/goals and /api/plan/one-off-effect:
                                          what is free now, and the goals the money could go to (buffer first)
  * "How much gold do we have?"        -> GET /api/gold (grams, what it is worth at today's price, what was paid)
  * "What are we worth?"               -> GET /api/wealth (owned, owed, the total, and the parts)

Like every agent here, this one never does money maths: each number in a reply is a MoneyView text or a line the core
wrote. It never suggests buying or selling gold, an amount or a time. The parent decides where money goes.
"""

import re

from .core_client import CoreClient, CoreError, Me, Role
from .gatekeeper import amount_in

# "how much is free until pay day", "what's left till payday", "how much can we spend before the salary"
_FREE = re.compile(
    r"\b(free|left|spare|to spend|can (we|i) spend|do we have|have we got)\b.*\b(until|till|til|before)\s+(the\s+)?(next\s+)?"
    r"(pay ?day|pay|salary|income)\b"
    r"|\bhow much (money |cash )?is free\b|\bfree (money|cash) (now|right now|today)\b", re.I)

# Extra money that came in. A child's words never come here (see graph.route_message).
_EXTRA = re.compile(
    r"\b(bonus|windfall|extra (income|money|cash|pay)|one[- ]off (money|payment|income)|unexpected money|"
    r"tax refund|eid money|inheritance|commission came|(got|received|earned) (a |an |some )?(raise|refund|gift))\b",
    re.I)

_GOLD = re.compile(r"\bgold\b", re.I)
_HOW_MUCH = re.compile(r"\b(how much|how many|worth|value|valued|total|have|own|holding|grams?)\b", re.I)
_WEALTH = re.compile(r"\b(net worth|our wealth|family wealth|wealth|how much are we worth|what are we worth|"
                     r"what do we own|everything we own)\b", re.I)

CHILD_REPLY = ("That's part of your parents' plan. You can ask me how close you are to your own savings goal, "
               "or tell me what you bought.")


def is_free_question(message: str) -> bool:
    return bool(_FREE.search(message))


def is_extra_income(message: str) -> bool:
    """'I got a AED 5,000 bonus': money that came in, not something to buy."""
    if re.search(r"\b(afford|buy)\b", message, re.I):
        return False
    return bool(_EXTRA.search(message))


def is_gold_question(message: str) -> bool:
    """'How much gold do we have?', 'what is our gold worth'. A long-term what-if goes to the calculator instead."""
    return bool(_GOLD.search(message) and _HOW_MUCH.search(message))


def is_wealth_question(message: str) -> bool:
    return bool(_WEALTH.search(message))


def is_money_question(message: str) -> bool:
    return (is_free_question(message) or is_extra_income(message) or is_gold_question(message)
            or is_wealth_question(message))


def handle(core: CoreClient, me: Me, message: str) -> str:
    if me.role is not Role.PARENT:
        return CHILD_REPLY
    run_id = core.open_run("PLANNER", message)
    run = core.in_run(run_id)
    try:
        if is_extra_income(message):
            reply = extra_income(run, message)
        elif is_gold_question(message):
            reply = gold(run)
        elif is_wealth_question(message):
            reply = wealth(run)
        else:
            reply = free_until_pay(run)
        core.finish_run(run_id, True, reply, None, False, "rule-based", None, None, None)
        return reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, None, False, "rule-based", None, None, None)
        return f"I couldn't check that. {e.detail}."


# ------------------------------------------------------------------ free until pay day

def free_until_pay(core: CoreClient) -> str:
    cf = core.cashflow()
    u = cf.untilNextIncome
    if u.nextIncomeOn is None:
        return (f"There is no pay day in the plan yet. This month {cf.free.text} is free after commitments and "
                f"everyday budgets. Add your income in Plan to see what is free until it arrives.")
    lines = [f"{u.free.text} is free until {u.nextIncomeLabel} arrives on {_day(u.nextIncomeOn)}."]
    if u.why:
        lines.append("How the app worked it out:\n" + "\n".join(f"• {w.label}: {w.amount.text}" for w in u.why))
    if cf.nextCommitment:
        n = cf.nextCommitment
        lines.append(f"Next bill: {n.name}, {n.amount.text}, on {_day(n.dueOn)}.")
    return "\n\n".join(lines)


# ------------------------------------------------------------------ extra income (a bonus, a gift)

def extra_income(core: CoreClient, message: str) -> str:
    cf = core.cashflow()
    goals = [g for g in core.goals() if g.left.minor > 0]
    amount = amount_in(message)
    effect = core.one_off_effect(amount) if amount else None
    said = effect["amount"]["text"] if effect else "that money"

    u = cf.untilNextIncome
    now = (f"Before {said} is counted, {u.free.text} is free until {u.nextIncomeLabel} on {_day(u.nextIncomeOn)}"
           if u.nextIncomeOn else f"Before {said} is counted, {cf.free.text} is free this month")
    spare = (f", and {cf.spare.text} is spare this month after goal saving." if cf.spare.minor >= 0
             else f", and this month is short by {cf.spare.text.lstrip('-')} after goal saving.")
    lines = [f"Nice, well done! {now}{spare}"]

    if goals:
        by_goal = {str(line["goalId"]): line for line in (effect or {}).get("goals", [])}
        # Safety first: the buffer (Survival goals), then the rest in the family's own order
        order = sorted(goals, key=lambda g: g.tier != "SURVIVAL")
        where = []
        for g in order:
            line = f"• {g.name}: {g.left.text} still to go ({g.saved.text} of {g.target.text} saved)"
            e = by_goal.get(str(g.id))
            if e and e["monthsSooner"] > 0:
                line += f". All of it here: full in {e['fullAfter']} instead of {e['fullBefore']}"
            elif e:
                line += f". Full in {e['fullBefore']} either way"
            where.append(line + ".")
        lines.append("Where it could go, the emergency buffer first:\n" + "\n".join(where))
        if effect:
            lines.append(effect["note"])
    else:
        lines.append("Every goal is full. You could start a new one in Plan, or keep it as a buffer.")
    lines.append("Add it in Plan, Income on the day it arrives so the plan counts it. You decide where it goes.")
    return "\n\n".join(lines)


# ------------------------------------------------------------------ gold and wealth

def gold(core: CoreClient) -> str:
    g = core.gold()
    if not g.get("holdings"):
        return "You haven't logged any gold yet. Add what you bought in Money, Gold and I'll tell you what it's worth."
    grams = " and ".join(f"{x['grams']} g of {x['purity']}" for x in g["grams"])
    price = next((p for p in g.get("prices", []) if p["purity"] == "24K"), None)
    lines = [f"You have {grams}. At today's price it is worth {g['value']['text']}. You paid {g['paid']['text']} for it."]
    if price:
        lines.append(f"Valued at {price['perGram']['text']} a gram for 24K.")
    if g.get("byGoal"):
        lines.append("Set aside for: " + "; ".join(f"{x['name']}, {x['grams']} g ({x['value']['text']})"
                                                     for x in g["byGoal"]) + ".")
    if g.get("priceIsSample"):
        lines.append("That price is a sample. Type today's price in Money, Gold for a better value.")
    if any(h.get("jewellery") for h in g["holdings"]) and g.get("makingNote"):
        lines.append(g["makingNote"])
    return "\n\n".join(lines)


def wealth(core: CoreClient) -> str:
    w = core.wealth()
    lines = [f"About {w['total']['text']} in all: {w['owned']['text']} you own, less {w['owed']['text']} you owe."]
    parts = [s for s in w.get("steps", []) if s.get("kind") != "total"]  # the totals are in the first line
    if parts:
        lines.append("\n".join(f"• {s['label']}: {s['amount']['text']}" for s in parts))
    if w.get("note"):
        lines.append(w["note"])
    return "\n\n".join(lines)


def _day(d) -> str:
    return f"{d.day} {d:%b}"
