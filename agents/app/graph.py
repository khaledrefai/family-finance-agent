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
Later weeks replace route_message() with an LLM classifier and fill in each agent with tools
that call the core API (core_client.py). Agents never calculate money themselves.
"""

import re
from enum import Enum
from typing import TypedDict

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, START, StateGraph

from . import bookkeeper, documents, gatekeeper, planner
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
    (Agent.SHOPPER, ("compare", "cheaper", "meal", "recipe", "shopping list", "groceries", "warranty")),
    (Agent.COACH, ("save", "saving", "challenge", "points", "lesson", "goal", "reward")),
    (Agent.GATEKEEPER, ("buy", "can i", "want", "noon", "amazon", "allerg", "peanut", "money for")),
]

# Shown until each agent is built. No week numbers: these lines can appear in the demo video.
_PLACEHOLDER = {
    Agent.BOOKKEEPER: "Upload a receipt or a statement in Money, Uploads. I'll read it and show you a draft to confirm.",
    Agent.GATEKEEPER: "Tell me what it costs, like \"can I buy headphones for AED 300?\", and I'll check it.",
    Agent.SHOPPER: "I can't compare prices or plan meals yet. That's coming soon.",
    Agent.COACH: "Savings challenges and lessons aren't ready yet. They're coming soon.",
    Agent.PLANNER: "Long-term what-ifs, like gold or renting against buying, are coming soon. Ask me to make this month's plan.",
}


# A child asking for something ("can I buy...") goes to the Gatekeeper even if it has a price in it
_ASKING = re.compile(r"\b(can i|could i|may i|i want|i wanna|please|buy me)\b", re.I)


def is_spend_log(state: ChatState) -> bool:
    text = state.get("message", "")
    return state.get("role") == "CHILD" and looks_like_spend(text) and not _ASKING.search(text)


def route_message(state: ChatState) -> ChatState:
    text = state.get("message", "").lower()
    if bookkeeper.is_spend_question(text) or is_spend_log(state):
        return {"agent": Agent.BOOKKEEPER}
    if gatekeeper.amount_in(text) and _ASKING.search(text) or "afford" in text:
        return {"agent": Agent.GATEKEEPER}
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
    reply = gatekeeper.handle(core, me, state.get("message", "")) if core is not None and me is not None else None
    return {"reply": reply or _PLACEHOLDER[Agent.GATEKEEPER]}


def planner_node(state: ChatState, config: RunnableConfig) -> ChatState:
    ctx = config.get("configurable", {})
    core, me = ctx.get("core"), ctx.get("me")
    message = state.get("message", "")
    if core is not None and me is not None and planner.is_plan_question(message):
        return {"reply": planner.handle(core, me, message, ctx.get("llm"))}
    return {"reply": _PLACEHOLDER[Agent.PLANNER]}


_NODES = {Agent.BOOKKEEPER: bookkeeper_node, Agent.GATEKEEPER: gatekeeper_node, Agent.PLANNER: planner_node}


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
