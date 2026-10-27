"""Gatekeeper agent, second part: family rules from a parent's plain words.

A parent writes "no peanuts for Adam" or "anything over AED 100 needs me". This agent turns the words into a
typed RuleDraft (kind, which child, which kind of thing or allergen, the amount as written) and sends it to the
core, which checks it and keeps it as a DRAFT. Only the parent's tap on "Turn on" in the app makes it count:
the core refuses that tap from an agent. The Java RuleEngine then checks every request against the rules.

Claude reads the words when it is on; the rule-based parser below handles the common shapes when it is off,
slow, or unsure. Either way the amount must be a number the parent actually wrote.
"""

import re

from .core_client import Allergen, CoreClient, CoreError, Me, Member, Role, RuleDraft, RuleKind, SpendCategory
from .llm import Llm, LlmResult, llm as default_llm, numbers_ok
from .spend_parser import guess_category

EXAMPLES = '"no peanuts for Adam", "anything over AED 100 needs me" or "Layla: snacks up to AED 50 a month"'

SYSTEM = """You turn a parent's family rule, written in plain words, into a typed rule for a UAE family finance app.
Rules are about the children only. Pick exactly one kind:
- BLOCK_CATEGORY: a kind of thing a child can't buy ("no in-game purchases", "no energy drinks").
- BLOCK_ALLERGEN: a food a child must not have ("no peanuts for Adam", "Adam is allergic to nuts").
- APPROVAL_LIMIT: anything over an amount needs a parent ("anything over AED 100 needs me").
- CATEGORY_LIMIT: at most an amount a month or week on one kind of thing ("snacks up to AED 50 a month").
- MONTHLY_CAP: at most an amount a month in all ("Adam spends at most AED 200 a month").
child is one of the children's names given, or ALL. amount is the number exactly as the parent wrote it, or "".
Never invent an amount. If the words are not a rule you can type, set understood to false."""

_KINDS = [k.value for k in RuleKind]
_CATEGORIES = [c.value for c in SpendCategory if c is not SpendCategory.OTHER] + ["NONE"]
_ALLERGENS = [a.value for a in Allergen] + ["NONE"]

SCHEMA = {
    "type": "object",
    "properties": {
        "understood": {"type": "boolean"},
        "kind": {"type": "string", "enum": _KINDS},
        "child": {"type": "string", "description": "a child's name, or ALL"},
        "category": {"type": "string", "enum": _CATEGORIES},
        "allergen": {"type": "string", "enum": _ALLERGENS},
        "amount": {"type": "string", "description": "the amount as written, digits only, or empty"},
        "period": {"type": "string", "enum": ["MONTHLY", "WEEKLY", "NONE"]},
    },
    "required": ["understood", "kind", "child", "category", "allergen", "amount", "period"],
    "additionalProperties": False,
}

_RULE_WORDS = re.compile(r"\b(rule|allerg\w*|not allowed|never|ban|block\w*|needs? (me|us|a parent|approval|permission)|"
                         r"ask (me|us) first|up to|at most|no more than|max(imum)?|limit|cap)\b", re.I)


def is_rule_text(message: str) -> bool:
    """A parent stating a rule. Rules are statements, so a question ("what's my card limit?") is not one."""
    text = message.strip().lower()
    if text.startswith("no ") or text.startswith("don't let") or text.startswith("dont let"):
        return True
    return "?" not in text and bool(_RULE_WORDS.search(text))


# ---------------------------------------------------------------- the rule-based parser (the fallback)

_AMOUNT = re.compile(r"(?:aed|dhs?|dirhams?)?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*(?:aed|dhs?|dirhams?)?", re.I)
_ALLERGEN_WORDS: list[tuple[Allergen, tuple[str, ...]]] = [
    (Allergen.PEANUT, ("peanut", "groundnut")),
    (Allergen.TREE_NUT, ("tree nut", "nut", "almond", "cashew", "hazelnut", "pistachio", "walnut")),
    (Allergen.SESAME, ("sesame", "tahini")),
    (Allergen.SHELLFISH, ("shellfish", "shrimp", "prawn")),
    (Allergen.FISH, ("fish",)),
    (Allergen.MILK, ("milk", "dairy", "lactose")),
    (Allergen.EGG, ("egg",)),
    (Allergen.WHEAT_GLUTEN, ("gluten", "wheat")),
    (Allergen.SOY, ("soy", "soya")),
]
# How parents name a kind of thing in a rule, checked before the spend-logging word list ("eating out", not "shawarma")
_RULE_CATEGORY_WORDS: list[tuple[SpendCategory, tuple[str, ...]]] = [
    (SpendCategory.IN_GAME_PURCHASES, ("in-game", "in game", "game purchases", "games", "gaming", "app store")),
    (SpendCategory.SUGARY_DRINKS, ("energy drinks", "soft drinks", "fizzy", "sugary drinks")),
    (SpendCategory.EATING_OUT, ("eating out", "restaurants", "takeaway", "take-away", "fast food", "delivery")),
    (SpendCategory.SNACKS, ("junk food", "sweets", "treats", "snacks", "candy", "chocolate")),
    (SpendCategory.TOYS_AND_HOBBIES, ("toys", "hobbies")),
    (SpendCategory.CLOTHES_AND_ACCESSORIES, ("clothes", "accessories", "makeup")),
    (SpendCategory.BOOKS_AND_STATIONERY, ("books", "stationery")),
    (SpendCategory.GIFTS_AND_CHARITY, ("gifts", "presents")),
    (SpendCategory.TRANSPORT, ("taxis", "taxi", "transport", "careem", "uber")),
]


def _category(text: str) -> SpendCategory:
    for category, words in _RULE_CATEGORY_WORDS:
        if any(re.search(rf"\b{re.escape(w)}\b", text) for w in words):
            return category
    return guess_category(text)


