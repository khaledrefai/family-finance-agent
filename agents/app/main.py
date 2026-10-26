"""Agent service HTTP API (FastAPI). Run locally with:  uvicorn app.main:app --reload --port 8000"""

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from uuid import UUID

from .core_client import CoreClient
from .documents import read_document
from .graph import orchestrator
from .spend_story import nudge
from .core_client import CoreError, WaysToSave
from .ways_to_save import write as write_ways_to_save
from . import outings, shopper

app = FastAPI(title="Family Finance Coach - agent service", version="0.1.0")


class ChatIn(BaseModel):
    message: str


class ChatOut(BaseModel):
    agent: str
    reply: str


class PricesIn(BaseModel):
    text: str
    requesterId: UUID | None = None       # a parent checking for a child
    paidFromAccountId: UUID | None = None  # a parent's card, for the card bill check


class PricesOut(BaseModel):
    intent: dict | None
    comparison: dict | None   # the core's comparison as it sent it; the app draws the offers from it
    reply: str


class NudgeOut(BaseModel):
    nudge: str | None


def core_for_caller(authorization: str | None = Header(default=None)) -> CoreClient:
    """A core client that acts as the caller. Tests replace this with a fake core."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")
    return CoreClient(authorization.removeprefix("Bearer "))


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}


@app.post("/chat", response_model=ChatOut)
def chat(body: ChatIn, core: CoreClient = Depends(core_for_caller)) -> ChatOut:
    # The member's token is passed straight through to the core API, so an agent can never do more
    # than the member themselves is allowed to.
    me = core.me()
    result = orchestrator.invoke({"message": body.message, "role": me.role.value},
                                 config={"configurable": {"core": core, "me": me}})
    return ChatOut(agent=result["agent"].value, reply=result["reply"])


class ReadOut(BaseModel):
    documentId: UUID | None
    status: str | None
    reply: str


@app.post("/documents/{doc_id}/read", response_model=ReadOut)
def read(doc_id: UUID, core: CoreClient = Depends(core_for_caller)) -> ReadOut:
    """The Bookkeeper reads an uploaded receipt or statement into a draft for a parent to confirm."""
    doc, reply = read_document(core, core.me(), doc_id)
    return ReadOut(documentId=doc.id if doc else None, status=doc.status if doc else None, reply=reply)


@app.post("/prices", response_model=PricesOut)
def prices(body: PricesIn, core: CoreClient = Depends(core_for_caller)) -> PricesOut:
    """'Wireless headphones' -> the shops' prices. The Shopper reads the words; the core searches, ranks and checks."""
    intent, comparison, reply = shopper.compare(core, core.me(), body.text[:300], None, body.requesterId,
                                                body.paidFromAccountId)
    return PricesOut(intent=intent.model_dump(exclude_none=True, mode="json") if intent else None,
                     comparison=comparison, reply=reply)


class OutingsIn(BaseModel):
    text: str = ""
    when: str | None = None   # THIS_MONTH or NEXT_MONTH, from the app's month toggle


class OutingsOut(BaseModel):
    plan: dict | None         # the core's plan as it sent it; the app draws the outings from it
    reply: str


@app.post("/outings", response_model=OutingsOut)
def plan_outings(body: OutingsIn, core: CoreClient = Depends(core_for_caller)) -> OutingsOut:
    """'Something free this weekend' -> outings that fit the fun budget. The Planner reads; the core prices and checks."""
    when = body.when if body.when in ("THIS_MONTH", "NEXT_MONTH") else None
    plan, reply = outings.plan(core, core.me(), body.text[:300] or "cheaper outings", None, when)
    return OutingsOut(plan=plan, reply=reply)


@app.get("/nudge", response_model=NudgeOut)
def get_nudge(core: CoreClient = Depends(core_for_caller)) -> NudgeOut:
    """'Did you buy lunch today?' for a child who hasn't logged anything today. Parents get nothing."""
    if core.me().role.value != "CHILD":
        return NudgeOut(nudge=None)
    return NudgeOut(nudge=nudge(core.spend_status()))


@app.post("/ways-to-save", response_model=WaysToSave)
def ways_to_save(core: CoreClient = Depends(core_for_caller)) -> WaysToSave:
    """This month's Ways to save card for a parent. The Planner writes its words once a month; later calls just read it."""
    try:
        return write_ways_to_save(core, core.me())
    except CoreError as e:
        raise HTTPException(status_code=e.status, detail=e.detail)
