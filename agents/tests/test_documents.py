"""The Bookkeeper reading receipts and statements, against a fake core and a fake Claude."""

from app.doc_parser import parse
from app.documents import NEEDS_AI, answer_findings, read_document
from app.graph import Agent, orchestrator
from app.llm import Llm
from tests.conftest import FakeCore
from tests.test_planner import FakeAnthropic, llm_on, me

DOC = "22222222-2222-2222-2222-222222222222"

# The demo files the core makes (documents/DemoSamples.java), as they look on 25 Oct 2026
RECEIPT = """CARREFOUR
MIRDIF CITY CENTRE, DUBAI
TRN 100012345600003

Date: 19/10/2026  18:42

Whole chicken x2                44.00
Basmati rice 5kg                39.00
Dishwasher tablets              51.75

TOTAL AED                     641.50
VAT 5% INCLUDED                30.55

PAID BY VISA ************7710
THANK YOU FOR SHOPPING AT CARREFOUR
"""

STATEMENT = """Card statement,Omar's Visa
Card number,**** **** **** 7710
Statement date,2026-10-24
Closing balance,5950.00

Date,Description,Amount
2026-10-10,PAYMENT RECEIVED - THANK YOU,-3200.00
2026-10-11,SPINNEYS JUMEIRAH,189.00
2026-10-11,SPINNEYS JUMEIRAH,189.00
2026-10-16,SHAHID VIP,35.00
"""


def test_rules_read_a_receipt_without_adding_anything_up():
    d = parse(RECEIPT, "text/plain")
    assert d.kind == "RECEIPT"
    assert d.merchant == "Carrefour Mirdif City Centre"
    assert d.date == "19/10/2026"
    assert d.total == "641.50"            # the printed total, as printed
    assert d.cardLast4 == "7710"
    assert [line.amount for line in d.lines] == ["44.00", "39.00", "51.75"]   # VAT and the total are not items
    assert d.readBy == "RULES"


def test_rules_read_a_csv_statement():
    d = parse(STATEMENT, "text/csv", "visa.csv")
    assert d.kind == "CARD_STATEMENT"
    assert d.cardLast4 == "7710"
    assert d.closingBalance == "5950.00"
    assert len(d.lines) == 4
    assert d.lines[0].amount == "-3200.00"      # the core reads the minus as money coming in
    assert d.lines[3].description == "SHAHID VIP"


def test_a_csv_with_debit_and_credit_columns():
    d = parse("Date,Details,Debit,Credit\n14/10/2026,NOON,99.00,\n15/10/2026,REFUND NOON,,99.00\n", "text/csv")
    assert [(line.amount, line.credit) for line in d.lines] == [("99.00", False), ("99.00", True)]


def test_a_text_file_with_nothing_to_read():
    assert parse("hello there", "text/plain") is None


def test_bookkeeper_reads_a_statement_in_an_agent_run_and_leaves_a_draft():
    core = FakeCore("Sara")
    core.file = (STATEMENT.encode(), "text/csv")
    doc, reply = read_document(core.client(), me(core), DOC, Llm(FakeAnthropic(error=RuntimeError("not called"))))
    assert doc.status == "EXTRACTED"
    assert "Confirm" in reply
    assert core.extractions[0]["readBy"] == "RULES"
    assert core.extractions[0]["cardLast4"] == "7710"
    assert all(h.startswith("11111111-") for h in core.doc_headers)   # every call recorded under the run
    assert core.runs[0]["agent"] == "BOOKKEEPER"
    assert core.runs[0]["finished"]["status"] == "SUCCEEDED"
    assert core.runs[0]["finished"]["llmUsed"] is False


def test_a_photo_with_the_ai_off_is_marked_failed_with_a_way_forward(monkeypatch):
    monkeypatch.setattr("app.llm.settings.llm", "off")
    core = FakeCore("Sara")
    core.file = (b"\x89PNG...", "image/png")
    doc, reply = read_document(core.client(), me(core), DOC)
    assert doc.status == "FAILED"
    assert reply == NEEDS_AI
    assert core.failures == [NEEDS_AI]
    assert core.runs[0]["finished"]["status"] == "FAILED"


