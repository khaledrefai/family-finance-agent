"""Family rules from plain words, the Gatekeeper's rule answers and the Ways to save words: fake core, fake Claude."""

import pytest

from app.core_client import Member
from app.gatekeeper import handle as gatekeeper_handle
from app.graph import Agent, orchestrator
from app.llm import Llm
from app.rules import handle as rules_handle, is_rule_text, parse_rule
from app.ways_to_save import write as write_ways
from tests.conftest import ADAM, LAYLA, MEMBERS, FakeCore
from tests.test_planner import FakeAnthropic, llm_on, me

CHILDREN = [Member.model_validate(m) for m in MEMBERS if m["role"] == "CHILD"]


@pytest.mark.parametrize("words, kind, child, extra", [
    ("No peanuts for Adam", "BLOCK_ALLERGEN", ADAM, {"allergen": "PEANUT"}),
    ("Adam is allergic to nuts", "BLOCK_ALLERGEN", ADAM, {"allergen": "TREE_NUT"}),
    ("Anything over AED 100 needs me", "APPROVAL_LIMIT", None, {"limit": "100"}),
    ("anything above 75 dirhams, ask me first", "APPROVAL_LIMIT", None, {"limit": "75"}),
    ("No energy drinks for the kids", "BLOCK_CATEGORY", None, {"category": "SUGARY_DRINKS"}),
    ("no robux", "BLOCK_CATEGORY", None, {"category": "IN_GAME_PURCHASES"}),
    ("Layla: snacks up to AED 50 a month", "CATEGORY_LIMIT", LAYLA, {"category": "SNACKS", "limit": "50", "weekly": False}),
    ("Layla can spend at most 30 dirhams a week eating out", "CATEGORY_LIMIT", LAYLA,
     {"category": "EATING_OUT", "limit": "30", "weekly": True}),
    ("Adam spends at most AED 200 a month", "MONTHLY_CAP", ADAM, {"limit": "200"}),
])
def test_the_fallback_parser_types_the_common_rules(words, kind, child, extra):
    d = parse_rule(words, CHILDREN)
    assert d is not None, words
    assert d.kind.value == kind
    assert (str(d.forChild) if d.forChild else None) == child
    for k, v in extra.items():
        got = getattr(d, k)
        assert (got.value if hasattr(got, "value") else got) == v
    assert d.said == words and d.draftedBy == "RULES"


def test_words_that_are_not_a_rule_give_nothing():
    assert parse_rule("Layla did great this week", CHILDREN) is None
    assert parse_rule("no idea what to cook", CHILDREN) is None


def test_rule_text_is_a_statement_not_a_question():
    assert is_rule_text("No peanuts for Adam")
    assert is_rule_text("anything over AED 100 needs me")
    assert not is_rule_text("What's the limit on my Visa?")
    assert not is_rule_text("make our plan for this month")


def test_a_parents_rule_goes_to_the_gatekeeper_and_a_childs_no_does_not():
    assert orchestrator.invoke({"message": "No peanuts for Adam", "role": "PARENT"})["agent"] == Agent.GATEKEEPER
    assert orchestrator.invoke({"message": "Anything over AED 100 needs me", "role": "PARENT"})["agent"] == Agent.GATEKEEPER


def test_without_claude_the_parser_drafts_the_rule_in_an_agent_run():
    core = FakeCore("Omar")
    reply = rules_handle(core.client(), me(core), "No peanuts for Adam", Llm(FakeAnthropic(error=RuntimeError())))
    assert core.rule_drafts == [{"kind": "BLOCK_ALLERGEN", "forChild": ADAM, "allergen": "PEANUT", "weekly": False,
                                 "said": "No peanuts for Adam", "draftedBy": "RULES"}]
    assert core.rule_headers[0].startswith("11111111-")       # recorded under the agent run
    assert "No peanuts for Adam" in reply and "turn it on here" in reply
    assert core.runs[0]["agent"] == "GATEKEEPER" and core.runs[0]["finished"]["llmUsed"] is False


