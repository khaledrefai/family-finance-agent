"""Shopper agent: "where is it cheapest?"

A kid or a parent says what they want in their own words ("wireless headphones", "is the Sony WH-CH720N cheaper
anywhere?"). Nobody pastes a link. This agent only turns the words into a typed PurchaseIntent (what to search for,
the brand and the model code if they named one, the kind of thing for the family rules) and sends it to the core.

The core does the rest (POST /api/pricing/compare): it searches the shops (SerpApi Google Shopping, a 24 hour cache,
our saved price list as the fallback), converts every price to fils, works out the payable total with delivery,
keeps other models apart as substitutes, re-checks the top offers, ranks them, and runs the same money and family
rule check as any request. This agent then says the core's facts. It never compares or adds up prices itself.

Claude reads the words when it is on; the rule-based reader below handles them when it is off, slow, or unsure.
A brand or model only counts if it is in the person's own words.
"""

import re

from .core_client import CoreClient, CoreError, Me, PurchaseIntent, Role, SpendCategory
from .llm import Llm, LlmResult, llm as default_llm
from .spend_parser import guess_category

SYSTEM = """You read what a family member in the UAE wants to buy, written in their own words, and fill in a
shopping search for a price comparison app. Fill:
- query: short shopping search words in English, the thing itself ("wireless headphones", "air fryer",
  "Sony WH-CH720N headphones"). Leave out prices, shop names, "please", "can I".
- brand: the brand only if the person wrote it, else "".
- model: the model code exactly as the person wrote it (like "WH-CH720N" or "Tune 520BT"), else "".
- category: the kind of thing, from the list, or NONE.
Never add a brand or model the person didn't write. If they are not asking about something to buy, set understood to false."""

_CATEGORIES = [c.value for c in SpendCategory if c is not SpendCategory.OTHER] + ["NONE"]

SCHEMA = {
    "type": "object",
    "properties": {
        "understood": {"type": "boolean"},
        "query": {"type": "string"},
        "brand": {"type": "string"},
        "model": {"type": "string"},
        "category": {"type": "string", "enum": _CATEGORIES},
    },
    "required": ["understood", "query", "brand", "model", "category"],
    "additionalProperties": False,
}

_PRICE_WORDS = re.compile(
    r"\b(compare|cheaper|cheapest|best (price|deal)|lowest price|price of|prices? for|how much (is|are|does|do)|"
    r"where (can i|to|should i) (buy|get)|find me|search for|look for|shop for|any deals?)\b", re.I)
_WANT = re.compile(r"\b(i want|i wanna|i'd like|i need|can i (buy|get|have)|could i (buy|get|have)|"
                   r"may i (buy|get|have)|buy me|get me)\b", re.I)

_LEAD = re.compile(
    r"^(please\s+)?(is( there)?|are there|how much (is|are|does|do)|compare( the)?( prices?)?( of| for)?|where (can i|to|should i) (buy|get)|"
    r"find me|search for|look for|shop for|what('?s| is) the (best |lowest )?price (of|for)|best price (of|for)|"
    r"cheapest|is there a cheaper|i want( to buy)?|i wanna|i'd like|i need|can i (buy|get|have)|"
    r"could i (buy|get|have)|may i (buy|get|have)|buy me|get me)\s+", re.I)
_TAIL = re.compile(r"\s+(please|cost|costs|anywhere|online|in dubai|in the uae|for me|"
                   r"cheaper( anywhere| somewhere)?|on (noon|amazon)( or (noon|amazon))?)$", re.I)
# A price in the words ("for AED 300", "300 dirhams", "for 300" at the end); a bare number may be a model ("iPhone 16")
_AMOUNT = re.compile(r"\s*(for\s+)?(?:(aed|dhs?|dirhams?)\s*\d[\d,]*(\.\d{1,2})?|\d[\d,]*(\.\d{1,2})?\s*(aed|dhs?|dirhams?)\b)"
                     r"|\s+for\s+\d[\d,]*(\.\d{1,2})?$", re.I)
_ARTICLE = re.compile(r"^(a|an|the|some|new)\s+", re.I)
# Sizes and amounts, not model codes: 128GB, 55-inch, 4.2L, 250ml, 42g
_SIZE = re.compile(r"^\d+(\.\d+)?-?(gb|tb|mb|mah|w|l|ml|kg|g|cm|mm|inch|in|oz|pack|pcs|x\d+)$", re.I)
_MODEL = re.compile(r"\b(?=[A-Za-z0-9-]*\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\b")
_BRANDS = ("Sony", "JBL", "Apple", "Samsung", "Anker", "Soundcore", "Beats", "Bose", "LEGO", "Nintendo",
           "PlayStation", "Xbox", "Philips", "Ninja", "Tefal", "Dyson", "Xiaomi", "HP", "Lenovo", "Dell", "Logitech",
           "LG", "TCL", "Nike", "Adidas", "Casio", "Stanley", "Kenwood", "Bosch", "Nespresso", "IKEA", "Amazon",
           "Kindle", "Hasbro", "Mattel", "Crayola", "Faber-Castell", "Huawei", "Garmin", "Chicco", "Joie")


