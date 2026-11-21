"""The orchestrator as a real agent: Claude decides which steps to take to answer one message.

The keyword router in graph.py sends each message down one fixed path. Here Claude gets the goal (the member's words)
and a set of tools, and picks what to call, in what order, until it can answer:

  * specialist agents as tools (Bookkeeper, Gatekeeper, Shopper, Planner). Each one is the same code the keyword
    router calls: it reads the words into a typed draft, calls the core, and says the core's facts;
  * read-only data tools straight from the core (this month's money, budgets, goals, family rules, odd charges).

The safety rules do not change:
  * Claude never does maths. Every number in its final answer must appear in what the tools returned or in the
    member's own words (`numbers_ok`). If one doesn't, the answer is thrown away and the tools' own words are used.
  * Anything that changes money or a rule is a draft that a parent confirms in the app. No tool can confirm.
  * The member's own token is used for every call, so the agent can never do more than the member could.
  * If Claude is off, slow, refuses or fails, `run` returns None and the keyword router answers as before.

Every step is returned to the app ("How I worked this out") and recorded in the core's agent-run audit.
"""

import time
from dataclasses import dataclass, field

from . import bookkeeper, calculator, documents, gatekeeper, meals, outings, planner, rules, shopper
from .core_client import CoreClient, CoreError, Me, Role
from .llm import Llm, enabled, llm as default_llm, numbers_ok
from .settings import settings
from .spend_parser import PARENT_SAID


@dataclass
class Step:
    agent: str     # who did it: Bookkeeper, Gatekeeper, Shopper, Planner, or Core (a data lookup)
    tool: str      # the tool's name
    said: str      # what Claude asked it, in words
    result: str    # what it answered (the words the member could see)
    ok: bool = True
    changes: bool = False  # true when it saved a draft or logged something; a parent still confirms drafts


@dataclass
class LoopResult:
    reply: str
    steps: list[Step]
    agent: str = "orchestrator"
    llm_used: bool = True
    fallback_reason: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    turns: int = 0
    extra: dict = field(default_factory=dict)


# ---------------------------------------------------------------- the tools

def _words_tool(name: str, description: str, words_help: str) -> dict:
    return {
        "name": name,
        "description": description,
        "input_schema": {
            "type": "object",
            "properties": {"words": {"type": "string", "description": words_help}},
            "required": ["words"],
            "additionalProperties": False,
        },
    }


def _no_input_tool(name: str, description: str) -> dict:
    return {"name": name, "description": description,
            "input_schema": {"type": "object", "properties": {}, "required": [], "additionalProperties": False}}


