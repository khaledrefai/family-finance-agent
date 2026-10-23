"""Turns a child's message like "12 dirhams shawarma" into typed items for the core API.

This is the spike version: plain rules, no LLM, so it is predictable on camera and easy to test.
It only *reads* what the child wrote. The amount stays as the text they typed ("12", "7.50");
the core turns it into fils and does all the adding up.

A later week can swap parse_spend() for an LLM that fills the same LogSpend model.
"""

import re
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

from .core_client import LogItem, LogSpend, SpendCategory

_CURRENCY = r"(?:aed|dhs?|dirhams?|dirham|dhm|درهم)"
# "12", "7.50", "3,50", optionally with a currency word before or after: "AED 12", "12dhs", "12 dirhams"
_AMOUNT = re.compile(rf"(?:{_CURRENCY}\s*)?(?<![\w.])(\d{{1,4}}(?:[.,]\d{{1,2}})?)(?![\d])\s*{_CURRENCY}?\b", re.I)

# Separators between different things in one message: "shawarma 12, juice 3 and gum 2"
_SPLIT = re.compile(r",\s+|;|\+|&|\band\b|\bthen\b|\balso\b|\bplus\b", re.I)

_FILLER = {
    "i", "i've", "ive", "i'd", "just", "spent", "spend", "paid", "pay", "bought", "buy", "got", "get", "had",
    "a", "an", "the", "some", "on", "for", "of", "my", "me", "it", "was", "today", "yesterday", "this", "morning",
    "afternoon", "evening", "at", "from", "in", "with", "to",
}
# "with friends" is worth keeping in a description; everything else above is noise
_KEEP_PHRASES = ("with friends",)

_DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

# Checked in order; first match wins. Where it was bought beats what it was ("juice at the canteen" = canteen).
_CATEGORY_WORDS: list[tuple[SpendCategory, tuple[str, ...]]] = [
    (SpendCategory.SCHOOL_CANTEEN, ("canteen", "cafeteria", "lunch", "tuck shop")),
    (SpendCategory.IN_GAME_PURCHASES, ("robux", "v-bucks", "vbucks", "fortnite", "roblox", "minecoins", "battle pass",
                                       "in-game", "in game", "gems", "skin", "game credit")),
    (SpendCategory.SUGARY_DRINKS, ("cola", "coke", "pepsi", "fizzy", "soda", "sprite", "fanta", "7up",
                                   "energy drink", "red bull", "mountain dew")),
    (SpendCategory.EATING_OUT, ("shawarma", "burger", "pizza", "mcdonald", "kfc", "restaurant", "bubble tea", "boba",
                                "karak", "coffee", "starbucks", "fries", "noodles", "sushi", "falafel", "manakeesh",
                                "manousheh", "cafe", "dinner", "frappe")),
    (SpendCategory.SNACKS, ("chips", "crisps", "candy", "sweets", "chocolate", "gum", "ice cream", "popcorn",
                            "cookie", "biscuit", "juice", "water", "snack", "croissant", "donut", "doughnut", "cake",
                            "lollipop", "slushie", "ice lolly", "muffin")),
    (SpendCategory.TOYS_AND_HOBBIES, ("pokemon", "pokémon", "cards", "lego", "toy", "slime", "stickers", "squishy",
                                      "ball", "figure", "fidget", "puzzle", "craft", "beads", "kit")),
    (SpendCategory.BOOKS_AND_STATIONERY, ("book", "notebook", "pen", "pens", "pencil", "stationery", "eraser",
                                          "sketchbook", "comic", "manga", "marker", "ruler", "glue")),
    (SpendCategory.CLOTHES_AND_ACCESSORIES, ("shirt", "t-shirt", "socks", "cap", "hat", "phone case", "case",
                                             "bracelet", "hair", "clips", "lip balm", "makeup", "nail", "earrings",
                                             "shoes", "hoodie", "scrunchie", "keychain")),
    (SpendCategory.TRANSPORT, ("metro", "nol", "bus", "taxi", "careem", "uber", "tram")),
    (SpendCategory.GIFTS_AND_CHARITY, ("gift", "present", "birthday", "charity", "donation", "sadaqah", "eid")),
]