def is_price_question(message: str) -> bool:
    return bool(_PRICE_WORDS.search(message))


def is_want(message: str) -> bool:
    """'I want wireless headphones', 'can I get a Switch game': something to buy, with or without a price."""
    return bool(_WANT.search(message))


def _flat(s: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", s.upper())


def parse_intent(message: str) -> PurchaseIntent | None:
    """The rule-based reader: the thing, its brand and model code. None when nothing is left to search for."""
    text = message.strip().rstrip("?!. ")
    for _ in range(3):
        text = _LEAD.sub("", text).strip()
    text = _AMOUNT.sub(" ", text)
    for _ in range(2):
        text = _TAIL.sub("", text).strip()
    text = _ARTICLE.sub("", re.sub(r"\s+", " ", text)).strip(" ,.-")
    if len(text) < 2:
        return None
    models = [m for m in _MODEL.findall(text) if len(m) >= 3 and not _SIZE.match(m)]
    brand = next((b for b in _BRANDS if re.search(rf"\b{re.escape(b)}\b", text, re.I)), None)
    category = guess_category(text)
    return PurchaseIntent(query=text[:120], brand=brand, model=models[0] if models else None,
                          category=None if category is SpendCategory.OTHER else category, said=message.strip()[:300])


def _from_llm(data: dict, message: str) -> PurchaseIntent | None:
    """Claude's reading, kept only if its brand and model are in the person's own words."""
    if not data.get("understood") or not (data.get("query") or "").strip():
        return None
    brand = (data.get("brand") or "").strip() or None
    model = (data.get("model") or "").strip() or None
    if brand and brand.lower() not in message.lower():
        brand = None
    if model and _flat(model) not in _flat(message):
        model = None  # a model the person never wrote would search for the wrong thing
    category = None if data.get("category") in (None, "NONE") else SpendCategory(data["category"])
    return PurchaseIntent(query=data["query"].strip()[:120], brand=brand, model=model, category=category,
                          said=message.strip()[:300])


def read_intent(message: str, model: Llm | None = None) -> tuple[PurchaseIntent | None, LlmResult]:
    model = model or default_llm
    result = model.ask(SYSTEM, message, SCHEMA, max_tokens=300)
    intent = _from_llm(result.data, message) if result.used and result.data else None
    if result.used and intent is None:
        result.used, result.fallback_reason = False, "LLM intent did not check out"
    return intent or parse_intent(message), result


def reply_for(comparison: dict, me: Me) -> str:
    """The core's facts, plus what happens next. No numbers of our own."""
    facts = comparison.get("facts") or []
    a = comparison.get("assessment") or {}
    if a.get("blocked"):
        # The family rule that stops it comes first, before the shops' prices
        rules = [f for f in facts if f.startswith("Family rule: ")]
        facts = rules + [f for f in facts if f not in rules]
    reply = " ".join(facts[:6])
    if comparison.get("best") is None:
        return reply + " Try other words, like the brand and the kind of thing."
    if me.role is Role.CHILD:
        if a.get("blocked"):
            reply += " That one isn't allowed, so I won't send it to a parent."
        else:
            reply += " Pick one on the Ask screen and I'll send it to a parent."
    return reply


def compare(core: CoreClient, me: Me, message: str, model: Llm | None = None, requester_id=None,
            paid_from=None) -> tuple[PurchaseIntent | None, dict | None, str]:
    """(the intent, the core's comparison, the words to say). The comparison is None when nothing could be searched."""
    run_id = core.open_run("SHOPPER", message)
    intent, result = read_intent(message, model)
    if intent is None:
        reply = "Tell me what you'd like to buy, like \"wireless headphones\" or \"Sony WH-CH720N\"."
        core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return None, None, reply
    try:
        comparison = core.in_run(run_id).compare(intent, requester_id, paid_from)
    except CoreError as e:
        core.finish_run(run_id, False, e.detail, result.model, result.used, result.fallback_reason, result.latency_ms,
                        result.input_tokens, result.output_tokens)
        return intent, None, f"I couldn't compare prices just now. {e.detail}."
    reply = reply_for(comparison, me)
    core.finish_run(run_id, True, reply, result.model, result.used, result.fallback_reason, result.latency_ms,
                    result.input_tokens, result.output_tokens)
    return intent, comparison, reply


def handle(core: CoreClient, me: Me, message: str, model: Llm | None = None) -> str:
    return compare(core, me, message, model)[2]
