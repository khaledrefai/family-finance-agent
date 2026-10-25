"""Planner agent: the family plan in plain words, and a draft split of the month into money buckets.

What it does for a parent:
  1. Opens an agent run, so the core records every call it makes ("What the agent did").
  2. Reads the plan from the core: free cash, what is free until pay day, tight months, goals.
  3. Saves a BucketPlanDraft. The shares come from the core's engine; Claude only writes one line per bucket.
     The draft waits for a parent's Confirm in the app: the agent can never confirm it.
  4. If the parent names a goal ("the summer trip"), adds the relaxed / steady / faster options.
  5. Claude turns the facts into a short answer. If Claude is off, slow, or says a number that is not in the
     facts, the template below answers instead.

Information only, not financial advice.
"""

import calendar
import re

from .core_client import CoreClient, CoreError, Me, Role, Share
from .llm import Llm, LlmResult, llm as default_llm, numbers_ok

KINDS = ["COMMITMENTS", "EVERYDAY", "GOALS", "INVEST_ASIDE", "FUN", "GIVING"]

SYSTEM = """You are the Planner in a UAE family finance app. You explain the family's money plan to a parent.
Write plain, warm English for a busy parent. Short sentences. No jargon. No advice to buy or sell anything.
Use ONLY the numbers in FACTS, written exactly as they appear there (for example "AED 6,500").
Never add, subtract or work out any new number. If a number is not in FACTS, leave it out.
Never say a plan is confirmed: the parent confirms it in the app."""

SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string", "description": "2 to 4 sentences for the parent"},
        "reasons": {
            "type": "object",
            "description": "One short line per bucket saying what it is for",
            "properties": {k: {"type": "string"} for k in KINDS},
            "required": KINDS,
            "additionalProperties": False,
        },
    },
    "required": ["summary", "reasons"],
    "additionalProperties": False,
}

_PLAN_WORDS = re.compile(r"\b(plan|bucket|buckets|budget my|split|free cash|pay day|payday|tight|goal|goals|"
                         r"trip|save for|saving for|cash flow|cashflow)\b", re.I)


def is_plan_question(message: str) -> bool:
    return bool(_PLAN_WORDS.search(message))


def handle(core: CoreClient, me: Me, message: str, model: Llm | None = None) -> str:
    if me.role is not Role.PARENT:
        return "Planning the family's money is a job for your parents. You can ask me about your own savings goal."
    model = model or default_llm
    run_id = core.open_run("PLANNER", message)
    run = core.in_run(run_id)
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        cf = run.cashflow()
        goals = run.goals()
        shares = run.bucket_suggestion()
        goal = next((g for g in goals if _mentions(message, g.name)), None)
        options = run.plan_options(goal.id) if goal else None

        facts = _facts(cf, shares, options)
        result = model.ask(SYSTEM, _prompt(message, facts), SCHEMA)
        summary, reasons = _template(cf, options), {}
        if result.used and result.data:
            words = [result.data.get("summary", "")] + list((result.data.get("reasons") or {}).values())
            if all(numbers_ok(w, facts + [message]) for w in words):
                summary = result.data["summary"].strip()
                reasons = result.data.get("reasons") or {}
            else:
                result.used, result.fallback_reason = False, "number check failed"

        drafted = run.save_bucket_draft([Share(kind=s.kind, shareBp=s.shareBp,
                                               reason=(reasons.get(s.kind) or s.reason or "")[:240]) for s in shares])
        reply = summary + "\n\n" + _bucket_lines(drafted) + "\n\nThis split is a draft. Check it and press Confirm in the app."
        core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return f"I couldn't do that. {e.detail}."


def _mentions(message: str, name: str) -> bool:
    words = [w for w in re.findall(r"[a-z]+", name.lower()) if len(w) > 3]
    return any(re.search(rf"\b{w}\b", message, re.I) for w in words)


def _facts(cf, shares: list[Share], options) -> list[str]:
    u = cf.untilNextIncome
    facts = [
        f"Planned income in {cf.monthLabel}: {cf.income.total.text}.",
        f"Commitments and reserves this month: {cf.commitments.total.text}.",
        f"Everyday budgets: {cf.everyday.total.text}.",
        f"Free cash this month: {cf.free.text}. After goal saving of {cf.saving.total.text}, spare: {cf.spare.text}.",
    ]
    if u.nextIncomeOn:
        facts.append(f"Free until the next pay ({u.nextIncomeLabel}, {u.nextIncomeOn.day} {u.nextIncomeOn:%b}): {u.free.text}.")
    if cf.nextCommitment:
        n = cf.nextCommitment
        facts.append(f"Next bill: {n.name}, {n.amount.text}, on {n.dueOn.day} {n.dueOn:%b}.")
    tight = [_month_name(m.month) for m in cf.months if m.tight]
    if tight:
        facts.append("Tight months: " + ", ".join(tight) + ".")
    facts += cf.notes
    facts += [f"Bucket {s.kind}: {s.reason}" for s in shares if s.reason]
    if options:
        for o in options.options:
            facts.append(f"{options.goalName}, {o.name}: {o.monthly.text} a month, ready by {o.readyMonth}"
                         + ("" if o.fits else f", does not fit in {o.tightestMonth}") + ".")
    return facts


def _prompt(message: str, facts: list[str]) -> str:
    return ("PARENT ASKED: " + message + "\n\nFACTS:\n" + "\n".join("- " + f for f in facts)
            + "\n\nWrite the summary and one line for each bucket.")


def _template(cf, options) -> str:
    """The rule-based answer: always right, a little plain."""
    u = cf.untilNextIncome
    parts = [f"This month {cf.free.text} is free after commitments and everyday budgets, and {cf.spare.text} is spare after goal saving."]
    if u.nextIncomeOn:
        parts.append(f"Until {u.nextIncomeLabel} arrives on {u.nextIncomeOn.day} {u.nextIncomeOn:%b}, {u.free.text} is free.")
    tight = [_month_name(m.month) for m in cf.months if m.tight]
    if tight:
        parts.append("Watch " + " and ".join(tight) + ": a big payment comes before its reserve is full.")
    if options:
        rec = next((o for o in options.options if o.id == options.recommended), None)
        if rec:
            parts.append(f"For {options.goalName}, the {rec.name.lower()} pace is {rec.monthly.text} a month, ready by {rec.readyMonth}.")
    return " ".join(parts)


def _month_name(month: str) -> str:
    """'2027-03' -> 'March'"""
    return calendar.month_name[int(month[5:7])]


def _bucket_lines(b) -> str:
    return "\n".join(f"• {x.label}: {x.amount.text}" for x in b.buckets)