@dataclass
class ParsedSpend:
    spend: LogSpend | None      # None when we couldn't find an amount
    missing_amount_for: str | None = None  # what they bought, if we know, so we can ask "how much was it?"


def guess_category(text: str) -> SpendCategory:
    lowered = text.lower()
    for category, words in _CATEGORY_WORDS:
        if any(re.search(rf"\b{re.escape(w)}(?:e?s)?\b", lowered) for w in words):
            return category
    return SpendCategory.OTHER


def _when(text: str, today: date) -> date | None:
    lowered = text.lower()
    if "yesterday" in lowered:
        return today - timedelta(days=1)
    for i, day in enumerate(_DAYS):
        if re.search(rf"\bon {day}\b", lowered):
            back = (today.weekday() - i) % 7 or 7  # "on Monday" said on a Monday means last week
            return today - timedelta(days=back)
    return None


def _merchant(chunk: str) -> str | None:
    m = re.search(r"\b(?:at|from)\s+(?:the\s+)?([a-z][\w' -]{1,40})$", chunk.strip(), re.I)
    return m.group(1).strip().title() if m else None


def _describe(chunk: str) -> str:
    text = _AMOUNT.sub(" ", chunk)
    text = re.sub(rf"\b{_CURRENCY}\b", " ", text, flags=re.I)
    text = re.sub(r"\b(?:at|from)\s+(?:the\s+)?[a-z][\w' -]{1,40}$", " ", text.strip(), flags=re.I)
    kept = [p for p in _KEEP_PHRASES if p in text.lower()]
    for p in kept:
        text = re.sub(re.escape(p), " ", text, flags=re.I)
    words = [w for w in re.findall(r"[\w'’-]+", text) if w.lower() not in _FILLER and not w.isdigit()]
    words = [w for w in words if w.lower() not in _DAYS]
    description = " ".join(words + kept).strip()
    return description[:1].upper() + description[1:] if description else ""


def parse_spend(message: str, today: date) -> ParsedSpend:
    """'12 dirhams shawarma and juice 3.50 at the canteen' -> two LogItems."""
    chunks = [c for c in _SPLIT.split(message) if c and c.strip()]
    items: list[LogItem] = []
    pending: list[str] = []  # things named before their price: "sandwich and juice for 9"

    for chunk in chunks:
        amounts = _AMOUNT.findall(chunk)
        description = _describe(chunk)
        if not amounts:
            if description:
                pending.append(description.lower() if pending or items else description)
            continue
        # One price per chunk. "2 cards for 15" is rare enough for a spike; the larger number is the price.
        amount = max(amounts, key=lambda a: Decimal(a.replace(",", ".")))  # only picks one, adds nothing
        full = " and ".join(pending + ([description.lower()] if pending and description else [description] if description else []))
        pending = []
        full = full[:1].upper() + full[1:] if full else "Something"
        items.append(LogItem(
            amount=amount.replace(",", "."),
            description=full[:120],
            merchant=_merchant(chunk),
            category=guess_category(chunk if full == "Something" else f"{full} {chunk}"),
        ))

    if pending and items:  # "12 shawarma and juice": the juice was part of the 12
        last = items[-1]
        last.description = (last.description + " and " + " and ".join(pending)).strip()[:120]
    if not items:
        return ParsedSpend(None, " and ".join(pending) or None)

    # A merchant named once ("... at the canteen") applies to everything in the message
    merchants = {i.merchant for i in items if i.merchant}
    if len(merchants) == 1:
        shared = merchants.pop()
        for i in items:
            i.merchant = i.merchant or shared
            if i.category is SpendCategory.OTHER or guess_category(shared) is SpendCategory.SCHOOL_CANTEEN:
                i.category = guess_category(f"{shared} {i.description}")

    return ParsedSpend(LogSpend(items=items, occurredOn=_when(message, today), loggedText=message.strip()[:500]))


def looks_like_spend(message: str) -> bool:
    """A message with an amount in it, from a child, is probably them telling us what they bought."""
    return bool(_AMOUNT.search(message))