_NEEDS_PARENT = re.compile(r"\b(needs? (me|us|a parent|my ok|approval|permission)|ask (me|us)( first)?|"
                           r"approv\w*|over|above|more than)\b", re.I)
_MONTH = re.compile(r"\b(a|per|each|every) month\b|\bmonthly\b", re.I)
_WEEK = re.compile(r"\b(a|per|each|every) week\b|\bweekly\b", re.I)
_BLOCK = re.compile(r"^\s*no\b|\b(not allowed|never|ban\w*|block\w*|don'?t let|stop)\b", re.I)


def _child(message: str, children: list[Member]) -> Member | None:
    named = [c for c in children if re.search(rf"\b{re.escape(c.displayName)}\b", message, re.I)]
    return named[0] if len(named) == 1 else None


def _allergen(text: str) -> Allergen | None:
    for allergen, words in _ALLERGEN_WORDS:
        if any(re.search(rf"\b{re.escape(w)}s?\b", text) for w in words):
            return allergen
    return None


def parse_rule(message: str, children: list[Member]) -> RuleDraft | None:
    """The common shapes of a rule, by rules. None when the words don't fit one."""
    text = message.lower()
    child = _child(message, children)
    for_child = child.id if child else None
    m = _AMOUNT.search(text)
    amount = m.group(1).replace(",", "") if m else None
    # What the rule is about, with the amount and the child's name taken out
    rest = _AMOUNT.sub(" ", text)
    if child:
        rest = re.sub(rf"\b{re.escape(child.displayName.lower())}\b", " ", rest)
    category = _category(rest)
    allergen = _allergen(rest)

    def draft(kind: RuleKind, **kw) -> RuleDraft:
        return RuleDraft(kind=kind, forChild=for_child, said=message.strip()[:300], draftedBy="RULES", **kw)

    if allergen and (re.search(r"allerg|intoleran", text) or _BLOCK.search(text)):
        return draft(RuleKind.BLOCK_ALLERGEN, allergen=allergen)
    if amount:
        monthly, weekly = bool(_MONTH.search(text)), bool(_WEEK.search(text))
        if monthly or weekly:
            if category is not SpendCategory.OTHER:
                return draft(RuleKind.CATEGORY_LIMIT, category=category, limit=amount, weekly=weekly and not monthly)
            return draft(RuleKind.MONTHLY_CAP, limit=amount) if monthly else None
        if _NEEDS_PARENT.search(text):
            return draft(RuleKind.APPROVAL_LIMIT, limit=amount)
        return None
    if _BLOCK.search(text) and category is not SpendCategory.OTHER:
        return draft(RuleKind.BLOCK_CATEGORY, category=category)
    return None


# ---------------------------------------------------------------- Claude's reading, checked

def _from_llm(data: dict, message: str, children: list[Member]) -> RuleDraft | None:
    """Claude's answer as a RuleDraft, or None if it doesn't hold up (unknown child, an amount not in the words)."""
    if not data.get("understood"):
        return None
    try:
        kind = RuleKind(data["kind"])
    except (KeyError, ValueError):
        return None
    name = (data.get("child") or "ALL").strip()
    child = next((c for c in children if c.displayName.lower() == name.lower()), None)
    if name.upper() != "ALL" and child is None:
        return None
    amount = (data.get("amount") or "").replace(",", "").strip()
    if amount and not numbers_ok(amount, [message]):
        return None  # Claude worked out or made up a number the parent never wrote
    category = None if data.get("category") in (None, "NONE") else SpendCategory(data["category"])
    allergen = None if data.get("allergen") in (None, "NONE") else Allergen(data["allergen"])
    needs = {RuleKind.BLOCK_CATEGORY: category, RuleKind.BLOCK_ALLERGEN: allergen,
             RuleKind.APPROVAL_LIMIT: amount, RuleKind.MONTHLY_CAP: amount,
             RuleKind.CATEGORY_LIMIT: amount and category}
    if not needs[kind]:
        return None
    return RuleDraft(kind=kind, forChild=child.id if child else None,
                     category=category if kind in (RuleKind.BLOCK_CATEGORY, RuleKind.CATEGORY_LIMIT) else None,
                     allergen=allergen if kind is RuleKind.BLOCK_ALLERGEN else None,
                     limit=amount or None, weekly=data.get("period") == "WEEKLY", said=message.strip()[:300],
                     draftedBy="LLM")


def handle(core: CoreClient, me: Me, message: str, model: Llm | None = None) -> str:
    if me.role is not Role.PARENT:
        return "Family rules are set by your parents. You can ask me if something is allowed."
    model = model or default_llm
    children = [m for m in core.members() if m.role is Role.CHILD]
    run_id = core.open_run("GATEKEEPER", message)
    result = LlmResult(None, used=False, fallback_reason="not reached")
    try:
        names = ", ".join(c.displayName for c in children) or "none"
        result = model.ask(SYSTEM, f"CHILDREN: {names}\n\nPARENT WROTE: {message}", SCHEMA, max_tokens=400)
        draft = _from_llm(result.data, message, children) if result.used and result.data else None
        if result.used and draft is None:
            result.used, result.fallback_reason = False, "LLM rule did not check out"
        draft = draft or parse_rule(message, children)
        if draft is None:
            reply = f"I couldn't turn that into a rule. Try something like {EXAMPLES}."
            core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                            result.input_tokens, result.output_tokens)
            return reply
        rule = core.in_run(run_id).draft_rule(draft)
        reply = (f"I've written this down as a rule: \"{rule.label}\" ({', '.join(rule.chips)}). "
                 "It isn't on yet: turn it on here, or later in Family.")
        core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return reply
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return f"I couldn't save that rule. {e.detail}."
