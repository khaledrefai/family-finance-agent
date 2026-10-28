"""Bookkeeper agent, documents part (week 3): read an uploaded receipt or statement into a typed draft.

What it does when a parent uploads a file:
  1. Opens an agent run, so the core records every call ("What the agent did").
  2. Fetches the file from the core with the parent's own token.
  3. Reads it:
       * a CSV statement export or a plain-text receipt: the rules in doc_parser.py (exact, free, no LLM);
       * a photo or a PDF: Claude reads it into the Extraction schema (structured output).
     Claude only copies what is printed. It never adds anything up or works out a total.
  4. Sends the draft to the core, which converts every amount, matches the account and each line, and decides
     what is new, what is already in the family's spending and what looks charged twice.
  5. The document waits for a parent's Confirm in the app. The agent can never confirm it.
If nothing can read the file (a photo while Claude is off), the core is told it failed, with a reason a parent
can act on.
"""

import base64

from .core_client import CoreClient, CoreError, Document, DocLine, Extraction, Me, Role
from .doc_parser import parse
from .llm import Llm, LlmResult, llm as default_llm, numbers_in
from .settings import settings

CATEGORIES = ["GROCERIES", "EATING_OUT", "ENTERTAINMENT", "TRANSPORT", "SHOPPING", "HOTEL", "HEALTH", "HOME", "OTHER"]

SYSTEM = """You read receipts and bank or card statements for a UAE family finance app.
Copy exactly what is printed. Amounts are text exactly as printed, like "189.00" or "1,250.50".
Never add, subtract or calculate anything, and never invent a total: if the total is not printed, leave it empty.
Dates as YYYY-MM-DD. UAE documents print the day first (14/10/2026 is 14 October).
For a statement, list every payment line. Mark money coming in (a refund, a payment to the card) with credit true.
cardLast4 is the last 4 digits of the card or account number if printed, else empty. Use "" for anything not printed."""

_LINE = {
    "type": "object",
    "properties": {
        "date": {"type": "string"},
        "description": {"type": "string"},
        "amount": {"type": "string"},
        "credit": {"type": "boolean"},
        "category": {"type": "string", "enum": CATEGORIES + [""]},
    },
    "required": ["date", "description", "amount", "credit", "category"],
    "additionalProperties": False,
}

SCHEMA = {
    "type": "object",
    "properties": {
        "kind": {"type": "string", "enum": ["RECEIPT", "INVOICE", "BANK_STATEMENT", "CARD_STATEMENT"]},
        "merchant": {"type": "string", "description": "the shop, for a receipt"},
        "date": {"type": "string"},
        "total": {"type": "string", "description": "the printed total, for a receipt"},
        "cardLast4": {"type": "string"},
        "closingBalance": {"type": "string", "description": "for a statement, if printed"},
        "category": {"type": "string", "enum": CATEGORIES + [""], "description": "for a receipt"},
        "lines": {"type": "array", "items": _LINE, "description": "receipt items, or statement payment lines"},
    },
    "required": ["kind", "merchant", "date", "total", "cardLast4", "closingBalance", "category", "lines"],
    "additionalProperties": False,
}

NEEDS_AI = ("I can't read photos and PDFs while the AI is switched off. Upload the bank's CSV export instead, "
            "or type the payment in with Add spending.")
WRONG_TYPE = "I can't read this kind of file. Upload a JPEG or PNG photo, a PDF or the bank's CSV export."
NOT_READ = "I couldn't read it this time. Press Read it again, or type the payment in with Add spending."
NOTHING_FOUND = "I couldn't find a total or any payment lines in this file. Check it's a receipt or a statement."


def _why_not(content_type: str, result: LlmResult) -> str:
    """What the parent sees when no draft came back: only "AI switched off" when it really is off."""
    if result.used or content_type.startswith("text/"):
        return NOTHING_FOUND
    if result.fallback_reason == "LLM off":
        return NEEDS_AI
    if result.fallback_reason == "unsupported file type":
        return WRONG_TYPE
    return NOT_READ  # a timeout, a network error, a refusal or an answer cut off


