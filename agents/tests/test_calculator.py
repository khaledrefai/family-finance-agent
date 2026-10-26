"""The Planner's savings calculator step: a what-if in words into the core's ask, the core's figures back as words."""

from app.calculator import handle, is_calculator_question, read_ask
from app.graph import Agent, route_message
from app.llm import numbers_ok
from tests.conftest import FakeCore
from tests.test_planner import me

HOME = "00000000-0000-0000-0000-0000000003f3"


def test_what_if_words_are_recognised():
    assert is_calculator_question("what if we put AED 1,000 a month at 6% for 15 years?")
    assert is_calculator_question("how much could our investing money grow in 10 years")
    assert is_calculator_question("open the savings calculator")
    assert not is_calculator_question("plan cheaper outings for next month")
    assert not is_calculator_question("can I buy headphones for AED 300?")


def test_the_words_become_the_core_ask_and_nothing_is_worked_out_here():
    assert read_ask("what if we put AED 1,000 a month at 6% for 15 years, starting with 5,000?") == {
        "monthly": "1,000", "start": "5,000", "rate": "6", "years": 15}
    assert read_ask("how much could it grow in 10 years at 4.5 percent") == {"rate": "4.5", "years": 10}


def test_a_parent_gets_the_core_figures_in_an_agent_run():
    core = FakeCore("Omar")
    reply = handle(core.client(), me(core), "what if we put AED 1,000 a month at 6% for 15 years?")
    assert core.calculator_bodies == [{"monthly": "1,000", "rate": "6", "years": 15}]
    assert core.run_headers[-1] == "11111111-1111-1111-1111-000000000001"
    assert "At an example 6% a year it comes to AED 290,819" in reply
    assert "In 10 years: AED 163,879 (AED 120,000 put in)" in reply
    assert "not a forecast" in reply
    assert core.runs[0]["finished"]["status"] == "SUCCEEDED"
    # Every number said is the core's (or the parent's own)
    fixture = __import__("tests.conftest", fromlist=["load"]).load("calculator_1000_6pc.json")
    p = fixture["projection"]
    facts = [p["summary"]] + [f"{m['year']} {m['total']['text']} {m['putIn']['text']}" for m in p["marks"]]
    assert numbers_ok(reply, facts + ["what if we put AED 1,000 a month at 6% for 15 years?"])


def test_naming_a_goal_starts_from_it_and_no_amount_starts_from_the_investing_bucket():
    core = FakeCore("Omar")
    handle(core.client(), me(core), "how long to the home deposit at 5%, over 10 years?")
    assert core.calculator_bodies[-1]["goalId"] == HOME
    handle(core.client(), me(core), "how much could our investing grow in 10 years?")
    assert core.calculator_bodies[-1] == {"years": 10, "from": "INVEST_ASIDE"}


def test_children_are_routed_but_not_shown_the_calculator():
    core = FakeCore("Layla")
    assert route_message({"message": "what if I invest 50 a month for 10 years", "role": "CHILD"})["agent"] is Agent.PLANNER
    assert "for your parents" in handle(core.client(), me(core), "what if I invest 50 a month for 10 years")
    assert core.calculator_bodies == []