def test_claude_reads_a_photo_into_the_schema(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    core.file = (b"\xff\xd8 jpeg bytes", "image/jpeg")
    fake = FakeAnthropic({"kind": "RECEIPT", "merchant": "Spinneys", "date": "2026-10-20", "total": "86.25",
                          "cardLast4": "", "closingBalance": "", "category": "GROCERIES",
                          "lines": [{"date": "", "description": "Milk", "amount": "8.25", "credit": False, "category": ""}]})
    doc, _ = read_document(core.client(), me(core), DOC, Llm(fake))
    assert doc.status == "EXTRACTED"
    sent = core.extractions[0]
    assert sent["readBy"] == "LLM" and sent["total"] == "86.25" and "cardLast4" not in sent
    block = fake.calls[0]["messages"][0]["content"][0]
    assert block["type"] == "image" and block["source"]["media_type"] == "image/jpeg"
    assert core.runs[0]["finished"]["llmUsed"] is True


def test_a_receipt_with_no_printed_total_is_not_guessed(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    core.file = (b"%PDF-1.7", "application/pdf")
    fake = FakeAnthropic({"kind": "RECEIPT", "merchant": "Lulu", "date": "", "total": "", "cardLast4": "",
                          "closingBalance": "", "category": "", "lines": []})
    doc, _ = read_document(core.client(), me(core), DOC, Llm(fake))
    assert doc.status == "FAILED" and not core.extractions
    assert fake.calls[0]["messages"][0]["content"][0]["type"] == "document"


def test_claude_reading_text_must_copy_numbers_that_are_printed(monkeypatch):
    llm_on(monkeypatch)
    core = FakeCore("Sara")
    core.file = (b"Lulu Hypermarket\nPaid 120 dirhams for the shopping, thanks", "text/plain")
    made_up = FakeAnthropic({"kind": "RECEIPT", "merchant": "Lulu", "date": "", "total": "125.00", "cardLast4": "",
                             "closingBalance": "", "category": "", "lines": []})
    doc, _ = read_document(core.client(), me(core), DOC, Llm(made_up))
    assert doc.status == "FAILED"
    assert core.runs[0]["finished"]["fallbackReason"] == "number check failed"


def test_children_cannot_upload_documents():
    core = FakeCore("Layla")
    doc, reply = read_document(core.client(), me(core), DOC)
    assert doc is None and "parents" in reply and not core.runs


def test_findings_are_read_back_from_the_core():
    core = FakeCore("Sara")
    assert "Nothing odd" in answer_findings(core.client())
    core.found = [{"key": "dup:x", "kind": "DUPLICATE", "title": "Two charges of AED 189 at Spinneys on 11 Oct.",
                   "detail": "Looks like a double charge.", "amount": {"minor": 18900, "currency": "AED", "text": "AED 189"}}]
    assert answer_findings(core.client()).startswith("• Two charges of AED 189 at Spinneys on 11 Oct.")


def test_a_parent_asking_about_duplicates_gets_the_findings():
    core = FakeCore("Sara")
    result = orchestrator.invoke({"message": "any duplicate charges this month?", "role": "PARENT"},
                                 config={"configurable": {"core": core.client(), "me": me(core)}})
    assert result["agent"] is Agent.BOOKKEEPER
    assert "Nothing odd" in result["reply"]


def test_the_read_endpoint(fake_core):
    from fastapi.testclient import TestClient
    from app.main import app
    fake_core.who = {"id": "00000000-0000-0000-0000-0000000000a1", "displayName": "Sara", "role": "PARENT", "ageBand": "ADULT"}
    fake_core.file = (STATEMENT.encode(), "text/csv")
    r = TestClient(app).post(f"/documents/{DOC}/read", headers={"Authorization": "Bearer t"})
    assert r.status_code == 200
    assert r.json()["status"] == "EXTRACTED"
