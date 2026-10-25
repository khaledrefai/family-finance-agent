"""Gatekeeper agent, first part: "can I afford it, and is it allowed?"

The core does the check (POST /api/requests/assess): the money engine for the child's wallet, the family's
free cash until pay day and, for a parent paying by card, the card bill; the Java RuleEngine for the family
rules (blocked kinds of thing, allergens, the approval limit, limits and the monthly cap).
This agent only picks the amount and the kind of thing out of the message and says the facts the core sent
back. Writing new rules from a parent's words is the second part, in rules.py.
"""

import re

from .core_client import CoreClient, CoreError, Me, Role, SpendCategory
from .spend_parser import guess_category

_AMOUNT = re.compile(r"(?:aed|dhs?|dirhams?)\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*(?:aed|dhs?|dirhams?)\b",
                     re.I)


def amount_in(message: str) -> str | None:
    """'can I buy headphones for AED 300?' -> '300'. The core turns the text into fils."""
    m = _AMOUNT.search(message)
    return (m.group(1) or m.group(2)) if m else None


def handle(core: CoreClient, me: Me, message: str) -> str | None:
    """None when there is no amount to check, so the caller can answer another way."""
    amount = amount_in(message)
    if amount is None:
        return None
    run_id = core.open_run("GATEKEEPER", message)
    # The kind of thing, from the same word list as spend logging. The core checks the rules against it and the words.
    category = guess_category(message)
    try:
        a = core.in_run(run_id).assess(amount, title=message[:300],
                                       category=None if category is SpendCategory.OTHER else category)
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, None, False, "rule-based", None, None, None)
        return f"I couldn't check that. {e.detail}."
    reply = " ".join(a.facts)
    if me.role is Role.CHILD:
        if a.blocked:
            reply += " That one isn't allowed, so I won't send it to a parent."
        elif a.kid and not a.kid.walletCovers:
            reply += " Keep saving, or ask a parent."
        elif a.needsParent:
            reply += " I'll need a parent to say yes."
    elif a.verdict == "DOES_NOT_FIT":
        reply += " It doesn't fit before the next pay."
    elif a.verdict == "TIGHT":
        reply += " It fits, but it leaves very little until the next pay."
    core.finish_run(run_id, True, reply, None, False, "rule-based", None, None, None)
    return reply
