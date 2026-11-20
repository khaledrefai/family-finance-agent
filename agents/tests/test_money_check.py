"""The weekly money check: the Planner decides what matters this week and proposes it for a parent to approve."""

from fastapi.testclient import TestClient

from app import llm as llm_module
from app import money_check
from app.llm import Llm
from app.main import app

from .conftest import LAYLA, FakeCore
from .test_planner import FakeAnthropic


def sara(core: FakeCore):
    return core.client().me()


def test_with_the_ai_off_the_biggest_concern_is_proposed():
    core = FakeCore("Sara")
    out = money_check.check(core.client(), sara(core), "SCHEDULE", Llm(FakeAnthropic(error=RuntimeError())))
    assert [p["kind"] for p in core.proposals] == ["BUDGET"]
    assert "Entertainment" in core.proposals[0]["body"]
    assert core.proposals[0]["link"] == "/parent/money/budgets"
    assert core.runs[-1]["triggerKind"] == "SCHEDULE"
    assert core.proposal_headers[0].startswith("11111111")   # made inside the agent run
    assert out["aiChose"] is False


def test_claude_picks_a_nudge_for_a_child_from_her_own_facts(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Sara")
    facts = money_check.gather(core.client())
    layla = next(f for f in facts if f.child and str(f.child.id) == LAYLA)
    over = next(f for f in facts if f.topic == "BUDGET")
    fake = FakeAnthropic({"summary": "One budget is over; Layla is doing well.", "proposals": [
        {"kind": "BUDGET", "fact_ids": [over.id], "title": "Slow down on fun", "body": over.text,
         "child": "", "child_message": ""},
        {"kind": "NUDGE", "fact_ids": [layla.id], "title": "Well done, Layla", "body": "Tell Layla she is on track.",
         "child": "Layla", "child_message": "Nice work keeping track of your money!"}]})
    out = money_check.check(core.client(), sara(core), "MESSAGE", Llm(fake))
    nudge = core.proposals[1]
    assert nudge["kind"] == "NUDGE" and nudge["childId"] == LAYLA
    assert nudge["why"] == [layla.text]
    assert out["aiChose"] is True


def test_a_made_up_number_falls_back_to_the_rules(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Sara")
    facts = money_check.gather(core.client())
    fake = FakeAnthropic({"summary": "", "proposals": [
        {"kind": "NOTE", "fact_ids": [facts[0].id], "title": "Save AED 777", "body": "Put AED 777 aside.",
         "child": "", "child_message": ""}]})
    money_check.check(core.client(), sara(core), "MESSAGE", Llm(fake))
    assert all("777" not in p["title"] for p in core.proposals)
    assert core.runs[-1]["finished"]["fallbackReason"] == "LLM proposals did not check out"


def test_a_nudge_using_a_siblings_facts_is_refused(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Sara")
    facts = money_check.gather(core.client())
    adam = next(f for f in facts if f.child and f.child.displayName == "Adam")
    fake = FakeAnthropic({"summary": "", "proposals": [
        {"kind": "NUDGE", "fact_ids": [adam.id], "title": "Hi Layla", "body": "x", "child": "Layla",
         "child_message": "Look at what Adam did"}]})
    money_check.check(core.client(), sara(core), "MESSAGE", Llm(fake))
    assert not any(p["kind"] == "NUDGE" for p in core.proposals)


def test_running_it_twice_does_not_repeat_a_waiting_suggestion():
    core = FakeCore("Sara")
    off = Llm(FakeAnthropic(error=RuntimeError()))
    money_check.check(core.client(), sara(core), "MESSAGE", off)
    money_check.check(core.client(), sara(core), "MESSAGE", off)
    assert len(core.proposals) == 1


def test_a_child_cannot_run_the_check(fake_core):
    res = TestClient(app).post("/check", json={}, headers={"Authorization": "Bearer x"})
    assert res.status_code == 403
