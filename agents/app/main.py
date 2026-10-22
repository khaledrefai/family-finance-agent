"""Agent service HTTP API (FastAPI). Run locally with:  uvicorn app.main:app --reload --port 8000"""

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

from .graph import orchestrator

app = FastAPI(title="Family Finance Agent - agent service", version="0.1.0")


class ChatIn(BaseModel):
    message: str


class ChatOut(BaseModel):
    agent: str
    reply: str


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}


@app.post("/chat", response_model=ChatOut)
def chat(body: ChatIn, authorization: str | None = Header(default=None)) -> ChatOut:
    # The member's token is passed straight through to the core API by the agents (week 2+),
    # so an agent can never do more than the member themselves is allowed to.
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")
    result = orchestrator.invoke({"message": body.message})
    return ChatOut(agent=result["agent"].value, reply=result["reply"])
