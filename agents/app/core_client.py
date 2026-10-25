"""Typed client for the Spring Boot core API.

The agent service never touches the database and never does money maths itself.
Every number it shows comes from the core API, and every change goes through it
using the logged-in member's own token, so the core's permission rules always apply.

The models below mirror the Java records in core/ (e.g. RequestController.RequestView).
If you change one side, change the other.
"""

from datetime import date, datetime
from enum import Enum
from uuid import UUID

import httpx
from pydantic import BaseModel

from .settings import settings


class Role(str, Enum):
    PARENT = "PARENT"
    CHILD = "CHILD"


class AgeBand(str, Enum):
    CHILD_6_9 = "CHILD_6_9"
    PRETEEN_10_12 = "PRETEEN_10_12"
    TEEN_13_17 = "TEEN_13_17"
    ADULT = "ADULT"


class RequestKind(str, Enum):
    SPEND = "SPEND"
    ASK_TO_BUY = "ASK_TO_BUY"
    CHALLENGE = "CHALLENGE"
    REWARD = "REWARD"


class RequestStatus(str, Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    CANCELLED = "CANCELLED"
    BLOCKED = "BLOCKED"


class Me(BaseModel):
    memberId: UUID
    displayName: str
    role: Role
    ageBand: AgeBand
    familyId: UUID
    familyName: str
    currency: str


class SpendingRequest(BaseModel):
    id: UUID
    requesterId: UUID
    kind: RequestKind
    title: str
    amountMinor: int  # fils: 30000 = AED 300.00
    currency: str
    category: str | None = None
    productUrl: str | None = None  # the shop's page, set by the core from a price comparison (never pasted)
    status: RequestStatus
    agentSummary: str | None = None
    createdAt: datetime
    decidedAt: datetime | None = None


class NewRequest(BaseModel):
    kind: RequestKind
    title: str
    amountMinor: int
    category: str | None = None
    # Made from a price comparison offer: the core takes the title, price and shop from it
    comparisonId: UUID | None = None
    offerKey: str | None = None


class Member(BaseModel):
    id: UUID
    displayName: str
    role: Role
    ageBand: AgeBand


# ---------------------------------------------------------------- spend logging (mirrors core/.../spend/)

class SpendCategory(str, Enum):
    SCHOOL_CANTEEN = "SCHOOL_CANTEEN"
    SNACKS = "SNACKS"
    SUGARY_DRINKS = "SUGARY_DRINKS"
    EATING_OUT = "EATING_OUT"
    IN_GAME_PURCHASES = "IN_GAME_PURCHASES"
    TOYS_AND_HOBBIES = "TOYS_AND_HOBBIES"
    BOOKS_AND_STATIONERY = "BOOKS_AND_STATIONERY"
    CLOTHES_AND_ACCESSORIES = "CLOTHES_AND_ACCESSORIES"
    TRANSPORT = "TRANSPORT"
    GIFTS_AND_CHARITY = "GIFTS_AND_CHARITY"
    OTHER = "OTHER"


class MoneyView(BaseModel):
    """Money as the core sends it. Agents show `text` and never do maths on `minor`."""
    minor: int
    currency: str
    text: str  # "AED 12" or "AED 12.50"


class LogItem(BaseModel):
    amount: str  # what the child typed, e.g. "12" or "7.50"; the core turns it into fils
    description: str
    merchant: str | None = None
    category: SpendCategory


class LogSpend(BaseModel):
    items: list[LogItem]
    occurredOn: date | None = None
    loggedText: str | None = None


class SpendEntry(BaseModel):
    id: UUID | None = None
    occurredOn: date
    description: str
    merchant: str | None = None
    category: SpendCategory
    categoryLabel: str
    amount: MoneyView
    loggedText: str | None = None
    possibleDuplicate: bool = False
    familyRuleNote: str | None = None


class LogResult(BaseModel):
    logged: list[SpendEntry]
    today: MoneyView
    thisMonth: MoneyView


class CategoryLine(BaseModel):
    category: SpendCategory
    label: str
    total: MoneyView
    count: int
    percent: int
    averagePerPurchase: MoneyView


class ItemLine(BaseModel):
    description: str
    category: SpendCategory
    count: int
    total: MoneyView


class WeekLine(BaseModel):
    weekStart: date
    weekEnd: date
    total: MoneyView
    topCategory: SpendCategory | None = None
    topCategoryLabel: str | None = None


class Comparison(BaseModel):
    periodLabel: str
    total: MoneyView
    daysLogged: int
    difference: MoneyView
    direction: str  # MORE, LESS or SAME


class GoalLine(BaseModel):
    name: str
    target: MoneyView
    saved: MoneyView
    remaining: MoneyView
    percent: int


class SpendSummary(BaseModel):
    memberId: UUID
    displayName: str
    periodLabel: str
    monthToDate: bool
    daysInPeriod: int
    total: MoneyView
    purchaseCount: int
    daysLogged: int
    lastLoggedOn: date | None = None
    pocketMoney: MoneyView
    spentPercentOfPocketMoney: int
    categories: list[CategoryLine]
    topItems: list[ItemLine]
    biggestPurchase: SpendEntry | None = None
    weeks: list[WeekLine]
    topCategoryWeeksRunning: int
    previous: Comparison
    goals: list[GoalLine]
    againstFamilyRules: list[SpendEntry]


class LoggingStatus(BaseModel):
    today: date
    schoolDay: bool
    loggedToday: bool
    lastLoggedOn: date | None = None
    todayTotal: MoneyView


# ---------------------------------------------------------------- the money plan (mirrors core/.../plan/)
# Only the fields the agents use. Unknown fields from the core are ignored.

class PlanLine(BaseModel):
    label: str
    amount: MoneyView
    note: str | None = None


class Section(BaseModel):
    total: MoneyView
    lines: list[PlanLine]


class UntilNextIncome(BaseModel):
    nextIncomeOn: date | None = None
    nextIncomeLabel: str | None = None
    free: MoneyView
    why: list[PlanLine]


class Upcoming(BaseModel):
    name: str
    dueOn: date
    daysLeft: int
    amount: MoneyView
    state: str  # FROM_PAY, RESERVED or SHORT
    shortBy: MoneyView | None = None
    label: str | None = None


class MonthLine(BaseModel):
    month: str
    label: str
    free: MoneyView
    spare: MoneyView
    tight: bool
    negative: bool


class Cashflow(BaseModel):
    currency: str
    today: date
    monthLabel: str
    income: Section
    commitments: Section
    everyday: Section
    free: MoneyView
    saving: Section
    spare: MoneyView
    untilNextIncome: UntilNextIncome
    nextCommitment: Upcoming | None = None
    upcoming: list[Upcoming]
    months: list[MonthLine]
    notes: list[str]


class Share(BaseModel):
    kind: str  # COMMITMENTS, EVERYDAY, GOALS, INVEST_ASIDE, FUN, GIVING
    shareBp: int  # basis points: 4264 = 42.64%. Set by the core's engine, never worked out here.
    reason: str | None = None


class Bucket(BaseModel):
    kind: str
    label: str
    shareBp: int
    amount: MoneyView


class Buckets(BaseModel):
    planId: UUID | None = None
    status: str | None = None
    proposedBy: str | None = None
    income: MoneyView
    buckets: list[Bucket]
    warnings: list[str] = []


class Goal(BaseModel):
    id: UUID
    name: str
    tier: str
    target: MoneyView
    saved: MoneyView
    left: MoneyView
    monthly: MoneyView | None = None


class PlanOption(BaseModel):
    id: str  # relaxed, steady, faster
    name: str
    monthly: MoneyView
    readyMonth: str
    meetsDate: bool
    fits: bool
    tightestMonth: str | None = None
    spareInTightest: MoneyView | None = None


class PlanOptions(BaseModel):
    goalId: UUID
    goalName: str
    options: list[PlanOption]
    recommended: str | None = None


# ---------------------------------------------------------------- affordability (mirrors AffordabilityService)

class KidSide(BaseModel):
    name: str
    wallet: MoneyView
    walletCovers: bool


class CardCheck(BaseModel):
    card: str
    fits: bool
    line: str


class RuleHit(BaseModel):
    rule: str      # the rule in the parent's words, e.g. "No peanuts for Adam"
    kind: str
    outcome: str   # OK, NEEDS_PARENT or BLOCKED
    detail: str    # a whole sentence written by the core


class RuleCheck(BaseModel):
    outcome: str   # the worst of the hits: OK, NEEDS_PARENT or BLOCKED
    hits: list[RuleHit]


class Assessment(BaseModel):
    amount: MoneyView
    verdict: str  # FITS, TIGHT, DOES_NOT_FIT
    needsParent: bool
    blocked: bool = False  # against a family rule (a blocked kind of thing, an allergen in the name)
    kid: KidSide | None = None
    rules: RuleCheck | None = None
    card: CardCheck | None = None
    facts: list[str]  # every number the agent may say is in here


# ---------------------------------------------------------------- price search (mirrors core/.../pricing/)

class PurchaseIntent(BaseModel):
    """What someone wants to buy, as the Shopper read it. The core searches, converts prices and ranks."""
    query: str                    # plain search words, e.g. "wireless headphones"
    brand: str | None = None
    model: str | None = None      # a model code they wrote, e.g. "WH-CH720N": then other models are substitutes
    gtin: str | None = None
    category: SpendCategory | None = None
    said: str | None = None       # the words as typed


class Offer(BaseModel):
    key: str
    store: str
    title: str
    price: MoneyView
    delivery: MoneyView | None = None   # None = the shop doesn't list delivery
    deliveryKnown: bool
    total: MoneyView
    source: str                         # LIVE, CACHE or SEEDED (our saved price list)
    verified: bool


class PriceComparison(BaseModel):
    id: UUID
    source: str
    exact: bool
    offers: list[Offer]
    substitutes: list[Offer]
    best: Offer | None = None
    assessment: Assessment | None = None
    facts: list[str]                    # every number the agent may say is in here


# ---------------------------------------------------------------- family rules (mirrors core/.../rules/)

class RuleKind(str, Enum):
    BLOCK_CATEGORY = "BLOCK_CATEGORY"   # a kind of thing a child can't buy
    BLOCK_ALLERGEN = "BLOCK_ALLERGEN"   # a food a child must not have
    APPROVAL_LIMIT = "APPROVAL_LIMIT"   # anything over this needs a parent
    CATEGORY_LIMIT = "CATEGORY_LIMIT"   # at most this a month (or week) on one kind of thing
    MONTHLY_CAP = "MONTHLY_CAP"         # at most this a month in all


class Allergen(str, Enum):
    PEANUT = "PEANUT"
    TREE_NUT = "TREE_NUT"
    MILK = "MILK"
    EGG = "EGG"
    WHEAT_GLUTEN = "WHEAT_GLUTEN"
    SOY = "SOY"
    FISH = "FISH"
    SHELLFISH = "SHELLFISH"
    SESAME = "SESAME"


class RuleDraft(BaseModel):
    """A typed rule for the core to check. It stays a DRAFT until a parent turns it on in the app."""
    kind: RuleKind
    forChild: UUID | None = None          # None = every child
    category: SpendCategory | None = None
    allergen: Allergen | None = None
    limit: str | None = None              # the amount as written, e.g. "100"; the core turns it into fils
    weekly: bool = False
    said: str                             # the parent's own words
    draftedBy: str                        # LLM or RULES


class Rule(BaseModel):
    id: UUID
    kind: RuleKind
    status: str   # DRAFT, ACTIVE or ARCHIVED
    label: str
    forChildName: str | None = None
    chips: list[str] = []
    replaces: list[str] = []


# ---------------------------------------------------------------- ways to save (mirrors core/.../advice/)

class TipView(BaseModel):
    key: str
    title: str
    text: str
    writtenBy: str        # LLM, RULES, or ENGINE (the engine's own sentence)
    facts: list[str]      # the only numbers the words may use


class WaysToSave(BaseModel):
    month: date
    monthLabel: str
    tips: list[TipView]
    hidden: int
    needsWords: bool


# ---------------------------------------------------------------- documents (mirrors core/.../documents/)

class DocLine(BaseModel):
    """What the agent read from one line. Every amount is the text printed on the document: the core converts it."""
    date: str | None = None
    description: str
    amount: str
    credit: bool | None = None
    category: str | None = None


class Extraction(BaseModel):
    """An ExtractedDocument: a typed draft for the core to check. Nothing counts until a parent confirms it."""
    kind: str  # RECEIPT, INVOICE, BANK_STATEMENT or CARD_STATEMENT
    merchant: str | None = None
    date: str | None = None
    total: str | None = None
    cardLast4: str | None = None
    closingBalance: str | None = None
    category: str | None = None
    readBy: str  # LLM or RULES
    note: str | None = None
    lines: list[DocLine] = []


class Counts(BaseModel):
    lines: int
    added: int
    alreadyLogged: int
    possibleDuplicates: int


class Document(BaseModel):
    id: UUID
    kind: str
    fileName: str
    contentType: str
    status: str  # UPLOADED, EXTRACTED, CONFIRMED, FAILED
    accountLabel: str | None = None
    counts: Counts
    summary: str  # written by the core; the agent repeats it
    facts: list[str]


class Finding(BaseModel):
    key: str
    kind: str  # DUPLICATE or SUBSCRIPTION
    title: str
    detail: str
    amount: MoneyView


class CoreError(Exception):
    """The core said no (bad amount, not allowed...). `detail` is a message safe to show the member."""

    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


class CoreClient:
    """Calls the core API on behalf of one member (their bearer token)."""

    def __init__(self, token: str, base_url: str | None = None, transport: httpx.BaseTransport | None = None,
                 run_id: UUID | None = None):
        self._args = (token, base_url, transport)
        headers = {"Authorization": f"Bearer {token}"}
        if run_id is not None:
            headers["X-Agent-Run-Id"] = str(run_id)
        self._http = httpx.Client(
            base_url=base_url or settings.core_url,
            headers=headers,
            timeout=10.0,
            transport=transport,
        )

    # ------------------------------------------------------------ agent runs (the core keeps the audit trail)

    def open_run(self, agent: str, input_summary: str) -> UUID:
        resp = self._http.post("/api/agent-runs", json={"agent": agent, "triggerKind": "MESSAGE",
                                                        "inputSummary": input_summary[:2000]})
        _raise_for_core(resp)
        return UUID(resp.json()["id"])

    def in_run(self, run_id: UUID) -> "CoreClient":
        """The same member, but every call is recorded under this agent run and anything it creates is a draft."""
        token, base_url, transport = self._args
        return CoreClient(token, base_url, transport, run_id=run_id)

    def finish_run(self, run_id: UUID, ok: bool, output_summary: str, model: str | None, llm_used: bool,
                   fallback_reason: str | None, latency_ms: int | None, input_tokens: int | None,
                   output_tokens: int | None) -> None:
        body = {"status": "SUCCEEDED" if ok else "FAILED", "outputSummary": output_summary[:4000], "model": model,
                "llmUsed": llm_used, "fallbackReason": fallback_reason, "latencyMs": latency_ms,
                "inputTokens": input_tokens, "outputTokens": output_tokens}
        _raise_for_core(self._http.post(f"/api/agent-runs/{run_id}/finish",
                                        json={k: v for k, v in body.items() if v is not None}))

    # ------------------------------------------------------------ the money plan

    def cashflow(self) -> Cashflow:
        return Cashflow.model_validate(self._get("/api/plan/cashflow"))

    def goals(self) -> list[Goal]:
        return [Goal.model_validate(g) for g in self._get("/api/plan/goals")]

    def plan_options(self, goal_id: UUID) -> PlanOptions:
        resp = self._http.post("/api/plan/options", json={"goalId": str(goal_id)})
        _raise_for_core(resp)
        return PlanOptions.model_validate(resp.json())

    def bucket_suggestion(self) -> list[Share]:
        return [Share.model_validate(s) for s in self._get("/api/plan/buckets/suggestion")]

    def save_bucket_draft(self, shares: list[Share]) -> Buckets:
        resp = self._http.post("/api/plan/buckets/draft", json={"shares": [s.model_dump() for s in shares]})
        _raise_for_core(resp)
        return Buckets.model_validate(resp.json())

    def assess(self, amount: str, requester_id: UUID | None = None, paid_from: UUID | None = None,
               title: str | None = None, category: SpendCategory | None = None) -> Assessment:
        """`title` and `category` are what the core checks the family rules against."""
        body = {"amount": amount, "requesterId": str(requester_id) if requester_id else None,
                "paidFromAccountId": str(paid_from) if paid_from else None, "title": title,
                "category": category.value if category else None}
        resp = self._http.post("/api/requests/assess", json={k: v for k, v in body.items() if v})
        _raise_for_core(resp)
        return Assessment.model_validate(resp.json())

    def compare(self, intent: PurchaseIntent, requester_id: UUID | None = None,
                paid_from: UUID | None = None) -> dict:
        """The core's price comparison as it sent it (the web app draws the offers from it); see PriceComparison."""
        body = {"intent": intent.model_dump(exclude_none=True, mode="json"),
                "requesterId": str(requester_id) if requester_id else None,
                "paidFromAccountId": str(paid_from) if paid_from else None}
        resp = self._http.post("/api/pricing/compare", json={k: v for k, v in body.items() if v},
                               timeout=30.0)  # a live search plus three re-checks
        _raise_for_core(resp)
        return resp.json()

    # ------------------------------------------------------------ family rules and ways to save

    def draft_rule(self, draft: RuleDraft) -> Rule:
        resp = self._http.post("/api/rules/drafts", json=draft.model_dump(exclude_none=True, mode="json"))
        _raise_for_core(resp)
        return Rule.model_validate(resp.json())

    def rules(self) -> list[Rule]:
        return [Rule.model_validate(r) for r in self._get("/api/rules")]

    def ways_to_save(self) -> WaysToSave:
        return WaysToSave.model_validate(self._get("/api/ways-to-save"))

    def save_tip_words(self, words: list[dict]) -> WaysToSave:
        """[{key, text, writtenBy}]. The core checks the numbers again and keeps its own sentence if one is wrong."""
        resp = self._http.post("/api/ways-to-save/words", json={"tips": words})
        _raise_for_core(resp)
        return WaysToSave.model_validate(resp.json())

    # ------------------------------------------------------------ documents

    def document(self, doc_id: UUID) -> Document:
        return Document.model_validate(self._get(f"/api/documents/{doc_id}"))

    def document_file(self, doc_id: UUID) -> tuple[bytes, str]:
        """The uploaded file and its content type."""
        resp = self._http.get(f"/api/documents/{doc_id}/file")
        _raise_for_core(resp)
        return resp.content, resp.headers.get("content-type", "application/octet-stream").split(";")[0].strip()

    def save_extraction(self, doc_id: UUID, draft: Extraction) -> Document:
        resp = self._http.post(f"/api/documents/{doc_id}/extraction", json=draft.model_dump(exclude_none=True))
        _raise_for_core(resp)
        return Document.model_validate(resp.json())

    def mark_failed(self, doc_id: UUID, reason: str) -> Document:
        resp = self._http.post(f"/api/documents/{doc_id}/failed", json={"reason": reason[:300]})
        _raise_for_core(resp)
        return Document.model_validate(resp.json())

    def findings(self) -> list[Finding]:
        return [Finding.model_validate(f) for f in self._get("/api/findings")]

    def me(self) -> Me:
        return Me.model_validate(self._get("/api/me"))

    def list_requests(self) -> list[SpendingRequest]:
        return [SpendingRequest.model_validate(r) for r in self._get("/api/requests")]

    def create_request(self, new: NewRequest) -> SpendingRequest:
        resp = self._http.post("/api/requests", json=new.model_dump(exclude_none=True, mode="json"))
        resp.raise_for_status()
        return SpendingRequest.model_validate(resp.json())

    def members(self) -> list[Member]:
        return [Member.model_validate(m) for m in self._get("/api/family/members")]

    def log_spend(self, spend: LogSpend) -> LogResult:
        resp = self._http.post("/api/spend", json=spend.model_dump(exclude_none=True, mode="json"))
        _raise_for_core(resp)
        return LogResult.model_validate(resp.json())

    def spend_summary(self, member_id: UUID | None = None, month: str | None = None) -> SpendSummary:
        params = {k: str(v) for k, v in {"memberId": member_id, "month": month}.items() if v}
        return SpendSummary.model_validate(self._get("/api/spend/summary", params))

    def spend_status(self) -> LoggingStatus:
        return LoggingStatus.model_validate(self._get("/api/spend/status"))

    def _get(self, path: str, params: dict | None = None):
        resp = self._http.get(path, params=params)
        _raise_for_core(resp)
        return resp.json()


def _raise_for_core(resp: httpx.Response) -> None:
    if resp.is_success:
        return
    try:
        detail = resp.json().get("detail") or resp.reason_phrase
    except ValueError:
        detail = resp.reason_phrase
    raise CoreError(resp.status_code, detail)
