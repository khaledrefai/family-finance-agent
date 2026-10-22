"""A fake core API for agent tests, so they run without Java or a database."""

import json
from pathlib import Path

import httpx
import pytest

from app.core_client import CoreClient
from app.main import app, core_for_caller

FIXTURES = Path(__file__).parent / "fixtures"

LAYLA = "00000000-0000-0000-0000-0000000000c1"
ADAM = "00000000-0000-0000-0000-0000000000c2"
MEMBERS = [
    {"id": "00000000-0000-0000-0000-0000000000a1", "displayName": "Sara", "role": "PARENT", "ageBand": "ADULT"},
    {"id": "00000000-0000-0000-0000-0000000000a2", "displayName": "Omar", "role": "PARENT", "ageBand": "ADULT"},
    {"id": LAYLA, "displayName": "Layla", "role": "CHILD", "ageBand": "TEEN_13_17"},
    {"id": ADAM, "displayName": "Adam", "role": "CHILD", "ageBand": "CHILD_6_9"},
]


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


class FakeCore:
    """Answers like the Spring Boot core would, and remembers what was posted."""

    def __init__(self, who: str = "Layla"):
        self.who = next(m for m in MEMBERS if m["displayName"] == who)
        self.posted: list[dict] = []
        self.summary_calls: list[dict] = []
        self.logged_today = False

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/me":
            return httpx.Response(200, json={"memberId": self.who["id"], "displayName": self.who["displayName"],
                                             "role": self.who["role"], "ageBand": self.who["ageBand"],
                                             "familyId": "00000000-0000-0000-0000-00000000f001",
                                             "familyName": "The Haddad Family (demo)", "currency": "AED"})
        if path == "/api/family/members":
            return httpx.Response(200, json=MEMBERS)
        if path == "/api/spend" and request.method == "POST":
            body = json.loads(request.content)
            self.posted.append(body)
            return httpx.Response(201, json=load("log_result.json"))
        if path == "/api/spend/summary":
            self.summary_calls.append(dict(request.url.params))
            member = request.url.params.get("memberId", self.who["id"])
            summary = load("summary_layla.json") if member == LAYLA else load("summary_adam.json")
            return httpx.Response(200, json=summary)
        if path == "/api/spend/status":
            return httpx.Response(200, json={"today": "2026-10-22", "dayOfWeek": "THURSDAY", "schoolDay": True,
                                             "loggedToday": self.logged_today, "lastLoggedOn": "2026-10-21",
                                             "todayTotal": {"minor": 0, "currency": "AED", "text": "AED 0"}})
        return httpx.Response(404, json={"detail": "not found"})

    def client(self) -> CoreClient:
        return CoreClient("t0ken", base_url="http://core", transport=httpx.MockTransport(self.handler))


@pytest.fixture
def fake_core():
    core = FakeCore()
    app.dependency_overrides[core_for_caller] = core.client
    yield core
    app.dependency_overrides.clear()
