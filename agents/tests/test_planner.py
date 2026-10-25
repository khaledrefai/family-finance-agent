"""Planner and Gatekeeper against a fake core and a fake Claude: no key, no Java, no database."""

import json
from types import SimpleNamespace

from app import llm as llm_module
from app.core_client import Me
from app.gatekeeper import handle as gatekeeper_handle
from app.graph import Agent, orchestrator
from app.llm import Llm, numbers_ok
from app.planner import handle as planner_handle
from tests.conftest import FakeCore


class FakeAnthropic:
    """Answers like client.beta.messages.create would."""

    def __init__(self, data: dict | None = None, stop_reason: str = "end_turn", error: Exception | None = None):
        self.data, self.stop_reason, self.error = data, stop_reason, error
        self.calls: list[dict] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    def _create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        text = json.dumps(self.data)
        return SimpleNamespace(content=[SimpleNamespace(type="text", text=text)], stop_reason=self.stop_reason,
                               model="test-model", usage=SimpleNamespace(input_tokens=100, output_tokens=50))


def me(core: FakeCore) -> Me:
    return core.client().me()


def llm_on(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")


REASONS = {"COMMITMENTS": "Rent, school fees and the bills.", "EVERYDAY": "Food, transport and pocket money.",
           "GOALS": "The emergency buffer and the summer trip.", "INVEST_ASIDE": "Kept apart for later.",
           "FUN": "Eating out and weekends.", "GIVING": "Charity, if you want it."}


def test_number_check_allows_only_numbers_from_the_facts():
    facts = ["Free until the next pay (Omar salary, 14 Nov): AED 258.", "Spare: AED 5,897.67."]
    assert numbers_ok("You have AED 258 until 14 Nov and AED 5,897.67 spare.", facts)
    assert not numbers_ok("You have AED 300 until pay day.", facts)       # 300 was worked out, or made up
    assert numbers_ok("No numbers at all.", facts)


def test_planner_uses_claude_words_and_saves_a_draft_with_the_engines_shares(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    fake = FakeAnthropic({"summary": "This month AED 12,397.67 is free. March is tight.", "reasons": REASONS})
    reply = planner_handle(core.client(), me(core), "make our plan for this month", Llm(fake))

    assert reply.startswith("This month AED 12,397.67 is free. March is tight.")
    assert "draft" in reply and "Confirm" in reply
    # The shares are the engine's, untouched; Claude only wrote the reasons
    suggestion = json.loads(open("tests/fixtures/bucket_suggestion.json").read())
    sent = core.bucket_drafts[0]["shares"]
    assert [s["shareBp"] for s in sent] == [s["shareBp"] for s in suggestion]
    assert sent[0]["reason"] == REASONS["COMMITMENTS"]
    # Every plan call ran under the agent run, and the run says Claude answered
    assert core.runs[0]["agent"] == "PLANNER"
    assert all(h for h in core.run_headers)
    assert core.runs[0]["finished"]["llmUsed"] is True
    # Fallbacks are on and the answer is structured JSON
    call = fake.calls[0]
    assert call["fallbacks"] == "default" and call["betas"] == ["server-side-fallback-2026-07-01"]
    assert call["output_config"]["format"]["type"] == "json_schema"


def test_planner_falls_back_when_claude_makes_up_a_number(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    fake = FakeAnthropic({"summary": "You can spend AED 999 more.", "reasons": REASONS})
    reply = planner_handle(core.client(), me(core), "make our plan", Llm(fake))
    assert "999" not in reply
    assert reply.startswith("This month AED 12,397.67 is free after commitments")
    assert core.runs[0]["finished"]["fallbackReason"] == "number check failed"
    assert core.runs[0]["finished"]["llmUsed"] is False


def test_planner_falls_back_when_claude_is_off_or_down(monkeypatch):
    core = FakeCore("Sara")
    monkeypatch.setattr(llm_module.settings, "llm", "off")
    reply = planner_handle(core.client(), me(core), "make our plan", Llm(FakeAnthropic(REASONS)))
    assert "is free after commitments" in reply
    assert core.runs[0]["finished"]["fallbackReason"] == "LLM off"

    llm_on(monkeypatch)
    core = FakeCore("Sara")
    reply = planner_handle(core.client(), me(core), "make our plan", Llm(FakeAnthropic(error=TimeoutError())))
    assert "is free after commitments" in reply
    assert core.runs[0]["finished"]["fallbackReason"] == "LLM error: TimeoutError"


def test_planner_adds_the_goal_options_when_a_goal_is_named(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "off")
    core = FakeCore("Sara")
    reply = planner_handle(core.client(), me(core), "how should we save for the summer trip?", Llm(FakeAnthropic()))
    assert "For Summer trip, the steady pace is AED 950 a month, ready by June 2027." in reply


def test_children_get_no_family_plan():
    core = FakeCore("Layla")
    reply = planner_handle(core.client(), me(core), "make a plan", Llm(FakeAnthropic()))
    assert "parents" in reply
    assert core.runs == []


def test_gatekeeper_says_the_cores_facts_for_a_child():
    core = FakeCore("Layla")
    reply = gatekeeper_handle(core.client(), me(core), "can I buy headphones for AED 300?")
    assert reply.startswith("It costs AED 300. Layla's wallet has AED 388")
    assert reply.endswith("I'll need a parent to say yes.")
    assert core.runs[0]["agent"] == "GATEKEEPER"


def test_router_sends_plans_to_the_planner_and_prices_to_the_gatekeeper():
    assert orchestrator.invoke({"message": "make our plan for this month", "role": "PARENT"})["agent"] == Agent.PLANNER
    assert orchestrator.invoke({"message": "can I buy a game for AED 60?", "role": "CHILD"})["agent"] == Agent.GATEKEEPER
