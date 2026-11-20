"""The weekly money check: an agent that acts without being asked.

Every Sunday morning the core wakes the Planner for each family (and a parent can press "Run the check now").
The Planner looks at the family's facts from the core (the month's money, the bills coming up, the budgets, the goals,
odd charges and each child's month), decides what matters this week, and proposes at most two actions:

  * NOTE   - something a parent should know ("the school fees reserve is short");
  * BUDGET - a budget to slow down, with a link to change it in Budgets;
  * NUDGE  - a short, kind message to one child, sent only if a parent approves it.

Nothing happens until a parent approves a proposal in the app; the core refuses an approval from an agent.
Claude chooses and words the proposals; every number in them must come from the facts it was given, and each proposal
carries the facts it is based on, word for word ("Why this?"). With the AI off, simple rules pick the proposals.
"""

import time
from dataclasses import dataclass

from .agent_loop import _ms
from .core_client import CoreClient, CoreError, Me, Member, NewProposal, Role
from .llm import Llm, LlmResult, llm as default_llm, numbers_ok

MAX_PROPOSALS = 2


@dataclass
class Fact:
    id: int
    text: str
    topic: str            # BILL, BUDGET, GOAL, CHARGE, MONEY, CHILD
    child: Member | None = None
    concern: int = 0      # how much a parent should hear about it this week: 0 = fine, 3 = act now


def gather(core: CoreClient) -> list[Fact]:
    facts: list[Fact] = []

    def add(text: str, topic: str, child: Member | None = None, concern: int = 0) -> None:
        facts.append(Fact(len(facts) + 1, text, topic, child, concern))

    c = core.cashflow()
    until = c.untilNextIncome
    add(f"{c.monthLabel}: income {c.income.total.text}, bills {c.commitments.total.text}, spare {c.spare.text}.",
        "MONEY")
    if until.nextIncomeOn:
        add(f"Free until the next pay ({until.nextIncomeLabel or 'pay day'}, {until.nextIncomeOn.strftime('%d %b')}): "
            f"{until.free.text}.", "MONEY", concern=2 if until.free.minor <= 0 else 0)
    for u in c.upcoming:
        if u.daysLeft > 14:
            continue
        if u.state == "SHORT" and u.shortBy:
            add(f"{u.name} is due {u.dueOn.strftime('%d %b')} ({u.amount.text}); its reserve is {u.shortBy.text} "
                "short, so that part comes out of free cash.", "BILL", concern=3)
        else:
            add(f"{u.name} is due {u.dueOn.strftime('%d %b')} ({u.amount.text}); its money is ready.", "BILL")
    for note in c.notes[:3]:
        add(note, "MONEY")

    b = core.budgets()
    for line in b.lines:
        if line.state == "OVER":
            add(f"{line.label} budget: {line.spent.text} spent of {line.limit.text} with {b.daysLeft} days left. "
                + (line.alert or "It is over."), "BUDGET", concern=3)
        elif line.state == "HEADING_OVER":
            add(f"{line.label} budget: {line.spent.text} of {line.limit.text} with {b.daysLeft} days left. "
                + (line.alert or "It is heading over."), "BUDGET", concern=2)
        elif line.state == "ABOVE_USUAL":
            add(f"{line.label} budget: {line.spent.text} so far, above the usual. " + (line.alert or ""),
                "BUDGET", concern=1)

    for g in core.goals():
        add(f"Goal {g.name}: {g.saved.text} saved of {g.target.text}, {g.left.text} left"
            + (f", {g.monthly.text} a month." if g.monthly else ", no monthly amount set."), "GOAL",
            concern=1 if g.monthly is None else 0)

    for f in core.findings():
        add(f"{f.title}: {f.detail}", "CHARGE", concern=2 if f.kind == "DUPLICATE" else 1)

    for kid in (m for m in core.members() if m.role is Role.CHILD):
        try:
            s = core.spend_summary(member_id=kid.id)
        except CoreError:
            continue
        top = s.categories[0].label if s.categories else None
        add(f"{kid.displayName} spent {s.total.text} of {s.pocketMoney.text} pocket money in {s.periodLabel} "
            f"({s.spentPercentOfPocketMoney}%), logged on {s.daysLogged} days"
            + (f", mostly on {top}." if top else "."), "CHILD", kid,
            concern=1 if s.spentPercentOfPocketMoney >= 90 else 0)
        for goal in s.goals:
            add(f"{kid.displayName}'s goal {goal.name}: {goal.saved.text} saved of {goal.target.text} "
                f"({goal.percent}%).", "CHILD", kid, concern=1 if goal.percent >= 75 else 0)
    return facts


SYSTEM = """You are the Planner agent of a UAE family finance app, doing the family's weekly money check on your own.
You get numbered facts from the app's money engine. Decide what matters most for the parents THIS week and propose at
most two actions. Fewer is better: propose nothing if all is well.

Kinds of action:
- NOTE: something the parents should know or do (a bill whose reserve is short, a duplicate charge).
- BUDGET: a budget to slow down or change this week.
- NUDGE: a short, kind message to ONE child (praise, or a gentle reminder about their own spending or goal). Use only
  that child's own facts. Never mention the family's money, a sibling, or the parents' bills to a child.

Rules:
- Never do arithmetic or make up a number. Use numbers exactly as the facts write them.
- For each action, list the fact ids it is based on.
- title: under 8 words. body: one or two plain sentences to the parent. child_message: one or two sentences to the
  child (NUDGE only, else empty). child: the child's name for a NUDGE, else empty.
- summary: one sentence for the parent about the week."""

SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "proposals": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "kind": {"type": "string", "enum": ["NOTE", "BUDGET", "NUDGE"]},
                    "fact_ids": {"type": "array", "items": {"type": "integer"}},
                    "title": {"type": "string"},
                    "body": {"type": "string"},
                    "child": {"type": "string"},
                    "child_message": {"type": "string"},
                },
                "required": ["kind", "fact_ids", "title", "body", "child", "child_message"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["summary", "proposals"],
    "additionalProperties": False,
}

_LINKS = {"BUDGET": "/parent/money/budgets", "NOTE": None, "NUDGE": None}


def _from_llm(data: dict, facts: list[Fact]) -> tuple[str, list[NewProposal]] | None:
    """Claude's choice as proposals, or None if anything doesn't hold up (a made-up number, an unknown fact)."""
    by_id = {f.id: f for f in facts}
    out: list[NewProposal] = []
    for p in (data.get("proposals") or [])[:MAX_PROPOSALS]:
        used = [by_id[i] for i in p.get("fact_ids") or [] if i in by_id]
        if not used:
            return None
        words = [p.get("title", ""), p.get("body", ""), p.get("child_message", "")]
        if not all(numbers_ok(w, [f.text for f in used]) for w in words):
            return None
        kind = p.get("kind")
        child = None
        if kind == "NUDGE":
            name = (p.get("child") or "").strip().lower()
            children = {f.child.displayName.lower(): f.child for f in facts if f.child}
            child = children.get(name)
            # A nudge may only use that child's own facts, and needs words for the child
            if child is None or not p.get("child_message") or any(f.child is None or f.child.id != child.id
                                                                   for f in used):
                return None
        out.append(NewProposal(kind=kind, title=p["title"][:120], body=p["body"][:400], why=[f.text for f in used],
                               childId=child.id if child else None,
                               childMessage=p["child_message"][:300] if child else None, link=_LINKS.get(kind)))
    summary = data.get("summary") or ""
    if not numbers_ok(summary, [f.text for f in facts]):
        summary = ""
    return summary, out


def rule_based(facts: list[Fact]) -> tuple[str, list[NewProposal]]:
    """With the AI off: the two biggest concerns, in fixed words."""
    worst = sorted((f for f in facts if f.concern >= 2), key=lambda f: -f.concern)[:MAX_PROPOSALS]
    out = []
    for f in worst:
        if f.topic == "BUDGET":
            out.append(NewProposal(kind="BUDGET", title="Slow down on a budget", body=f.text, why=[f.text],
                                   link=_LINKS["BUDGET"]))
        elif f.topic == "BILL":
            out.append(NewProposal(kind="NOTE", title="A bill's reserve is short", body=f.text, why=[f.text]))
        elif f.topic == "CHARGE":
            out.append(NewProposal(kind="NOTE", title="Check a charge", body=f.text, why=[f.text]))
        else:
            out.append(NewProposal(kind="NOTE", title="Worth a look this week", body=f.text, why=[f.text]))
    summary = "Two things to look at this week." if len(out) == 2 else (
        "One thing to look at this week." if out else "Nothing needs you this week.")
    return summary, out


def check(core: CoreClient, me: Me, trigger: str = "MESSAGE", model: Llm | None = None) -> dict:
    """Runs the check for the parent's family and saves the proposals. Returns the summary and what was proposed."""
    if me.role is not Role.PARENT:
        raise CoreError(403, "Only a parent can run the money check")
    model = model or default_llm
    started = time.monotonic()
    run_id = core.open_run("PLANNER", "Weekly money check", trigger)
    run = core.in_run(run_id)
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        facts = gather(run)
        listed = "\n".join(f"{f.id}. [{f.topic}{' - ' + f.child.displayName if f.child else ''}] {f.text}"
                           for f in facts)
        result = model.ask(SYSTEM, f"FACTS:\n{listed}", SCHEMA, max_tokens=1200)
        chosen = _from_llm(result.data, facts) if result.used and result.data else None
        if result.used and chosen is None:
            result.used, result.fallback_reason = False, "LLM proposals did not check out"
        summary, proposals = chosen or rule_based(facts)
        saved = []
        for p in proposals:
            try:
                saved.append(run.propose(p))
            except CoreError as e:
                if e.status != 409:  # 409: the same suggestion is already waiting for a parent
                    raise
        reply = summary or ("Nothing needs you this week." if not proposals else "")
        out = f"{reply} Proposed: " + "; ".join(p.title for p in proposals) if proposals else reply
        core.finish_run(run_id, True, out, result.model, result.used, result.fallback_reason, _ms(started),
                        result.input_tokens, result.output_tokens)
        return {"summary": reply, "proposals": saved, "factsChecked": len(facts), "aiChose": result.used}
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, _ms(started),
                        result.input_tokens, result.output_tokens)
        raise
