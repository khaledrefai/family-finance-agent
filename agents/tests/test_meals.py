"""The Planner's dinners step: words into busy nights and wishes (or Claude's pick), the core's week back as words."""

from fastapi.testclient import TestClient

from app.graph import Agent, orchestrator
from app.llm import Llm, numbers_ok
from app.main import app
from app.meals import is_meals_question, plan, read_delivery_days, read_wishes
from tests.conftest import MEMBERS, FakeCore, load
from tests.test_planner import FakeAnthropic, llm_on, me

OFF = Llm(FakeAnthropic(error=RuntimeError()))
BOARD = load("meals_board.json")


def test_dinner_words_are_recognised():
    assert is_meals_question("plan this week's dinners")
    assert is_meals_question("what should we cook this week?")
    assert is_meals_question("make the shopping list")
    assert not is_meals_question("plan cheaper outings for next month")
    assert not is_meals_question("where did Layla's money go?")


def test_busy_nights_are_read_as_dates_in_the_core_week():
    # The core's week in the fixture runs Tue 27 Oct to Mon 2 Nov
    assert read_delivery_days("Tuesday and Thursday are busy", BOARD["days"]) == ["2026-10-27", "2026-10-29"]
    assert read_delivery_days("we'll order in on Fri", BOARD["days"]) == ["2026-10-30"]
    assert read_delivery_days("Thursday we want pasta", BOARD["days"]) == []   # a day, but nothing about delivery


def test_wishes_pick_saved_recipes_that_suit_everyone():
    keys = read_wishes("the kids want pasta and something with fish", BOARD)
    suits = {r["key"] for r in BOARD["recipes"] if r["suits"]}
    assert keys and set(keys) <= suits
    assert "creamy-chicken-pasta" not in keys   # Layla doesn't like mushrooms
    assert read_wishes("anything is fine", BOARD) == []


def test_the_engine_plans_the_week_and_the_reply_says_only_the_cores_numbers():
    core = FakeCore("Omar")
    p, reply = plan(core.client(), me(core), "plan dinners this week, Wednesday and Friday are busy", OFF)

    assert core.meal_plans == [{"keys": [], "deliveryDays": ["2026-10-28", "2026-10-30"]}]
    assert core.meal_headers == ["11111111-1111-1111-1111-000000000001"]
    assert reply.startswith("This week's dinners: Tue Spaghetti bolognese, Wed delivery (Falafel and hummus box),")
    assert "Shopping list: 19 items, AED 201.50 at our saved prices." in reply
    assert "That is AED 143.50 under the AED 345 for dinners" in reply
    assert reply.endswith("Check the halal mark and pack labels when you shop.")
    assert "halal" not in reply.replace("halal mark", "")   # never says a product is halal
    assert numbers_ok(reply, p["facts"])
    assert core.runs[0]["agent"] == "PLANNER" and core.runs[0]["finished"]["status"] == "SUCCEEDED"
    assert core.runs[0]["finished"]["llmUsed"] is False


def test_claude_picks_from_the_recipes_that_suit_and_the_core_checks(monkeypatch):
    llm_on(monkeypatch)
    fake = FakeAnthropic({"understood": True, "keys": ["bolognese", "satay-noodles", "made-up"],
                          "deliveryDays": ["2026-10-31", "1999-01-01"]})
    core = FakeCore("Sara")
    plan(core.client(), me(core), "Saturday is a party so order in; the kids love bolognese", Llm(fake))

    # satay-noodles doesn't suit Adam, so Claude never saw it and its pick of it is dropped
    assert core.meal_plans == [{"keys": ["bolognese"], "deliveryDays": ["2026-10-31"]}]
    prompt = fake.calls[0]["messages"][0]["content"]
    assert "bolognese: Spaghetti bolognese" in prompt and "satay-noodles" not in prompt
    assert "AED" not in prompt   # Claude is never given prices to add up
    assert core.runs[0]["finished"]["llmUsed"] is True


def test_busy_nights_from_the_app_beat_the_words():
    core = FakeCore("Omar")
    plan(core.client(), me(core), "dinners, Tuesday is busy", OFF, delivery_days=["2026-11-01", "2030-01-01"])
    assert core.meal_plans[0]["deliveryDays"] == ["2026-11-01"]


def test_a_child_is_told_dinners_are_a_parents_job():
    core = FakeCore("Adam")
    p, reply = plan(core.client(), me(core), "what's for dinner this week?", OFF)
    assert p is None and "parents" in reply
    assert core.meal_plans == []


def test_chat_routes_dinners_to_the_planner():
    core = FakeCore("Omar")
    out = orchestrator.invoke({"message": "what should we cook this week?", "role": "PARENT"},
                              config={"configurable": {"core": core.client(), "me": me(core), "llm": OFF}})
    assert out["agent"] == Agent.PLANNER
    assert out["reply"].startswith("This week's dinners:")


def test_the_meals_endpoint(fake_core):
    fake_core.who = next(m for m in MEMBERS if m["displayName"] == "Omar")
    client = TestClient(app)
    resp = client.post("/meals", json={"text": "plan dinners", "deliveryDays": []}, headers={"Authorization": "Bearer t"})
    assert resp.status_code == 200
    assert resp.json()["plan"]["nights"]
    assert resp.json()["reply"].startswith("This week's dinners:")
