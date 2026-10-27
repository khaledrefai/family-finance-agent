"""The orchestrator: a LangGraph graph that routes each message to one of the five agents.

Week 1 is a skeleton:
  * routing uses simple keywords so it works without an LLM key and is easy to test;
  * each agent node only says what it will do and in which week it arrives.
Spend-logging spike: the Bookkeeper really works for two things, a child saying what they bought
("12 dirhams shawarma") and a parent asking where a child's money went (see bookkeeper.py).
Week 2: the Planner makes this month's plan and a draft bucket split for a parent (planner.py, the first
step that uses Claude, with a rule-based fallback), and the Gatekeeper answers "can I afford AED X?" with
the core's affordability check (gatekeeper.py).
Week 3: the Bookkeeper reads uploaded receipts and statements (documents.py, called from Money > Uploads)
and answers "any duplicate charges?" from the core's findings.
Week 4: the Gatekeeper turns a parent's plain words into a family rule draft (rules.py) and checks a child's
"can I buy...?" against the family rules too; the Planner writes the monthly Ways to save card (ways_to_save.py,
called from the parent's Today screen).
Week 6: the Shopper compares prices (shopper.py): it reads what someone wants from their words into a PurchaseIntent
and the core searches the shops, ranks the offers by payable total and checks the best one like any request.
Week 7: the Planner plans cheaper family outings (outings.py): it reads the month and the kind of outing, may pick
from the core's list, and the core prices them for the family and checks them against the entertainment budget.
Dinners (2026-10-26): the Planner plans the week's dinners (meals.py): it reads the busy nights and wishes, may pick
from the core's saved recipes that suit everyone, and the core prices the shopping list and checks the grocery money.
Savings calculator: the Planner reads a what-if ("AED 1,000 a month at 6% for 15 years") into the core's calculator
ask (calculator.py) and repeats the core's 1, 5 and 10 year figures. Learning only, no product is ever named.
Later weeks replace route_message() with an LLM classifier and fill in each agent with tools
that call the core API (core_client.py). Agents never calculate money themselves.
"""

import re
from enum import Enum
from typing import TypedDict

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, START, StateGraph

from . import bookkeeper, calculator, documents, gatekeeper, meals, outings, planner, rules, shopper
from .spend_parser import looks_like_spend


class Agent(str, Enum):
    BOOKKEEPER = "bookkeeper"  # documents, categories, duplicates, commitments, unusual spending
    GATEKEEPER = "gatekeeper"  # spending requests, ask-to-buy, rules and allergens, approvals
    SHOPPER = "shopper"        # better purchases, meal plan, shopping list
    COACH = "coach"            # kids' challenges, lessons, points, family savings plan
    PLANNER = "planner"        # this month's plan, money buckets, goal paces; later long-term what-ifs


class ChatState(TypedDict, total=False):
    message: str
    role: str          # PARENT or CHILD, from the core API's /api/me
    agent: Agent
    reply: str


# Checked in order; first match wins. Gatekeeper is the default because "can I buy / have money"
# is the most common message from a child.
_KEYWORDS: list[tuple[Agent, tuple[str, ...]]] = [
    (Agent.BOOKKEEPER, ("receipt", "invoice", "statement", "upload", "duplicate", "subscription", "bill", "charge")),
    (Agent.PLANNER, ("gold", "apartment", "rent or buy", "mortgage", "insurance", "invest", "10 years", "5 years")),
    (Agent.SHOPPER, ("compare", "cheaper", "warranty")),
    (Agent.COACH, ("save", "saving", "challenge", "points", "lesson", "goal", "reward")),
    (Agent.GATEKEEPER, ("buy", "can i", "want", "noon", "amazon", "allerg", "peanut", "money for")),
]

# Shown until each agent is built. No week numbers: these lines can appear in the demo video.
_PLACEHOLDER = {
    Agent.BOOKKEEPER: "Upload a receipt or a statement in Money, Uploads. I'll read it and show you a draft to confirm.",
    Agent.GATEKEEPER: "Tell me what it costs, like \"can I buy headphones for AED 300?\", and I'll check it. "
                      "Parents can also write a family rule, like \"no peanuts for Adam\".",
    Agent.SHOPPER: "Tell me what you'd like to buy, like \"wireless headphones\" or \"Sony WH-CH720N\", "
                   "and I'll compare the shops' prices.",
    Agent.COACH: "Savings challenges and lessons aren't ready yet. They're coming soon.",
    Agent.PLANNER: "Ask me to make this month's plan, or try a what-if like \"AED 1,000 a month at 5% for 10 years\".",
}


# A child asking for something ("can I buy...") goes to the Gatekeeper even if it has a price in it
_ASKING = re.compile(r"\b(can i|could i|may i|i want|i wanna|please|buy me)\b", re.I)


