"""The Bookkeeper's rule-based reader: CSV statement exports and plain-text receipts, with no LLM.

It is the fallback when Claude is off or fails, and the reader for CSV files even when Claude is on (a CSV is
already structured, so rules are exact and free). It only copies what is printed: dates and amounts go to the
core as the text on the page. It never adds anything up; the core checks the total against the lines.
"""

import csv
import io
import re

from .core_client import DocLine, Extraction

_AMOUNT = r"-?\(?\d[\d,]*\.\d{2}\)?(?:\s*CR)?"
_ITEM = re.compile(rf"^\s*(?P<desc>\S.*?\S)\s{{2,}}(?P<amount>{_AMOUNT})\s*$", re.I)
_TOTAL = re.compile(rf"^\s*(?:GRAND\s+)?TOTAL\b.*?(?P<amount>{_AMOUNT})\s*$", re.I)
_DATE = re.compile(r"\b(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}|\d{4}-\d{2}-\d{2})\b")
_CARD = re.compile(r"(?:[*xX•]{4,}[\s-]*)+(\d{4})\b")
_NOT_ITEMS = re.compile(r"\b(VAT|SUB\s*TOTAL|SUBTOTAL|TRN|CHANGE|CASH|TENDER|BALANCE|DISCOUNT TOTAL)\b", re.I)


def parse(text: str, content_type: str, file_name: str = "") -> Extraction | None:
    """A draft, or None when the rules can't find a statement or a receipt in it."""
    if content_type in ("text/csv", "application/vnd.ms-excel") or file_name.lower().endswith(".csv"):
        return parse_statement_csv(text)
    return parse_receipt_text(text) or parse_statement_csv(text)


def parse_statement_csv(text: str) -> Extraction | None:
    rows = [r for r in csv.reader(io.StringIO(text)) if any(c.strip() for c in r)]
    last4 = closing = None
    card = False
    header: list[str] | None = None
    lines: list[DocLine] = []
    for row in rows:
        cells = [c.strip() for c in row]
        low = [c.lower() for c in cells]
        if header is None:
            if "date" in low and ("amount" in low or "debit" in low):
                header = low
                continue
            label, value = low[0], (cells[1] if len(cells) > 1 else "")
            if "card" in label:
                card = True
                m = re.search(r"(\d{4})\s*$", value)
                last4 = m.group(1) if m else last4
            elif "account" in label and not last4:
                m = re.search(r"(\d{4})\s*$", value)
                last4 = m.group(1) if m else None
            elif "closing" in label or label == "balance":
                closing = value or None
            continue
        line = _statement_line(header, cells)
        if line:
            lines.append(line)
    if not lines:
        return None
    return Extraction(kind="CARD_STATEMENT" if card else "BANK_STATEMENT", cardLast4=last4, closingBalance=closing,
                      readBy="RULES", lines=lines)


def _statement_line(header: list[str], cells: list[str]) -> DocLine | None:
    def col(*names: str) -> str:
        for n in names:
            if n in header and header.index(n) < len(cells):
                return cells[header.index(n)]
        return ""

    date = col("date", "transaction date", "posting date")
    description = col("description", "details", "narrative", "merchant", "transaction details")
    amount, credit = col("amount"), None
    if not amount:
        debit, money_in = col("debit", "money out"), col("credit", "money in")
        amount, credit = (debit, False) if debit else (money_in, True)
    if not (date and description and amount):
        return None
    return DocLine(date=date, description=description, amount=amount, credit=credit)


def parse_receipt_text(text: str) -> Extraction | None:
    rows = [r.rstrip() for r in text.splitlines()]
    total_at = next((i for i, r in enumerate(rows) if _TOTAL.match(r) and not re.search(r"SUB\s*TOTAL", r, re.I)), None)
    if total_at is None:
        return None
    total = _TOTAL.match(rows[total_at]).group("amount")
    items = []
    for r in rows[:total_at]:
        m = _ITEM.match(r)
        if m and not _NOT_ITEMS.search(r) and not _DATE.search(m.group("desc")):
            items.append(DocLine(description=m.group("desc"), amount=m.group("amount")))
    date = next((m.group(1) for r in rows if (m := _DATE.search(r))), None)
    card = next((m.group(1) for r in rows if (m := _CARD.search(r))), None)
    return Extraction(kind="RECEIPT", merchant=_shop(rows), date=date, total=total, cardLast4=card, readBy="RULES",
                      lines=items)


def _shop(rows: list[str]) -> str | None:
    """The shop's name: the first line, plus the branch on the next line ("CARREFOUR" + "MIRDIF CITY CENTRE")."""
    lines = [r.strip() for r in rows if r.strip()]
    if not lines:
        return None
    name = lines[0]
    if len(lines) > 1 and re.fullmatch(r"[A-Za-z ,'&-]+", lines[1]) and not _NOT_ITEMS.search(lines[1]):
        name += " " + lines[1].split(",")[0].strip()
    return name.title()[:160]
