"""Gatekeeper agent, first part: "can I afford it?"

The core's money engine does the check (POST /api/requests/assess): the child's wallet, the family's
free cash until pay day, the approval limit and, for a parent paying by card, the card bill.
This agent only picks the amount out of the message and says the facts the core sent back.
Sending the request to a parent and checking family rules and allergens come next.
"""

import re

from .core_client import CoreClient, CoreError, Me, Role

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
    try:
        a = core.in_run(run_id).assess(amount)
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, None, False, "rule-based", None, None, None)
        return f"I couldn't check that. {e.detail}."
    reply = " ".join(a.facts)
    if me.role is Role.CHILD:
        if a.kid and not a.kid.walletCovers:
            reply += " Keep saving, or ask a parent."
        elif a.needsParent:
            reply += " I'll need a parent to say yes."
    elif a.verdict == "DOES_NOT_FIT":
        reply += " It doesn't fit before the next pay."
    elif a.verdict == "TIGHT":
        reply += " It fits, but it leaves very little until the next pay."
    core.finish_run(run_id, True, reply, None, False, "rule-based", None, None, None)
    return reply
