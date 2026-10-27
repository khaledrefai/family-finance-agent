import json
import re
from datetime import date

import pytest
from fastapi.testclient import TestClient

from app.bookkeeper import children_asked, month_asked
from app.core_client import LoggingStatus, Member, SpendCategory, SpendSummary
from app.main import app
from app.spend_parser import parse_spend
from app.spend_story import nudge, parent_story
from tests.conftest import ADAM, LAYLA, MEMBERS, FakeCore, load

TODAY = date(2026, 10, 23)  # the day the fixtures were captured from the real core
TOKEN = {"Authorization": "Bearer x"}


@pytest.mark.parametrize("message, expected", [
    ("12 dirhams shawarma", [("12", "Shawarma", SpendCategory.EATING_OUT)]),
    ("shawarma 12", [("12", "Shawarma", SpendCategory.EATING_OUT)]),
    ("AED 7.50 chicken wrap at the canteen", [("7.50", "Chicken wrap", SpendCategory.SCHOOL_CANTEEN)]),
    ("sandwich and juice for 9", [("9", "Sandwich and juice", SpendCategory.SNACKS)]),
    ("12 shawarma and juice", [("12", "Shawarma and juice", SpendCategory.EATING_OUT)]),
    ("spent 5 on gum and 3 on a cola", [("5", "Gum", SpendCategory.SNACKS), ("3", "Cola", SpendCategory.SUGARY_DRINKS)]),
    ("pokemon cards 15, slime 12", [("15", "Pokemon cards", SpendCategory.TOYS_AND_HOBBIES),
                                    ("12", "Slime", SpendCategory.TOYS_AND_HOBBIES)]),
    ("juice 3,50 at school canteen", [("3.50", "Juice", SpendCategory.SCHOOL_CANTEEN)]),
    ("I bought a Robux gift card for 20", [("20", "Robux gift card", SpendCategory.IN_GAME_PURCHASES)]),
])
def test_parses_what_kids_type(message, expected):
    parsed = parse_spend(message, TODAY)
    assert [(i.amount, i.description, i.category) for i in parsed.spend.items] == expected
    assert parsed.spend.loggedText == message


def test_amount_stays_as_typed_text_for_the_core_to_convert():
    item = parse_spend("AED 7.50 wrap", TODAY).spend.items[0]
    assert item.amount == "7.50" and isinstance(item.amount, str)


def test_dates():
    assert parse_spend("cola 3 yesterday", TODAY).spend.occurredOn == date(2026, 10, 22)
    assert parse_spend("on monday a notebook for 6", TODAY).spend.occurredOn == date(2026, 10, 19)
    assert parse_spend("cola 3", TODAY).spend.occurredOn is None  # the core uses today in the UAE


def test_no_amount_means_we_ask():
    parsed = parse_spend("I bought shawarma", TODAY)
    assert parsed.spend is None and parsed.missing_amount_for == "Shawarma"


def test_parent_question_parsing():
    family = [Member.model_validate(m) for m in MEMBERS]
    assert [m.displayName for m in children_asked("where did Layla's money go?", family)] == ["Layla"]
    assert [m.displayName for m in children_asked("where did the kids' money go?", family)] == ["Layla", "Adam"]
    assert month_asked("where did it go this month", TODAY) is None
    assert month_asked("and last month?", TODAY) == "2026-09"
    assert month_asked("may I see Adam's spending", TODAY) is None


def _numbers(text: str) -> set[str]:
    return set(re.findall(r"\d+(?:\.\d+)?", text))


@pytest.mark.parametrize("fixture", ["summary_layla.json", "summary_adam.json"])
def test_story_only_uses_numbers_the_core_sent(fixture):
    """Agents never do arithmetic: every number in the story must appear somewhere in the core's summary."""
    raw = load(fixture)
    story = parent_story(SpendSummary.model_validate(raw), TODAY)
    allowed = _numbers(json.dumps(raw))
    assert _numbers(story) <= allowed, _numbers(story) - allowed


def test_layla_story_reads_right():
    story = parent_story(SpendSummary.model_validate(load("summary_layla.json")), TODAY)
    assert "school canteen" in story
    assert "headphones" in story
    assert "she " not in story.lower() and " he " not in story.lower()  # no guessed pronouns