def is_spend_log(state: ChatState) -> bool:
    text = state.get("message", "")
    return (state.get("role") == "CHILD" and looks_like_spend(text) and not _ASKING.search(text)
            and not calculator.is_calculator_question(text))


def route_message(state: ChatState) -> ChatState:
    text = state.get("message", "").lower()
    if bookkeeper.is_spend_question(text) or is_spend_log(state):
        return {"agent": Agent.BOOKKEEPER}
    if state.get("role") == "PARENT" and rules.is_rule_text(text):
        return {"agent": Agent.GATEKEEPER}
    if gatekeeper.amount_in(text) and _ASKING.search(text) or "afford" in text:
        return {"agent": Agent.GATEKEEPER}
    if meals.is_meals_question(text) or outings.is_outings_question(text) or calculator.is_calculator_question(text):
        return {"agent": Agent.PLANNER}
    if shopper.is_price_question(text):
        return {"agent": Agent.SHOPPER}
    if state.get("role") == "PARENT" and planner.is_plan_question(text):
        return {"agent": Agent.PLANNER}
    for agent, words in _KEYWORDS:
        if any(w in text for w in words):
            return {"agent": agent}
    return {"agent": Agent.GATEKEEPER}


def _agent_node(agent: Agent):
    def run(state: ChatState) -> ChatState:
        return {"reply": _PLACEHOLDER[agent]}
    return run


_FINDINGS = re.compile(r"\b(duplicate|double|charged twice|subscriptions?|odd|unusual|found)\b", re.I)


def bookkeeper_node(state: ChatState, config: RunnableConfig) -> ChatState:
    ctx = config.get("configurable", {})
    core, me = ctx.get("core"), ctx.get("me")
    message = state.get("message", "")
    if core is not None and me is not None and (bookkeeper.is_spend_question(message) or is_spend_log(state)):
        return {"reply": bookkeeper.handle(core, me, message)}
    if core is not None and me is not None and me.role.value == "PARENT" and _FINDINGS.search(message):
        return {"reply": documents.answer_findings(core)}
    return {"reply": _PLACEHOLDER[Agent.BOOKKEEPER]}


def gatekeeper_node(state: ChatState, config: RunnableConfig) -> ChatState:
    ctx = config.get("configurable", {})
    core, me = ctx.get("core"), ctx.get("me")
    message = state.get("message", "")
    if core is not None and me is not None and me.role.value == "PARENT" and rules.is_rule_text(message):
        return {"reply": rules.handle(core, me, message, ctx.get("llm"))}
    reply = gatekeeper.handle(core, me, message) if core is not None and me is not None else None
    if reply is None and core is not None and me is not None and shopper.is_want(message):
        # "I want wireless headphones" has no price yet: find the shops' prices first
        reply = shopper.handle(core, me, message, ctx.get("llm"))
    return {"reply": reply or _PLACEHOLDER[Agent.GATEKEEPER]}


def planner_node(state: ChatState, config: RunnableConfig) -> ChatState:
    ctx = config.get("configurable", {})
    core, me = ctx.get("core"), ctx.get("me")
    message = state.get("message", "")
    if core is not None and me is not None and meals.is_meals_question(message):
        return {"reply": meals.handle(core, me, message, ctx.get("llm"))}
    if core is not None and me is not None and outings.is_outings_question(message):
        return {"reply": outings.handle(core, me, message, ctx.get("llm"))}
    if core is not None and me is not None and calculator.is_calculator_question(message):
        return {"reply": calculator.handle(core, me, message)}
    if core is not None and me is not None and planner.is_plan_question(message):
        return {"reply": planner.handle(core, me, message, ctx.get("llm"))}
    return {"reply": _PLACEHOLDER[Agent.PLANNER]}


def shopper_node(state: ChatState, config: RunnableConfig) -> ChatState:
    ctx = config.get("configurable", {})
    core, me = ctx.get("core"), ctx.get("me")
    if core is not None and me is not None:
        return {"reply": shopper.handle(core, me, state.get("message", ""), ctx.get("llm"))}
    return {"reply": _PLACEHOLDER[Agent.SHOPPER]}


_NODES = {Agent.BOOKKEEPER: bookkeeper_node, Agent.GATEKEEPER: gatekeeper_node, Agent.PLANNER: planner_node,
          Agent.SHOPPER: shopper_node}


def build_graph():
    graph = StateGraph(ChatState)
    graph.add_node("router", route_message)
    for agent in Agent:
        graph.add_node(agent.value, _NODES.get(agent) or _agent_node(agent))
        graph.add_edge(agent.value, END)
    graph.add_edge(START, "router")
    graph.add_conditional_edges("router", lambda s: s["agent"].value, {a.value: a.value for a in Agent})
    return graph.compile()


orchestrator = build_graph()
