"""The orchestrator: a LangGraph graph that routes each message to one of the five agents.

Week 1 is a skeleton:
  * routing uses simple keywords so it works without an LLM key and is easy to test;
  * each agent node only says what it will do and in which week it arrives.
Spend-logging spike: the Bookkeeper really works for two things, a child saying what they bought
("12 dirhams shawarma") and a parent asking where a child's money went (see bookkeeper.py).
Later weeks replace route_message() with an LLM classifier and fill in each agent with tools
that call the core API (core_client.py). Agents never calculate money themselves.
"""

import re
from enum import Enum
from typing import TypedDict

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, START, StateGraph

from . import bookkeeper
from .spend_parser import looks_like_spend


class Agent(str, Enum):
    BOOKKEEPER = "bookkeeper"  # documents, categories, duplicates, commitments, unusual spending
    GATEKEEPER = "gatekeeper"  # spending requests, ask-to-buy, rules and allergens, approvals
    SHOPPER = "shopper"        # better purchases, meal plan, shopping list
    COACH = "coach"            # kids' challenges, lessons, points, family savings plan
    PLANNER = "planner"        # long-term calculators, insurance guide (information only)


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
    Agent.BOOKKEEPER: "I can't sort receipts and statements yet. That's coming soon, along with spotting duplicate charges.",
    Agent.GATEKEEPER: "I can't check requests yet. Soon I'll look at the family's money, upcoming bills and rules, then ask a parent.",
    Agent.SHOPPER: "I can't compare prices or plan meals yet. That's coming soon.",
    Agent.COACH: "Savings challenges and lessons aren't ready yet. They're coming soon.",
    Agent.PLANNER: "Planning isn't ready yet. When it is, it will show 1, 5 and 10 year what-ifs. Information only, not financial advice.",
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
    for agent, words in _KEYWORDS:
        if any(w in text for w in words):
            return {"agent": agent}
    return {"agent": Agent.GATEKEEPER}


def _agent_node(agent: Agent):
    def run(state: ChatState) -> ChatState:
        return {"reply": _PLACEHOLDER[agent]}
    return run


def bookkeeper_node(state: ChatState, config: RunnableConfig) -> ChatState:
    ctx = config.get("configurable", {})
    core, me = ctx.get("core"), ctx.get("me")
    message = state.get("message", "")
    if core is not None and me is not None and (bookkeeper.is_spend_question(message) or is_spend_log(state)):
        return {"reply": bookkeeper.handle(core, me, message)}
    return {"reply": _PLACEHOLDER[Agent.BOOKKEEPER]}


def build_graph():
    graph = StateGraph(ChatState)
    graph.add_node("router", route_message)
    for agent in Agent:
        graph.add_node(agent.value, bookkeeper_node if agent is Agent.BOOKKEEPER else _agent_node(agent))
        graph.add_edge(agent.value, END)
    graph.add_edge(START, "router")
    graph.add_conditional_edges("router", lambda s: s["agent"].value, {a.value: a.value for a in Agent})
    return graph.compile()


orchestrator = build_graph()