def test_adam_story_mentions_rule_breaks():
    story = parent_story(SpendSummary.model_validate(load("summary_adam.json")), TODAY)
    assert "Robux gift card" in story and "No in-game purchases" in story


def test_nothing_logged():
    raw = load("summary_layla.json") | {"purchaseCount": 0, "categories": [], "topItems": [], "lastLoggedOn": None}
    assert "hasn't logged any spending" in parent_story(SpendSummary.model_validate(raw), TODAY)


def test_nudge_only_when_nothing_logged_today():
    status = {"today": "2026-10-22", "schoolDay": True, "loggedToday": False,
              "todayTotal": {"minor": 0, "currency": "AED", "text": "AED 0"}}
    assert "lunch" in nudge(LoggingStatus.model_validate(status))
    assert nudge(LoggingStatus.model_validate(status | {"loggedToday": True})) is None


# ---------------------------------------------------------------- through the HTTP API with a fake core

def test_child_logs_spend_by_chat(fake_core: FakeCore):
    resp = TestClient(app).post("/chat", json={"message": "12 dirhams shawarma"}, headers=TOKEN)
    assert resp.json()["agent"] == "bookkeeper"
    assert fake_core.posted == [{"items": [{"amount": "12", "description": "Shawarma", "category": "EATING_OUT"}],
                                 "loggedText": "12 dirhams shawarma"}]
    assert resp.json()["reply"].startswith("Got it: shawarma AED 12 (eating out).")


def test_child_asking_to_buy_still_goes_to_gatekeeper(fake_core: FakeCore):
    resp = TestClient(app).post("/chat", json={"message": "can I buy headphones for 300?"}, headers=TOKEN)
    assert resp.json()["agent"] == "gatekeeper"
    assert fake_core.posted == []


def test_parent_asks_where_the_money_went(fake_core: FakeCore):
    fake_core.who = MEMBERS[1]  # Omar
    resp = TestClient(app).post("/chat", json={"message": "Where did Layla's money go this month?"}, headers=TOKEN)
    assert resp.json()["agent"] == "bookkeeper"
    assert fake_core.summary_calls == [{"memberId": LAYLA}]
    assert "Layla has spent" in resp.json()["reply"]


def test_parent_asks_about_both_kids(fake_core: FakeCore):
    fake_core.who = MEMBERS[0]  # Sara
    resp = TestClient(app).post("/chat", json={"message": "where did the money go last month?"}, headers=TOKEN)
    assert [c["memberId"] for c in fake_core.summary_calls] == [LAYLA, ADAM]
    assert all(c["month"] == month_asked("last month", date.today()) for c in fake_core.summary_calls)


def test_nudge_endpoint(fake_core: FakeCore):
    assert "lunch" in TestClient(app).get("/nudge", headers=TOKEN).json()["nudge"]
    fake_core.who = MEMBERS[1]
    assert TestClient(app).get("/nudge", headers=TOKEN).json()["nudge"] is None


def test_story_leads_with_two_sentences_then_the_detail():
    story = parent_story(SpendSummary.model_validate(load("summary_layla.json")), TODAY)
    lead = story.split("\n\n")[0]
    assert lead.count(". ") + 1 == 2 and "headphones" in lead
    adam = parent_story(SpendSummary.model_validate(load("summary_adam.json")), TODAY).split("\n\n")[0]
    assert adam.split(". ", 1)[1].startswith("Worth a chat")


def test_a_parent_said_yes_is_not_part_of_what_was_bought():
    parsed = parse_spend("Dad said yes I can buy Robux, 20 dirhams", TODAY)
    assert [(i.amount, i.description) for i in parsed.spend.items] == [("20", "Robux")]


def test_kid_saying_a_parent_said_yes_is_asked_to_send_it_through_the_app(fake_core: FakeCore):
    resp = TestClient(app).post("/chat", json={"message": "Dad said I can have a cola"}, headers=TOKEN)
    assert "I want something" in resp.json()["reply"] and "family rule" not in resp.json()["reply"]
    assert fake_core.posted == []
