"""The parent's quick money questions in Ask, with Claude off: the rule-based router must send each to the Planner,
and the Planner must answer only with the core's numbers (cash flow, goals, one-off effect, gold, wealth)."""

import json

import pytest
from fastapi.testclient import TestClient

from app import llm as llm_module
from app.graph import Agent, orchestrator
from app.llm import numbers_ok
from app.main import app, core_for_caller
from tests.conftest import FIXTURES, FakeCore

BONUS = "I got a AED 5,000 bonus"   # one of the Ask page's own suggestion chips
FREE = "How much is free until pay day?"
GOLD = "How much gold do we have?"


@pytest.fixture(autouse=True)
def llm_off(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "off")


@pytest.fixture
def omar():
    core = FakeCore("Omar")
    app.dependency_overrides[core_for_caller] = core.client
    yield core
    app.dependency_overrides.clear()


def ask(text: str) -> dict:
    resp = TestClient(app).post("/chat", json={"message": text}, headers={"Authorization": "Bearer x"})
    assert resp.status_code == 200
    return resp.json()


def core_texts(*names: str) -> list[str]:
    """Every string in the core's answers: the only places a number in a reply may come from."""
    out: list[str] = []

    def walk(x):
        if isinstance(x, dict):
            for v in x.values():
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)
        elif isinstance(x, str):
            out.append(x)
    for n in names:
        walk(json.loads((FIXTURES / n).read_text()))
    return out


def route(text: str, role: str = "PARENT") -> Agent:
    return orchestrator.invoke({"message": text, "role": role})["agent"]


@pytest.mark.parametrize("text", [
    BONUS, "We got a bonus of 3,000 dirhams", "Sara's commission came in, AED 2,000 extra income",
    FREE, "What's left until payday?", "how much can we spend before the salary?",
    GOLD, "What is our gold worth?", "How many grams of gold do we own?",
    "What's our net worth?",
])
def test_parent_money_questions_go_to_the_planner(text):
    assert route(text) == Agent.PLANNER


def test_buying_and_affording_still_go_to_the_gatekeeper():
    assert route("Can we afford a AED 1,200 weekend away?") == Agent.GATEKEEPER
    assert route("Can I buy headphones for AED 300 with my bonus?") == Agent.GATEKEEPER
    assert route("How much is a PS5?") == Agent.SHOPPER


def test_bonus_is_extra_income_not_a_purchase(omar):
    out = ask(BONUS)
    reply = out["reply"]
    assert out["agent"] == "planner"
    # Not checked as a purchase, and no price search
    assert "/api/requests/assess" not in omar.paths and "/api/pricing/compare" not in omar.paths
    assert "costs" not in reply and "doesn't fit" not in reply
    # What is free now, from the core's cash flow
    assert "AED 258 is free until Omar salary on 14 Nov" in reply
    assert "AED 5,897.67 is spare this month" in reply
    # Where it could go: the buffer first, then the goals, with the core's one-off effect
    assert omar.one_off_amounts == ["5,000"]
    assert reply.index("Emergency buffer") < reply.index("Summer trip") < reply.index("Home deposit")
    assert "AED 15,000 still to go" in reply
    assert "full in December 2026 instead of January 2027" in reply
    assert "full in January 2027 instead of April 2027" in reply
    assert "You decide where it goes" in reply
    # No arithmetic: every number is one the core sent
    assert numbers_ok(reply, core_texts("cashflow.json", "goals.json", "one_off_effect_5000.json"))
    # Recorded as a Planner run that did not use Claude
    assert omar.runs[-1]["agent"] == "PLANNER"
    assert omar.runs[-1]["finished"]["status"] == "SUCCEEDED"
    assert omar.runs[-1]["finished"]["llmUsed"] is False


def test_free_until_pay_day_comes_from_the_cash_flow(omar):
    out = ask(FREE)
    reply = out["reply"]
    assert out["agent"] == "planner"
    assert "/api/pricing/compare" not in omar.paths
    assert "couldn't find" not in reply
    assert reply.startswith("AED 258 is free until Omar salary arrives on 14 Nov.")
    assert "• In current accounts and cash today: AED 12,300" in reply
    assert "• Everyday budget for 19 days: -AED 4,683" in reply
    assert "Next bill: DEWA electricity and water, AED 900, on 26 Oct." in reply
    assert numbers_ok(reply, core_texts("cashflow.json"))


def test_gold_total_comes_from_the_core(omar):
    out = ask(GOLD)
    reply = out["reply"]
    assert out["agent"] == "planner"
    assert "/api/gold" in omar.paths
    assert "Ask me to make this month's plan" not in reply   # not the generic help line
    assert reply.startswith("You have 40 g of 24K and 10 g of 22K. At today's price it is worth AED 15,765. "
                            "You paid AED 15,400 for it.")
    assert "AED 320 a gram for 24K" in reply
    assert "Home deposit, 40 g (AED 12,800)" in reply
    assert "making charges" in reply     # the bangle is jewellery
    assert "sample" in reply             # the demo price is the sample one
    # Records gold, never suggests it
    assert "buy" not in reply.lower().replace("you paid", "")
    assert numbers_ok(reply, core_texts("gold.json"))


def test_wealth_comes_from_the_core(omar):
    reply = ask("What's our net worth?")["reply"]
    assert reply.startswith("About AED 14,965 in all: AED 60,665 you own, less AED 45,700 you owe.")
    assert "• Car loan left: -AED 38,400" in reply
    assert "Everything you own" not in reply   # the totals are said once, in the first line
    assert numbers_ok(reply, core_texts("wealth.json"))


def test_a_child_asking_about_gold_gets_no_family_numbers():
    core = FakeCore("Layla")
    app.dependency_overrides[core_for_caller] = core.client
    try:
        out = ask(GOLD)
    finally:
        app.dependency_overrides.clear()
    assert out["agent"] == "planner"
    assert "your parents' plan" in out["reply"]
    assert "/api/gold" not in core.paths