def test_claude_reads_unusual_words_into_a_rule(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    fake = FakeAnthropic({"understood": True, "kind": "CATEGORY_LIMIT", "child": "Layla", "category": "TOYS_AND_HOBBIES",
                          "allergen": "NONE", "amount": "40", "period": "MONTHLY"})
    rules_handle(core.client(), me(core), "Layla shouldn't blow more than 40 a month on toys and stuff", Llm(fake))
    d = core.rule_drafts[0]
    assert (d["kind"], d["forChild"], d["category"], d["limit"], d["draftedBy"]) == \
           ("CATEGORY_LIMIT", LAYLA, "TOYS_AND_HOBBIES", "40", "LLM")
    assert core.runs[0]["finished"]["llmUsed"] is True


def test_an_amount_claude_made_up_is_thrown_away(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    fake = FakeAnthropic({"understood": True, "kind": "APPROVAL_LIMIT", "child": "ALL", "category": "NONE",
                          "allergen": "NONE", "amount": "150", "period": "NONE"})
    rules_handle(core.client(), me(core), "Anything over AED 100 needs me", Llm(fake))
    assert core.rule_drafts[0]["limit"] == "100" and core.rule_drafts[0]["draftedBy"] == "RULES"
    assert core.runs[0]["finished"]["fallbackReason"] == "LLM rule did not check out"


def test_a_child_cannot_write_rules_and_nonsense_gets_examples():
    core = FakeCore("Layla")
    assert "set by your parents" in rules_handle(core.client(), me(core), "no homework rule", Llm(FakeAnthropic(error=RuntimeError())))
    parent = FakeCore("Omar")
    reply = rules_handle(parent.client(), me(parent), "be nice to each other", Llm(FakeAnthropic(error=RuntimeError())))
    assert reply.startswith("I couldn't turn that into a rule") and parent.rule_drafts == []


def test_adam_asking_for_peanut_snacks_hears_the_rule():
    core = FakeCore("Adam")
    core.assess_fixture = "assess_adam_peanut.json"
    reply = gatekeeper_handle(core.client(), me(core), "can I buy peanut butter cups for AED 8?")
    assert "Family rule: No peanuts for Adam" in reply
    assert reply.endswith("That one isn't allowed, so I won't send it to a parent.")
    assert core.assess_bodies[0]["title"].startswith("can I buy peanut butter cups")


def test_the_gatekeeper_sends_the_kind_of_thing_to_the_core():
    core = FakeCore("Adam")
    gatekeeper_handle(core.client(), me(core), "can I buy a cola for 3 dirhams?")
    assert core.assess_bodies[0]["category"] == "SUGARY_DRINKS"


def test_ways_to_save_keeps_claudes_words_only_with_the_engines_numbers(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    fake = FakeAnthropic({"tips": {
        "outings": "Outings have averaged AED 1,520 a month, AED 320 over budget. Sticking to it frees about AED 3,840 a year.",
        "subscriptions": "Streaming bundle costs AED 59 a month. Cancel it and keep AED 1,000 a year.",   # 1,000 is made up
        "rent": "Rent is 29% of the income you plan on. Worth comparing before the contract renews.",
    }})
    card = write_ways(core.client(), me(core), Llm(fake))
    by = {w["key"]: w for w in core.tip_words}
    assert by["outings"]["writtenBy"] == "LLM" and by["rent"]["writtenBy"] == "LLM"
    assert by["subscriptions"]["writtenBy"] == "RULES" and "AED 708" in by["subscriptions"]["text"]
    assert card.needsWords is False
    assert core.runs[0]["agent"] == "PLANNER" and "number check failed on 1 tip" in core.runs[0]["finished"]["fallbackReason"]


def test_ways_to_save_is_written_once_a_month():
    core = FakeCore("Sara")
    core.ways = {**core.ways, "needsWords": False}
    fake = FakeAnthropic({"tips": {}})
    write_ways(core.client(), me(core), Llm(fake))
    assert fake.calls == [] and core.runs == [] and core.tip_words == []


def test_ways_to_save_without_claude_saves_the_engines_sentences():
    core = FakeCore("Omar")
    card = write_ways(core.client(), me(core), Llm(FakeAnthropic(error=RuntimeError())))
    assert {w["writtenBy"] for w in core.tip_words} == {"RULES"}
    assert [t.text for t in card.tips][0].startswith("Entertainment averaged AED 1,520")