def read_document(core: CoreClient, me: Me, doc_id, model: Llm | None = None) -> tuple[Document | None, str]:
    """Reads one uploaded document into a draft. Returns the core's view of it and a reply for the parent."""
    if me.role is not Role.PARENT:
        return None, "Receipts and statements are for your parents to upload."
    model = model or default_llm
    run_id = core.open_run("BOOKKEEPER", f"Read uploaded document {doc_id}")
    run = core.in_run(run_id)
    result = LlmResult(None, used=False, fallback_reason="not needed")
    try:
        doc = run.document(doc_id)
        data, content_type = run.document_file(doc_id)
        draft, result = _read(data, content_type, doc.fileName, doc.kind, model)
        if draft is None:
            reason = _why_not(content_type, result)
            doc = run.mark_failed(doc_id, reason)
            reply = reason
            ok = False
        else:
            try:
                doc = run.save_extraction(doc_id, draft)
                reply = doc.summary + " Nothing counts until you check it and press Confirm."
                ok = True
            except CoreError as e:
                if e.status != 400:
                    raise
                doc = run.mark_failed(doc_id, e.detail)
                reply, ok = e.detail, False
        core.finish_run(run_id, ok, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return doc, reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return None, f"I couldn't read that. {e.detail}."


def _read(data: bytes, content_type: str, file_name: str, uploaded_kind: str, model: Llm):
    """Rules first for text files; Claude for photos, PDFs and text the rules can't follow."""
    if content_type.startswith("text/") or content_type == "application/vnd.ms-excel":
        text = data.decode("utf-8", errors="replace")
        draft = parse(text, content_type, file_name)
        if draft is not None:
            return draft, LlmResult(None, used=False, fallback_reason="rules read a text file")
        result = model.ask(SYSTEM, _question(uploaded_kind) + "\n\nDOCUMENT:\n" + text[:20000], SCHEMA, max_tokens=4000,
                           timeout_s=settings.llm_read_timeout_s)
        draft = _draft(result)
        # Everything Claude copied must be printed in the text: if a number isn't, it was made up
        if draft is not None and not _copied(draft, text):
            result.used, result.fallback_reason, draft = False, "number check failed", None
        return draft, result
    block = _block(data, content_type)
    if block is None:
        return None, LlmResult(None, used=False, fallback_reason="unsupported file type")
    result = model.ask(SYSTEM, [block, {"type": "text", "text": _question(uploaded_kind)}], SCHEMA, max_tokens=4000,
                       timeout_s=settings.llm_read_timeout_s)
    return _draft(result), result


def _question(uploaded_kind: str) -> str:
    what = "a receipt" if uploaded_kind in ("RECEIPT", "INVOICE") else "a bank or card statement"
    return f"The parent uploaded this as {what}. Read it into the schema."


def _block(data: bytes, content_type: str) -> dict | None:
    encoded = base64.standard_b64encode(data).decode("ascii")
    if content_type == "application/pdf":
        return {"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": encoded}}
    if content_type in ("image/jpeg", "image/png", "image/webp", "image/gif"):
        return {"type": "image", "source": {"type": "base64", "media_type": content_type, "data": encoded}}
    return None


def _draft(result: LlmResult) -> Extraction | None:
    if not result.used or not result.data:
        return None
    d = result.data
    lines = [DocLine(date=_blank(line.get("date")), description=line.get("description", "").strip(),
                     amount=line.get("amount", "").strip(), credit=bool(line.get("credit")),
                     category=_blank(line.get("category")))
             for line in d.get("lines") or [] if line.get("description", "").strip() and line.get("amount", "").strip()]
    draft = Extraction(kind=d.get("kind") or "RECEIPT", merchant=_blank(d.get("merchant")), date=_blank(d.get("date")),
                       total=_blank(d.get("total")), cardLast4=_blank(d.get("cardLast4")),
                       closingBalance=_blank(d.get("closingBalance")), category=_blank(d.get("category")),
                       readBy="LLM", lines=lines)
    if draft.kind in ("RECEIPT", "INVOICE") and not draft.total:
        return None
    if draft.kind.endswith("STATEMENT") and not draft.lines:
        return None
    return draft


def _copied(draft: Extraction, text: str) -> bool:
    printed = numbers_in(text)
    amounts = [draft.total, draft.closingBalance] + [line.amount for line in draft.lines]
    return all(numbers_in(a.replace("-", "")) <= printed for a in amounts if a)


def _blank(value) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def answer_findings(core: CoreClient) -> str:
    """'Any duplicate charges?' The core finds them; the agent reads its sentences back."""
    try:
        found = core.findings()
    except CoreError as e:
        return f"I couldn't check that. {e.detail}."
    if not found:
        return "Nothing odd in your payments: no double charges, and no repeating payments missing from your commitments."
    return "\n".join(f"• {f.title} {f.detail}" for f in found) + "\n\nYou can sort each one out in Money, Uploads."
