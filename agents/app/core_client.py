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
    productUrl: str | None = None
    status: RequestStatus
    agentSummary: str | None = None
    createdAt: datetime
    decidedAt: datetime | None = None


class NewRequest(BaseModel):
    kind: RequestKind
    title: str
    amountMinor: int
    category: str | None = None
    productUrl: str | None = None


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


class CoreError(Exception):
    """The core said no (bad amount, not allowed...). `detail` is a message safe to show the member."""

    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


class CoreClient:
    """Calls the core API on behalf of one member (their bearer token)."""

    def __init__(self, token: str, base_url: str | None = None, transport: httpx.BaseTransport | None = None):
        self._http = httpx.Client(
            base_url=base_url or settings.core_url,
            headers={"Authorization": f"Bearer {token}"},
            timeout=10.0,
            transport=transport,
        )

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
