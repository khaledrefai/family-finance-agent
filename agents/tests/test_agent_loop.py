"""The orchestrator's tool loop: Claude picks the steps, the tools do the work, and every number is checked."""

from types import SimpleNamespace

from fastapi.testclient import TestClient

from app import agent_loop
from app import llm as llm_module
from app.llm import Llm
from app.main import app

from .conftest import MEMBERS, FakeCore

AUTH = {"Authorization": "Bearer x"}


def tool_use(id_: str, name: str, words: str | None = None):
    return SimpleNamespace(type="tool_use", id=id_, name=name, input={} if words is None else {"words": words})


def text(t: str):
    return SimpleNamespace(type="text", text=t)


class ScriptedClaude:
    """Answers like client.messages.create would, one scripted turn at a time, and keeps what it was sent."""

    def __init__(self, *turns, error: Exception | None = None):
        self.turns, self.error = list(turns), error
        self.calls: list[dict] = []
        self.messages = SimpleNamespace(create=self._create)

    def _create(self, **kwargs):
        self.calls.append({**kwargs, "messages": list(kwargs["messages"])})
        if self.error:
            raise self.error
        content = self.turns.pop(0)
        stop = "tool_use" if any(b.type == "tool_use" for b in content) else "end_turn"
        return SimpleNamespace(content=content, stop_reason=stop, model="test-model",
                               usage=SimpleNamespace(input_tokens=200, output_tokens=40))


def llm_on(monkeypatch, fake: ScriptedClaude):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    monkeypatch.setattr(agent_loop, "default_llm", Llm(fake))


def test_with_the_ai_off_the_keyword_router_answers_and_says_so(fake_core):
    res = TestClient(app).post("/chat", json={"message": "can I buy a toy?"}, headers=AUTH).json()
    assert res["planned"] is False
    assert res["steps"][0]["agent"] == "Router"
    assert not any(r["agent"] == "ORCHESTRATOR" for r in fake_core.runs)


def test_claude_chooses_two_tools_and_answers_with_their_numbers(monkeypatch, fake_core):
    fake_core.who = next(m for m in MEMBERS if m["displayName"] == "Sara")
    fake = ScriptedClaude(
        [text("Let me look."), tool_use("t1", "budgets_this_month"),
         tool_use("t2", "spending_story", "Where did Layla's money go this month?")],
        [text("Entertainment is AED 240 over with 8 days left. Layla spent AED 180 of her AED 300.")],
    )
    llm_on(monkeypatch, fake)
    res = TestClient(app).post("/chat", json={"message": "How are we doing, and where did Layla's money go?"},
                               headers=AUTH).json()

    assert res["planned"] is True
    assert res["reply"].startswith("Entertainment is AED 240 over")
    assert [(s["agent"], s["tool"]) for s in res["steps"]] == [("Core", "budgets_this_month"),
                                                               ("Bookkeeper", "spending_story")]
    # Both tool results went back to Claude in one message, before its final answer
    sent = fake.calls[1]["messages"][-1]["content"]
    assert [r["tool_use_id"] for r in sent] == ["t1", "t2"]
    assert "Entertainment" in sent[0]["content"]
    # The run is audited: the orchestrator's own run, with both turns' tokens
    orchestrator = next(r for r in fake_core.runs if r["agent"] == "ORCHESTRATOR")
    assert orchestrator["finished"]["llmUsed"] is True
    assert orchestrator["finished"]["inputTokens"] == 400
    assert "budgets_this_month" in orchestrator["finished"]["outputSummary"]


def test_a_number_claude_worked_out_is_thrown_away(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Sara")
    fake = ScriptedClaude([tool_use("t1", "budgets_this_month")],
                          [text("You have AED 999 left over.")])  # 999 is in no tool result
    result = agent_loop.run(core.client(), core.client().me(), "How are the budgets?", Llm(fake))
    assert "AED 999" not in result.reply
    assert result.reply.startswith("October 2026, 8 days left")   # the tool's own words instead
    assert result.llm_used is False
    assert result.fallback_reason == "number check failed"


def test_claude_failing_hands_back_to_the_keyword_router(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Sara")
    assert agent_loop.run(core.client(), core.client().me(), "How are the budgets?",
                          Llm(ScriptedClaude(error=TimeoutError()))) is None
    assert core.runs[-1]["finished"]["status"] == "FAILED"


def test_a_child_cannot_use_a_parent_tool(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Layla")
    fake = ScriptedClaude([tool_use("t1", "budgets_this_month")], [text("I can only help with your own money.")])
    result = agent_loop.run(core.client(), core.client().me(), "How much do mum and dad have?", Llm(fake))
    assert result.steps[0].ok is False
    assert "not available" in result.steps[0].result
    assert "budgets_this_month" not in {t["name"] for t in fake.calls[0]["tools"]}


def test_dad_said_yes_keeps_its_fixed_answer(monkeypatch):
    monkeypatch.setattr(llm_module.settings, "llm", "on")
    core = FakeCore("Adam")
    fake = ScriptedClaude(error=AssertionError("Claude must not be asked"))
    assert agent_loop.run(core.client(), core.client().me(), "Dad said yes to the juice", Llm(fake)) is None
