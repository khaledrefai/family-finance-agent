"""The monthly family summary's words: fake core (the demo family's real page), fake Claude."""

from fastapi.testclient import TestClient

from app.core_client import CoreError
from app.llm import Llm
from app.main import app
from app.summary import pieces, write
from tests.conftest import ADAM, LAYLA, FakeCore
from tests.test_planner import FakeAnthropic, llm_on, me

import pytest

KID_LAYLA, KID_ADAM = f"kid:{LAYLA}", f"kid:{ADAM}"


def test_claudes_words_are_kept_only_with_the_numbers_in_their_facts(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    fake = FakeAnthropic({"texts": {
        "headline": "October so far: the summer trip is 30% saved and 3 of 4 budgets are on track.",
        "story": "Groceries, eating out and transport are within their limits. Entertainment is AED 360 over, "
                 "so it is worth a look with 4 days to go.",
        KID_LAYLA: "Layla, your headphones jar is 40% full. Thank you for telling me what you buy.",
        KID_ADAM: "Adam, the family has AED 6,700 to spend.",   # a family figure: not in Adam's facts
    }})
    page = write(core.client(), me(core), None, Llm(fake))

    by = {w["key"]: w for w in core.summary_words}
    assert by["headline"]["writtenBy"] == "LLM" and by["story"]["writtenBy"] == "LLM"
    assert by[KID_LAYLA]["writtenBy"] == "LLM"
    assert by[KID_ADAM]["writtenBy"] == "RULES" and by[KID_ADAM]["text"].startswith("Adam, what a month.")
    assert page["needsWords"] is False
    assert core.runs[0]["agent"] == "PLANNER"
    assert "number check failed on 1 text" in core.runs[0]["finished"]["fallbackReason"]
    # Claude was given each piece's facts, and the kids' facts have no family money in them
    prompt = fake.calls[0]["messages"][0]["content"]
    assert "Entertainment: AED 1,560 of AED 1,200 (130%)" in prompt
    adam_part = prompt.split(f"TEXT {KID_ADAM}")[1]
    assert "1,200" not in adam_part and "Saver of the Month: Adam" in adam_part


def test_nothing_is_written_when_the_words_still_match_the_month():
    core = FakeCore("Omar")
    core.month_summary = {**core.month_summary, "needsWords": False}
    fake = FakeAnthropic({"texts": {}})
    write(core.client(), me(core), "2026-10", Llm(fake))
    assert fake.calls == [] and core.runs == [] and core.summary_words == []
    assert core.summary_months == ["2026-10"]


def test_only_the_pieces_still_in_the_engines_words_are_asked_for(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    core.month_summary["headline"] = {**core.month_summary["headline"], "writtenBy": "LLM"}
    fake = FakeAnthropic({"texts": {"story": "A calm month.", KID_LAYLA: "Well done, Layla.", KID_ADAM: "Nice one, Adam."}})
    write(core.client(), me(core), None, Llm(fake))
    assert {w["key"] for w in core.summary_words} == {"story", KID_LAYLA, KID_ADAM}


def test_without_claude_the_engines_sentences_are_saved():
    core = FakeCore("Omar")
    page = write(core.client(), me(core), None, Llm(FakeAnthropic(error=RuntimeError())))
    assert {w["writtenBy"] for w in core.summary_words} == {"RULES"}
    assert page["headline"]["text"].startswith("October so far: AED 2,587.50 of the AED 6,700 family budgets spent")
    assert len(pieces(page)) == 4


def test_kids_cant_ask_for_the_parents_summary():
    core = FakeCore("Layla")
    with pytest.raises(CoreError):
        write(core.client(), me(core), None, Llm(FakeAnthropic({"texts": {}})))


def test_the_endpoint_returns_the_cores_page(fake_core):
    fake_core.who = {"id": "00000000-0000-0000-0000-0000000000a1", "displayName": "Sara", "role": "PARENT",
                     "ageBand": "ADULT"}
    r = TestClient(app).post("/summary?month=2026-10", headers={"Authorization": "Bearer t"})
    assert r.status_code == 200
    assert r.json()["saver"]["winners"] == ["Adam"]
    assert fake_core.summary_months[0] == "2026-10"
