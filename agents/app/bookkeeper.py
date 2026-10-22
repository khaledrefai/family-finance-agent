"""Bookkeeper agent, spend-logging part (spike).

  * A child tells it what they bought  -> parse into typed items -> core records them -> confirm.
  * A parent asks where a child's money went -> core works out the numbers -> we tell the story.

The member's own token is used for every core call, so a child can only log and read their own spending.
"""

import re
from datetime import date, datetime
from zoneinfo import ZoneInfo

from .core_client import CoreClient, CoreError, Me, Member, Role
from .spend_parser import parse_spend
from .spend_story import ask_for_amount, child_confirmation, parent_story

UAE = ZoneInfo("Asia/Dubai")

_MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
           "november", "december"]

_QUESTION = re.compile(r"\bwhere\b.*\b(money|spend|spent|go|went)\b|\bwhat did\b.*\b(spend|buy|bought)\b|"
                       r"\bspending\b|\bspent\b.*\?|\bmoney go\b|\bmoney went\b", re.I)


def today_in_uae() -> date:
    return datetime.now(UAE).date()


def is_spend_question(message: str) -> bool:
    return bool(_QUESTION.search(message))


def month_asked(message: str, today: date) -> str | None:
    """'last month' -> '2026-09'. None = this month (the core's default)."""
    text = message.lower()
    if "last month" in text or "previous month" in text:
        y, m = (today.year, today.month - 1) if today.month > 1 else (today.year - 1, 12)
        return f"{y:04d}-{m:02d}"
    for i, name in enumerate(_MONTHS, start=1):
        pattern = r"\bin may\b" if name == "may" else rf"\b(?:{name}|{name[:3]})\b"  # "may I..." is not May
        if re.search(pattern, text):
            year = today.year if i <= today.month else today.year - 1
            return f"{year:04d}-{i:02d}"
    return None


def children_asked(message: str, family: list[Member]) -> list[Member]:
    """Children named in the question; nobody named = all of them."""
    kids = [m for m in family if m.role is Role.CHILD]
    named = [k for k in kids if re.search(rf"\b{re.escape(k.displayName)}\b", message, re.I)]
    return named or kids


def handle(core: CoreClient, me: Me, message: str) -> str:
    today = today_in_uae()
    try:
        if me.role is Role.PARENT or is_spend_question(message):
            return _answer_question(core, me, message, today)
        return _log(core, message, today)
    except CoreError as e:
        return f"I couldn't do that: {e.detail}."


def _log(core: CoreClient, message: str, today: date) -> str:
    parsed = parse_spend(message, today)
    if parsed.spend is None:
        return ask_for_amount(parsed.missing_amount_for)
    return child_confirmation(core.log_spend(parsed.spend))


def _answer_question(core: CoreClient, me: Me, message: str, today: date) -> str:
    month = month_asked(message, today)
    if me.role is Role.CHILD:
        return parent_story(core.spend_summary(month=month), today)
    stories = [parent_story(core.spend_summary(member_id=kid.id, month=month), today)
               for kid in children_asked(message, core.members())]
    return "\n\n".join(stories) if stories else "There are no children in this family yet."