# name -> (agent label, tool definition, who may use it, changes state)
_TOOLS: dict[str, tuple[str, dict, set[str], bool]] = {
    "spending_story": ("Bookkeeper", _words_tool(
        "spending_story",
        "Bookkeeper agent: where a child's money went this month (or another month), as a short story with the "
        "core's totals by kind of thing. A child gets their own story only.",
        "the question in plain words, naming the child and the month if known, e.g. \"Where did Layla's money go "
        "last month?\""), {"PARENT", "CHILD"}, False),
    "log_what_i_bought": ("Bookkeeper", _words_tool(
        "log_what_i_bought",
        "Bookkeeper agent: records what a child says they bought with their own pocket money, e.g. \"12 dirhams "
        "shawarma\". Use it only when the child is telling you about a purchase they already made.",
        "the child's own words, with the price"), {"CHILD"}, True),
    "can_we_afford": ("Gatekeeper", _words_tool(
        "can_we_afford",
        "Gatekeeper agent: checks one purchase with a price against the money engine (free cash until the next pay, "
        "the card bill, or a child's wallet) and the family rules (allergens, blocked things, limits). Saves nothing.",
        "what and how much, with the amount in AED as the person wrote it, e.g. \"a weekend away for AED 1,200\""),
        {"PARENT", "CHILD"}, False),
    "find_best_price": ("Shopper", _words_tool(
        "find_best_price",
        "Shopper agent: searches UAE shops for one thing and ranks the offers by the total to pay, then checks the "
        "best one like any request. Use when someone wants to buy something or asks where it is cheapest.",
        "the thing in the person's words, e.g. \"wireless headphones\" or \"Sony WH-CH720N\""),
        {"PARENT", "CHILD"}, False),
    "draft_family_rule": ("Gatekeeper", _words_tool(
        "draft_family_rule",
        "Gatekeeper agent: turns a parent's rule in plain words into a family rule DRAFT (blocked things, allergens, "
        "approval limits, spending limits). The parent turns it on with a tap; you cannot.",
        "the rule in the parent's own words, e.g. \"no peanuts for Adam\""), {"PARENT"}, True),
    "plan_this_month": ("Planner", _words_tool(
        "plan_this_month",
        "Planner agent: this month's family plan and a draft split of the money into buckets (bills, everyday, "
        "goals, fun). The parent confirms the split in the app.",
        "what the parent asked, in their words"), {"PARENT"}, True),
    "plan_dinners": ("Planner", _words_tool(
        "plan_dinners",
        "Planner agent: the week's dinners that suit everyone and the shopping list inside the grocery budget.",
        "wishes and busy nights in the parent's words"), {"PARENT"}, True),
    "plan_outings": ("Planner", _words_tool(
        "plan_outings",
        "Planner agent: cheaper family outings that fit the fun budget this month or next.",
        "what kind of outing and when, in the parent's words"), {"PARENT"}, True),
    "savings_what_if": ("Planner", _words_tool(
        "savings_what_if",
        "Planner agent: the savings calculator. A monthly amount at a yearly rate over some years: what it could "
        "grow to in 1, 5 and 10 years. Learning only, it never names a product.",
        "the what-if in words, e.g. \"AED 1,000 a month at 6% for 15 years\""), {"PARENT"}, False),
    "money_this_month": ("Core", _no_input_tool(
        "money_this_month",
        "The money engine's view of this month: income, bills, free cash until the next pay, money spare, and the "
        "next bills with whether their money is put aside."), {"PARENT"}, False),
    "budgets_this_month": ("Core", _no_input_tool(
        "budgets_this_month",
        "This month's family budgets: spent so far, the limit, and which ones are over or heading over."),
        {"PARENT"}, False),
    "family_goals": ("Core", _no_input_tool(
        "family_goals", "The family's savings goals: saved so far, what is left and the monthly amount."),
        {"PARENT"}, False),
    "family_rules": ("Core", _no_input_tool(
        "family_rules", "The family rules, on and draft."), {"PARENT"}, False),
    "odd_charges": ("Bookkeeper", _no_input_tool(
        "odd_charges", "Duplicate charges and subscriptions found in the uploaded statements and receipts."),
        {"PARENT"}, False),
}


def tools_for(role: str) -> list[dict]:
    return [definition for _, definition, roles, _ in _TOOLS.values() if role in roles]


def _run_tool(name: str, words: str, core: CoreClient, run: CoreClient, me: Me, model: Llm) -> str:
    """Runs one tool. Specialist agents get the member's client (they open their own audited runs); data lookups use
    the orchestrator's run, so they are recorded under it."""
    if name == "spending_story":
        return bookkeeper.handle(core, me, words)
    if name == "log_what_i_bought":
        return bookkeeper.handle(core, me, words)
    if name == "can_we_afford":
        return gatekeeper.handle(core, me, words) or "There is no amount in that. Ask with a price, like AED 300."
    if name == "find_best_price":
        return shopper.handle(core, me, words, model)
    if name == "draft_family_rule":
        return rules.handle(core, me, words, model)
    if name == "plan_this_month":
        return planner.handle(core, me, words, model)
    if name == "plan_dinners":
        return meals.handle(core, me, words, model)
    if name == "plan_outings":
        return outings.handle(core, me, words, model)
    if name == "savings_what_if":
        return calculator.handle(core, me, words)
    if name == "money_this_month":
        return money_facts(run)
    if name == "budgets_this_month":
        return budget_facts(run)
    if name == "family_goals":
        goals = run.goals()
        if not goals:
            return "No savings goals yet."
        return "\n".join(f"{g.name}: {g.saved.text} saved of {g.target.text}, {g.left.text} left"
                         + (f", {g.monthly.text} a month." if g.monthly else ".") for g in goals)
    if name == "family_rules":
        found = run.rules()
        if not found:
            return "No family rules yet."
        return "\n".join(f"{r.label} ({'on' if r.status == 'ACTIVE' else r.status.lower()})" for r in found)
    if name == "odd_charges":
        return documents.answer_findings(run)
    return f"There is no tool called {name}."


