"""Agent service HTTP API (FastAPI). Run locally with:  uvicorn app.main:app --reload --port 8000"""

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from .core_client import CoreClient
from .graph import orchestrator
from .spend_story import nudge

app = FastAPI(title="Family Finance Agent - agent service", version="0.1.0")


class ChatIn(BaseModel):
    message: str


class ChatOut(BaseModel):
    agent: str
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


@app.get("/nudge", response_model=NudgeOut)
def get_nudge(core: CoreClient = Depends(core_for_caller)) -> NudgeOut:
    """'Did you buy lunch today?' for a child who hasn't logged anything today. Parents get nothing."""
    if core.me().role.value != "CHILD":
        return NudgeOut(nudge=None)
    return NudgeOut(nudge=nudge(core.spend_status()))
