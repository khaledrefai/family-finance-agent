# Family Finance Agent

An agentic family accountant and money coach. Prototype for the Dubai Chambers "Create AI Agents" championship.
**No real money moves. No live bank connections. The wallet holds clearly labelled demo money only.**

## What is where

| Folder | What | Tech |
|---|---|---|
| `core/` | The core backend: families, members, roles, login, requests and approvals, and (coming) ledger, budgets, rules, demo wallet and the money engine. All money maths lives here. | Java 21, Spring Boot 3.5, Spring Security JWT, Flyway, PostgreSQL |
| `agents/` | The AI agents: an orchestrator that routes each message to Bookkeeper, Gatekeeper, Shopper, Coach or Planner. Talks to `core` only through its typed API. | Python 3.13, FastAPI, LangGraph |
| `web/` | The phone web app (installable PWA) with a parent view and a child view. | Next.js 16, TypeScript |
| `extension/` | Chrome extension that pauses checkout (demo only, week 8, first to cut). | Chrome MV3 |
| `deploy.sh` + `docker-compose.yml` | The one deploy script. | Docker Compose |

## Run it

```bash
cp .env.example .env      # then edit the two secrets
./deploy.sh               # builds and starts db, core, agents, web
open http://localhost:3000
```

Demo logins (password `demo1234` for all):

| Login | Who | Role |
|---|---|---|
| `sara` | Mum | Parent |
| `omar` | Dad | Parent |
| `layla` | 13 years old | Child |
| `adam` | 9 years old, peanut allergy | Child |

Before every demo recording: `./deploy.sh reseed` resets the demo family to its starting state.

## Tests

```bash
./deploy.sh test
```

- `core`: `mvn test`. Starts a throwaway PostgreSQL in Docker (Testcontainers). Without Docker, point it at any
  Postgres with `TEST_DB_URL=jdbc:postgresql://localhost:5432/ffa_test` (user/password `ffa`/`ffa`, or set
  `TEST_DB_USER`/`TEST_DB_PASSWORD`).
- `agents`: `pytest`.
- `web`: TypeScript type check.

The permission rule "a child can never approve their own request" is tested three ways:
1. `ApprovalPolicyTest`: the pure Java rule, every role and status combination.
2. `RequestApprovalIntegrationTest`: through the real HTTP API with real logins.
3. The same test checks the database trigger `approval_guard` refuses a self-approval even if the Java code had a bug.

## Running parts on their own (for development)

```bash
# Database only
docker compose up -d db

# Core (from core/), with the demo family
SPRING_PROFILES_ACTIVE=demo DB_PASSWORD=<from .env> mvn spring-boot:run

# Agents (from agents/)
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --reload --port 8000

# Web (from web/)
npm install && npm run dev       # forwards /api/* to http://localhost:8080
```

## Core API so far

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/login` | anyone, returns a JWT |
| GET | `/api/me` | any member |
| GET | `/api/family/members` | any member |
| POST | `/api/requests` | any member |
| GET | `/api/requests` | parents see all; children see their own |
| POST | `/api/requests/{id}/approve` | a parent, not the requester |
| POST | `/api/requests/{id}/reject` | a parent, not the requester |
| POST | `/api/spend` | any member, logs their own spending (amount as typed text, the core converts to fils) |
| GET | `/api/spend?memberId=&month=` | parents: anyone in the family; children: themselves |
| GET | `/api/spend/summary?memberId=&month=` | same; every number the agent may use ("where did the money go?") |
| GET | `/api/spend/status` | my logging today, for the agent's nudge |

Agent service (the web app reaches it at `/agent/...`): `POST /chat` and `GET /nudge`.

## Spend logging (spike)

Kids tell the agent what they bought ("12 dirhams shawarma"). The Bookkeeper parses it into typed items
(`agents/app/spend_parser.py`, plain rules, no LLM yet), the core records it, and a parent can ask
"Where did Layla's money go this month?". The answer (`agents/app/spend_story.py`) only copies numbers from
`/api/spend/summary`; a test checks every number in it came from the core.

## Ground rules (from the project brief)

- Agents never do arithmetic. They ask `core`, which uses `Money` (whole fils in a `long`, no floats).
- Every action that changes something needs permission from the right role, checked in `core`.
- The agent service calls `core` with the member's own token, so an agent can never do more than that member.
- Children's data is minimal: display name, age band, username. No birth date, email or phone. Child accounts need a parent's consent (PDPL).
- Allergens: warn, never certify. Always say "check the pack label".

See [docs/data-model.md](docs/data-model.md) for the database tables.
