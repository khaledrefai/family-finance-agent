import httpx
from fastapi.testclient import TestClient

from app.core_client import CoreClient, RequestStatus
from app.graph import Agent, orchestrator
from app.main import app


def route(text: str) -> Agent:
    return orchestrator.invoke({"message": text})["agent"]


def test_routes_to_the_right_agent():
    assert route("Can I buy these headphones from noon?") == Agent.GATEKEEPER
    assert route("I uploaded the bank statement") == Agent.BOOKKEEPER
    assert route("Gold or an apartment over 10 years?") == Agent.PLANNER
    assert route("Make a meal plan for this week") == Agent.SHOPPER
    assert route("I want to start a saving challenge") == Agent.COACH


def test_unknown_message_goes_to_gatekeeper():
    assert route("hello") == Agent.GATEKEEPER


def test_chat_requires_a_token():
    client = TestClient(app)
    assert client.post("/chat", json={"message": "hi"}).status_code == 401
    ok = client.post("/chat", json={"message": "can I buy a toy?"}, headers={"Authorization": "Bearer x"})
    assert ok.status_code == 200
    assert ok.json()["agent"] == "gatekeeper"


def test_core_client_parses_typed_responses_and_sends_token():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer t0ken"
        return httpx.Response(200, json=[{
            "id": "11111111-1111-1111-1111-111111111111",
            "requesterId": "00000000-0000-0000-0000-0000000000c1",
            "kind": "ASK_TO_BUY", "title": "Headphones", "amountMinor": 30000, "currency": "AED",
            "status": "PENDING", "createdAt": "2026-10-22T10:00:00Z",
        }])

    client = CoreClient("t0ken", base_url="http://core", transport=httpx.MockTransport(handler))
    [req] = client.list_requests()
    assert req.amountMinor == 30000
    assert req.status is RequestStatus.PENDING
