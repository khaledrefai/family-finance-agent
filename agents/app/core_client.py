"""Typed client for the Spring Boot core API.

The agent service never touches the database and never does money maths itself.
Every number it shows comes from the core API, and every change goes through it
using the logged-in member's own token, so the core's permission rules always apply.

The models below mirror the Java records in core/ (e.g. RequestController.RequestView).
If you change one side, change the other.
"""

from datetime import datetime
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

    def _get(self, path: str):
        resp = self._http.get(path)
        resp.raise_for_status()
        return resp.json()