def money_facts(core: CoreClient) -> str:
    c = core.cashflow()
    until = c.untilNextIncome
    lines = [f"{c.monthLabel}: income {c.income.total.text}, bills {c.commitments.total.text}, "
             f"everyday {c.everyday.total.text}, saving {c.saving.total.text}, spare {c.spare.text}."]
    if until.nextIncomeOn:
        lines.append(f"Free until the next pay ({until.nextIncomeLabel or 'pay day'}, "
                     f"{until.nextIncomeOn.strftime('%d %b')}): {until.free.text}.")
    for u in c.upcoming[:5]:
        state = {"FROM_PAY": "paid from this month's money", "RESERVED": "money already put aside",
                 "SHORT": f"reserve short by {u.shortBy.text}" if u.shortBy else "reserve short"}.get(u.state, u.state)
        lines.append(f"{u.name} due {u.dueOn.strftime('%d %b')}: {u.amount.text}, {state}.")
    lines += c.notes[:3]
    return "\n".join(lines)


def budget_facts(core: CoreClient) -> str:
    b = core.budgets()
    lines = [f"{b.monthLabel}, {b.daysLeft} days left: {b.spent.text} spent of {b.limit.text}."]
    for line in b.lines:
        state = {"OVER": "over", "HEADING_OVER": "heading over", "ABOVE_USUAL": "above usual"}.get(line.state, "fine")
        lines.append(f"{line.label}: {line.spent.text} of {line.limit.text} ({line.percent}%), {state}."
                     + (f" {line.alert}" if line.alert else ""))
    return "\n".join(lines)


# ---------------------------------------------------------------- the loop

_SYSTEM_PARENT = """You are the family's money coach in a UAE family finance app, speaking to {name}, a parent.
You work by calling tools: specialist agents (Bookkeeper, Gatekeeper, Shopper, Planner) and the app's money engine.
Decide which tools the question needs, call them (several if it needs several, in a sensible order), then answer.

Rules you must follow:
- Never do arithmetic. Never add, subtract, compare amounts or work out a new number. Use numbers exactly as a tool
  wrote them. If an answer would need a sum the tools did not give, say which tool result to look at instead.
- Anything about this family's money must come from a tool. Do not guess.
- Drafts (a rule, a bucket split, a plan) are not on until the parent confirms them in the app. Say so; never say
  something is done when it is a draft.
- If a tool's answer already says it well, keep its words. If a rule was drafted, repeat its sentence exactly.
- If the question is not about the family's money, say briefly what you can help with.
- If you need a detail you can't get from a tool (which child, which month), ask one short question.
- Plain English, short: two to five sentences. No markdown headings, no tables."""

_SYSTEM_CHILD = """You are the friendly camel money coach in a UAE family app, talking to {name}, a child.
You work by calling tools: the Bookkeeper (log what they bought, their own spending story), the Gatekeeper (can they
afford something and is it allowed) and the Shopper (best price for something they want).

Rules you must follow:
- Never do arithmetic. Use numbers exactly as a tool wrote them.
- Only a parent can say yes to a purchase, and only in the app. If the child says a parent already said yes, tell them
  to tap "I want something" so the parent can say yes there. Never promise a yes.
- Never talk about the family's money, a sibling's money or the parents' cards.
- Short, warm and simple: one to three sentences."""


