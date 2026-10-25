"""The Shopper: words into a PurchaseIntent, the core's comparison back as words. Fake core, fake Claude."""

import pytest
from fastapi.testclient import TestClient

from app.graph import Agent, orchestrator
from app.llm import Llm
from app.main import app
from app.shopper import handle, is_price_question, is_want, parse_intent, read_intent
from tests.conftest import FakeCore
from tests.test_planner import FakeAnthropic, llm_on, me


@pytest.mark.parametrize("words, query, brand, model", [
    ("is the Sony WH-CH720N cheaper anywhere?", "Sony WH-CH720N", "Sony", "WH-CH720N"),
    ("I want wireless headphones", "wireless headphones", None, None),
    ("can I buy headphones for AED 300?", "headphones", None, None),
    ("compare prices for an air fryer", "air fryer", None, None),
    ("how much is the iPhone 16 128GB", "iPhone 16 128GB", None, None),     # 128GB is a size, 16 stays in the words
    ("Where can I buy JBL Tune 520BT on noon?", "JBL Tune 520BT", "JBL", "520BT"),
    ("find me a LEGO set for 150 dirhams", "LEGO set", "LEGO", None),
])
def test_the_rule_based_reader_finds_the_thing_its_brand_and_model(words, query, brand, model):
    i = parse_intent(words)
    assert (i.query, i.brand, i.model) == (query, brand, model)
    assert i.said == words


def test_price_words_and_wants_are_told_apart():
    assert is_price_question("where can I buy a Switch game cheapest?")
    assert is_price_question("how much is an air fryer")
    assert not is_price_question("I want headphones")
    assert is_want("I want headphones")
    assert not is_want("where did Layla's money go?")


def test_claude_reads_the_words_but_cannot_invent_a_model(monkeypatch):
    llm_on(monkeypatch)
    fake = FakeAnthropic({"understood": True, "query": "noise cancelling headphones", "brand": "Sony",
                          "model": "WH-1000XM5", "category": "NONE"})
    intent, result = read_intent("the Sony noise cancelling ones", Llm(fake))
    assert intent.query == "noise cancelling headphones"
    assert intent.brand == "Sony"
    assert intent.model is None                       # never written by the person
    assert result.used


def test_without_claude_the_reader_answers_and_the_core_compares_in_an_agent_run():
    core = FakeCore("Layla")
    reply = handle(core.client(), me(core), "is the Sony WH-CH720N cheaper anywhere?", Llm(FakeAnthropic(error=RuntimeError())))

    assert core.compare_bodies == [{"intent": {"query": "Sony WH-CH720N", "brand": "Sony", "model": "WH-CH720N",
                                               "said": "is the Sony WH-CH720N cheaper anywhere?"}}]
    assert core.compare_headers == ["11111111-1111-1111-1111-000000000001"]
    # Only the core's facts and numbers
    assert reply.startswith("I compared 3 offers from 3 shops")
    assert "Best re-checked total: AED 289 at Amazon.ae" in reply
    assert reply.endswith("Pick one on the Ask screen and I'll send it to a parent.")
    assert core.runs[0]["agent"] == "SHOPPER" and core.runs[0]["finished"]["status"] == "SUCCEEDED"


def test_a_blocked_thing_is_not_sent_to_a_parent():
    core = FakeCore("Adam")
    core.compare_fixture = "compare_peanut.json"
    reply = handle(core.client(), me(core), "can I get peanut butter cups?", Llm(FakeAnthropic(error=RuntimeError())))
    assert "No peanuts for Adam" in reply
    assert reply.endswith("That one isn't allowed, so I won't send it to a parent.")


def test_price_questions_and_wants_without_a_price_go_to_the_shopper():
    assert orchestrator.invoke({"message": "compare prices for an air fryer", "role": "PARENT"})["agent"] == Agent.SHOPPER
    assert orchestrator.invoke({"message": "is the JBL Tune 520BT cheaper on noon?", "role": "CHILD"})["agent"] == Agent.SHOPPER
    # With a price it is still the Gatekeeper's "can I afford it?"
    assert orchestrator.invoke({"message": "can I buy headphones for AED 300?", "role": "CHILD"})["agent"] == Agent.GATEKEEPER


def test_a_want_without_a_price_gets_the_shops_prices_in_chat(fake_core):
    client = TestClient(app)
    r = client.post("/chat", json={"message": "I want wireless headphones"}, headers={"Authorization": "Bearer t0ken"})
    assert r.status_code == 200
    assert r.json()["reply"].startswith("I compared 3 offers")
    assert fake_core.compare_bodies[0]["intent"]["query"] == "wireless headphones"


def test_the_prices_endpoint_returns_the_intent_the_comparison_and_the_words(fake_core):
    client = TestClient(app)
    r = client.post("/prices", json={"text": "Sony WH-CH720N"}, headers={"Authorization": "Bearer t0ken"})
    assert r.status_code == 200
    body = r.json()
    assert body["intent"]["model"] == "WH-CH720N"
    assert body["comparison"]["best"]["store"] == "Amazon.ae"
    assert "AED 289" in body["reply"]
