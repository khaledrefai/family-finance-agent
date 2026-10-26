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
        self.runs: list[dict] = []          # agent runs opened, with what they finished as
        self.run_headers: list[str] = []    # X-Agent-Run-Id seen on plan calls
        self.bucket_drafts: list[dict] = []
        self.file: tuple[bytes, str] = (b"", "text/plain")  # the uploaded document the Bookkeeper reads
        self.extractions: list[dict] = []
        self.failures: list[str] = []
        self.doc_headers: list[str] = []
        self.found: list[dict] = []
        self.assess_fixture = "assess_layla.json"
        self.assess_bodies: list[dict] = []
        self.rule_drafts: list[dict] = []
        self.rule_headers: list[str] = []
        self.ways = load("ways_to_save.json")
        self.tip_words: list[dict] = []
        self.compare_fixture = "compare_sony.json"
        self.compare_bodies: list[dict] = []
        self.compare_headers: list[str] = []
        self.outings_board = load("outings_board.json")
        self.outing_plans: list[dict] = []
        self.outing_headers: list[str] = []
        self.meal_plans: list[dict] = []
        self.meal_headers: list[str] = []

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
        if path == "/api/agent-runs" and request.method == "POST":
            self.runs.append(json.loads(request.content))
            return httpx.Response(201, json={"id": "11111111-1111-1111-1111-%012d" % len(self.runs)})
        if path.startswith("/api/agent-runs/") and path.endswith("/finish"):
            self.runs[-1]["finished"] = json.loads(request.content)
            return httpx.Response(204)
        if path.startswith("/api/plan/") or path == "/api/requests/assess":
            self.run_headers.append(request.headers.get("X-Agent-Run-Id", ""))
        if path.startswith("/api/documents/"):
            self.doc_headers.append(request.headers.get("X-Agent-Run-Id", ""))
            return self.documents(request, path)
        if path == "/api/findings":
            return httpx.Response(200, json=self.found)
        if path == "/api/plan/cashflow":
            return httpx.Response(200, json=load("cashflow.json"))
        if path == "/api/plan/goals":
            return httpx.Response(200, json=load("goals.json"))
        if path == "/api/plan/buckets/suggestion":
            return httpx.Response(200, json=load("bucket_suggestion.json"))
        if path == "/api/plan/options":
            return httpx.Response(200, json=load("options_trip.json"))
        if path == "/api/plan/buckets/draft":
            self.bucket_drafts.append(json.loads(request.content))
            return httpx.Response(200, json=load("buckets.json"))
        if path == "/api/requests/assess":
            self.assess_bodies.append(json.loads(request.content))
            return httpx.Response(200, json=load(self.assess_fixture))
        if path == "/api/pricing/compare":
            self.compare_bodies.append(json.loads(request.content))
            self.compare_headers.append(request.headers.get("X-Agent-Run-Id", ""))
            return httpx.Response(200, json=load(self.compare_fixture))
        if path == "/api/outings":
            return httpx.Response(200, json=self.outings_board)
        if path == "/api/outings/plan":
            body = json.loads(request.content)
            self.outing_plans.append(body)
            self.outing_headers.append(request.headers.get("X-Agent-Run-Id", ""))
            plan = load("outings_plan_next.json" if body["when"] == "NEXT_MONTH" else "outings_plan_this.json")
            if body.get("keys"):
                picks = [p for p in plan["picks"] if p["key"] in body["keys"]]
                plan = {**plan, "picks": picks, "facts": [f for f in plan["facts"]
                                                           if not f.split(":")[0] in {p["name"] for p in plan["picks"]}
                                                           or f.split(":")[0] in {p["name"] for p in picks}]}
            return httpx.Response(200, json=plan)
        if path == "/api/meals":
            return httpx.Response(200, json=load("meals_board.json"))
        if path == "/api/meals/plan":
            self.meal_plans.append(json.loads(request.content))
            self.meal_headers.append(request.headers.get("X-Agent-Run-Id", ""))
            return httpx.Response(200, json=load("meals_plan.json"))
        if path == "/api/rules/drafts":
            body = json.loads(request.content)
            self.rule_drafts.append(body)
            self.rule_headers.append(request.headers.get("X-Agent-Run-Id", ""))
            if body["kind"] not in ("BLOCK_CATEGORY", "BLOCK_ALLERGEN", "APPROVAL_LIMIT", "CATEGORY_LIMIT", "MONTHLY_CAP"):
                return httpx.Response(400, json={"detail": "Say what kind of rule it is"})
            label = body["said"][0].upper() + body["said"][1:]
            return httpx.Response(201, json={"id": "22222222-2222-2222-2222-%012d" % len(self.rule_drafts),
                                             "kind": body["kind"], "status": "DRAFT", "label": label,
                                             "chips": ["Typed", "All the kids"], "replaces": []})
        if path == "/api/ways-to-save" and request.method == "GET":
            return httpx.Response(200, json=self.ways)
        if path == "/api/ways-to-save/words":
            words = json.loads(request.content)["tips"]
            self.tip_words.extend(words)
            byKey = {w["key"]: w for w in words}
            tips = [{**t, "text": byKey[t["key"]]["text"], "writtenBy": byKey[t["key"]]["writtenBy"]}
                    if t["key"] in byKey else t for t in self.ways["tips"]]
            self.ways = {**self.ways, "tips": tips, "needsWords": False}
            return httpx.Response(200, json=self.ways)
        return httpx.Response(404, json={"detail": "not found"})

    def documents(self, request: httpx.Request, path: str) -> httpx.Response:
        doc_id = path.split("/")[3]
        view = {"id": doc_id, "kind": "RECEIPT", "fileName": "upload", "contentType": self.file[1], "status": "UPLOADED",
                "counts": {"lines": 0, "added": 0, "alreadyLogged": 0, "possibleDuplicates": 0},
                "summary": "Uploaded. Not read yet.", "facts": []}
        if path.endswith("/file"):
            return httpx.Response(200, content=self.file[0], headers={"content-type": self.file[1]})
        if path.endswith("/extraction"):
            body = json.loads(request.content)
            self.extractions.append(body)
            n = len(body.get("lines", []))
            return httpx.Response(200, json={**view, "kind": body["kind"], "status": "EXTRACTED",
                                             "counts": {"lines": n, "added": n, "alreadyLogged": 0, "possibleDuplicates": 0},
                                             "summary": f"{n} lines read by the core."})
        if path.endswith("/failed"):
            self.failures.append(json.loads(request.content)["reason"])
            return httpx.Response(200, json={**view, "status": "FAILED", "summary": self.failures[-1]})
        return httpx.Response(200, json=view)

    def client(self) -> CoreClient:
        return CoreClient("t0ken", base_url="http://core", transport=httpx.MockTransport(self.handler))


@pytest.fixture
def fake_core():
    core = FakeCore()
    app.dependency_overrides[core_for_caller] = core.client
    yield core
    app.dependency_overrides.clear()
