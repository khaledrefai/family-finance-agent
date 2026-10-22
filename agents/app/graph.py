"""The orchestrator: a LangGraph graph that routes each message to one of the five agents.

Week 1 is a skeleton:
  * routing uses simple keywords so it works without an LLM key and is easy to test;
  * each agent node only says what it will do and in which week it arrives.
Later weeks replace route_message() with an LLM classifier and fill in each agent with tools
that call the core API (core_client.py). Agents never calculate money themselves.
"""

from enum import Enum
from typing import TypedDict

from langgraph.graph import END, START, StateGraph


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

_PLACEHOLDER = {
    Agent.BOOKKEEPER: "Bookkeeper: I'll sort receipts and statements and catch duplicates (arrives week 3).",
    Agent.GATEKEEPER: "Gatekeeper: I'll check this against funds, upcoming bills and family rules, then ask a parent (arrives week 4).",
    Agent.SHOPPER: "Shopper: I'll compare options and plan meals within budget (arrives weeks 6-7).",
    Agent.COACH: "Coach: I'll set up savings challenges, lessons and points (arrives week 5).",
    Agent.PLANNER: "Planner: I'll run neutral 1, 5 and 10 year scenarios. Information only, not advice (arrives week 8).",
}


def route_message(state: ChatState) -> ChatState:
    text = state.get("message", "").lower()
    for agent, words in _KEYWORDS:
        if any(w in text for w in words):
            return {"agent": agent}
    return {"agent": Agent.GATEKEEPER}


def _agent_node(agent: Agent):
    def run(state: ChatState) -> ChatState:
        return {"reply": _PLACEHOLDER[agent]}
    return run


def build_graph():
    graph = StateGraph(ChatState)
    graph.add_node("router", route_message)
    for agent in Agent:
        graph.add_node(agent.value, _agent_node(agent))
        graph.add_edge(agent.value, END)
    graph.add_edge(START, "router")
    graph.add_conditional_edges("router", lambda s: s["agent"].value, {a.value: a.value for a in Agent})
    return graph.compile()


orchestrator = build_graph()