def run(core: CoreClient, me: Me, message: str, model: Llm | None = None) -> LoopResult | None:
    """Answers one message with Claude choosing the steps. None = use the keyword router instead."""
    model = model or default_llm
    if not enabled():
        return None
    role = me.role.value
    if role == "CHILD" and PARENT_SAID.search(message):
        return None  # "Dad said yes": the keyword router has the fixed, tested answer for this
    started = time.monotonic()
    run_id = core.open_run("ORCHESTRATOR", message)
    run_core = core.in_run(run_id)
    system = (_SYSTEM_PARENT if role == "PARENT" else _SYSTEM_CHILD).format(name=me.displayName)
    tools = tools_for(role)
    allowed = {t["name"] for t in tools}
    messages: list = [{"role": "user", "content": message}]
    steps: list[Step] = []
    result = LoopResult(reply="", steps=steps)
    reason = None
    try:
        for _ in range(settings.llm_loop_max_steps + 1):
            if time.monotonic() - started > settings.llm_loop_budget_s:
                reason = "agent loop ran out of time"
                break
            response = model.converse(system, messages, tools, timeout_s=settings.llm_loop_turn_timeout_s)
            result.turns += 1
            usage = getattr(response, "usage", None)
            result.input_tokens += getattr(usage, "input_tokens", 0) or 0
            result.output_tokens += getattr(usage, "output_tokens", 0) or 0
            if response.stop_reason in ("refusal", "max_tokens"):
                reason = f"LLM {response.stop_reason}"
                break
            calls = [b for b in response.content if getattr(b, "type", None) == "tool_use"]
            if response.stop_reason != "tool_use" or not calls:
                result.reply = "".join(b.text for b in response.content if getattr(b, "type", None) == "text").strip()
                break
            if len(steps) >= settings.llm_loop_max_steps:
                reason = "agent loop used every step"
                break
            messages.append({"role": "assistant", "content": response.content})
            results = []
            for call in calls:
                agent, _, _, changes = _TOOLS.get(call.name, ("Core", None, set(), False))
                words = str((call.input or {}).get("words", ""))[:300]
                if call.name not in allowed:
                    text, ok = f"{call.name} is not available to you.", False
                else:
                    try:
                        text, ok = _run_tool(call.name, words, core, run_core, me, model), True
                    except CoreError as e:
                        text, ok = f"The app said no: {e.detail}", False
                steps.append(Step(agent=agent, tool=call.name, said=words, result=text, ok=ok, changes=changes))
                results.append({"type": "tool_result", "tool_use_id": call.id, "content": text,
                                **({} if ok else {"is_error": True})})
            messages.append({"role": "user", "content": results})
    except Exception as e:  # timeout, network, bad key, rate limit: the keyword router answers instead
        reason = f"LLM error: {type(e).__name__}"[:120]

    facts = [message] + [s.result for s in steps]
    if result.reply and not numbers_ok(result.reply, facts):
        reason, result.reply = "number check failed", ""
    if not result.reply and steps:
        # Claude's own words didn't hold up (or it stopped early): say what the agents found, in their words
        result.reply = "\n\n".join(dict.fromkeys(s.result for s in steps if s.ok)) or steps[-1].result
        result.llm_used = False
    result.fallback_reason = reason
    summary = result.reply + "\n\nSteps: " + "; ".join(f"{s.agent}.{s.tool}" for s in steps)
    if not result.reply:
        core.finish_run(run_id, False, f"No answer ({reason})", settings.llm_model, False, reason,
                        _ms(started), result.input_tokens, result.output_tokens)
        return None
    core.finish_run(run_id, True, summary, settings.llm_model, result.llm_used, reason, _ms(started),
                    result.input_tokens, result.output_tokens)
    return result


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
