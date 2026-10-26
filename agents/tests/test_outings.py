"""The Planner's outings step: words into a month and kinds (or Claude's pick), the core's plan back as words."""

import pytest
from fastapi.testclient import TestClient

from app.graph import Agent, orchestrator
from app.llm import Llm, numbers_ok
from app.main import app
from app.outings import handle, is_outings_question, plan, read_kinds, read_when
from tests.conftest import FakeCore
from tests.test_planner import FakeAnthropic, llm_on, me

OFF = Llm(FakeAnthropic(error=RuntimeError()))


def test_outing_words_are_recognised():
    assert is_outings_question("plan cheaper outings for next month")
    assert is_outings_question("any things to do this weekend that are free?")
    assert is_outings_question("ideas for a day out with the kids")
    assert not is_outings_question("where did Layla's money go?")
    assert not is_outings_question("compare prices for an air fryer")


@pytest.mark.parametrize("words, when, kinds", [
    ("something free this weekend", "THIS_MONTH", ["FREE"]),
    ("indoor ideas for next month, the kids love animals", "NEXT_MONTH", ["INDOOR", "ANIMALS"]),
    ("cheaper outings please", None, []),
    ("a beach or a park, it's too hot for the desert", None, ["INDOOR", "NATURE", "BEACH"]),
])
def test_the_rule_based_reader_finds_the_month_and_the_kinds(words, when, kinds):
    assert read_when(words) == when
    assert read_kinds(words) == kinds


def test_over_budget_this_month_plans_next_month_and_adds_free_ideas_for_now():
    core = FakeCore("Omar")
    p, reply = plan(core.client(), me(core), "plan cheaper outings", OFF)

    # The engine picks: next month's plan, then free ones for the rest of this month, both in the agent run
    assert [b["when"] for b in core.outing_plans] == ["NEXT_MONTH", "THIS_MONTH"]
    assert core.outing_plans[0] == {"when": "NEXT_MONTH", "kinds": [], "keys": []}
    assert core.outing_headers == ["11111111-1111-1111-1111-000000000001"] * 2
    assert p["monthLabel"] == "November"
    assert reply.startswith("For November: Dubai Safari Park, Dubai Frame, Kite Beach and Dubai Fountain show.")
    assert "Together: AED 340 for 4 outings, leaving AED 860 of the AED 1,200." in reply
    assert "this plan costs AED 1,156 less" in reply
    assert "For the rest of October, free ones: Kite Beach, Dubai Fountain show and Al Qudra Lakes picnic." in reply
    assert reply.endswith("check each one on its site before booking.")
    # Every number said is the core's
    assert numbers_ok(reply, p["facts"] + ["October"])
    assert core.runs[0]["agent"] == "PLANNER" and core.runs[0]["finished"]["status"] == "SUCCEEDED"
    assert core.runs[0]["finished"]["llmUsed"] is False


def test_this_weekend_free_asks_the_core_for_free_outings_this_month():
    core = FakeCore("Sara")
    _, reply = plan(core.client(), me(core), "something free this weekend", OFF)
    assert core.outing_plans == [{"when": "THIS_MONTH", "kinds": ["FREE"], "keys": []}]
    assert reply.startswith("For October: Kite Beach, Dubai Fountain show and Al Qudra Lakes picnic.")
    assert "Nothing is left for outings in October" in reply


def test_claude_picks_from_the_core_list_only_and_the_core_checks_it(monkeypatch):
    llm_on(monkeypatch)
    fake = FakeAnthropic({"understood": True, "when": "NEXT_MONTH", "keys": ["safari-park", "made-up-zoo", "kite-beach"]})
    core = FakeCore("Omar")
    p, reply = plan(core.client(), me(core), "the kids love animals, and a beach day", Llm(fake))

    assert core.outing_plans[0] == {"when": "NEXT_MONTH", "kinds": [], "keys": ["safari-park", "kite-beach"]}
    assert [x["key"] for x in p["picks"]] == ["safari-park", "kite-beach"]
    assert reply.startswith("For November: Dubai Safari Park and Kite Beach.")
    # Claude saw the list with what is closed; it was never asked to add anything up
    prompt = fake.calls[0]["messages"][0]["content"]
    assert "safari-park: Dubai Safari Park" in prompt and "miracle-garden" in prompt
    assert core.runs[0]["finished"]["llmUsed"] is True


def test_a_month_from_the_app_beats_the_words():
    core = FakeCore("Omar")
    plan(core.client(), me(core), "cheaper outings this weekend", OFF, when="NEXT_MONTH")
    assert [b["when"] for b in core.outing_plans] == ["NEXT_MONTH"]


def test_kids_are_sent_to_their_parents_and_the_core_is_not_asked():
    core = FakeCore("Layla")
    reply = handle(core.client(), me(core), "what can we do this weekend? things to do")
    assert "job for your parents" in reply
    assert core.outing_plans == [] and core.runs == []


def test_the_orchestrator_routes_outings_to_the_planner_before_the_monthly_plan():
    core = FakeCore("Omar")
    result = orchestrator.invoke({"message": "plan cheaper outings for next month", "role": "PARENT"},
                                 config={"configurable": {"core": core.client(), "me": me(core), "llm": OFF}})
    assert result["agent"] is Agent.PLANNER
    assert result["reply"].startswith("For November:")


def test_the_outings_endpoint(fake_core):
    fake_core.who = {"id": "00000000-0000-0000-0000-0000000000a1", "displayName": "Sara", "role": "PARENT", "ageBand": "ADULT"}
    client = TestClient(app)
    res = client.post("/outings", json={"text": "", "when": "THIS_MONTH"}, headers={"Authorization": "Bearer t"})
    assert res.status_code == 200
    body = res.json()
    assert body["plan"]["monthLabel"] == "October"
    assert body["reply"].startswith("For October:")
