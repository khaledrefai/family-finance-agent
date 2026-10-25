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

## Test server

Every commit on `main` that passes CI is deployed to the VPS by `.github/workflows/deploy.yml`
(or run it by hand: Actions, Deploy, Run workflow). It reads the GitHub environment `production`:

| Name | Kind | What |
|---|---|---|
| `VPS_HOST`, `VPS_USER` | variable or secret | Where to SSH. Optional `VPS_PORT` (default 22). |
| `VPS_SSH_KEY` | secret | Private key whose public half is in the VPS user's `~/.ssh/authorized_keys`. |
| `POSTGRES_PASSWORD` | secret | Set once. The database keeps the first password, so don't change it later. |
| `ANTHROPIC_API_KEY` | secret | Optional. Empty means the rule-based answers. |
| `WEB_PORT`, `AGENTS_LLM` | variable | Optional (3000, auto). |

The VPS needs Docker with the compose plugin, and the user must be allowed to run `docker`.
Releases live in `~/ffa/releases/<commit>`, `~/ffa/current` points at the running one,
and `~/ffa/shared/.env` holds the settings (the JWT secret is generated there once).
To reset the demo family on the server: `ssh` in, then `cd ~/ffa/current && COMPOSE_PROJECT_NAME=ffa ./deploy.sh reseed`.

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
| POST | `/api/requests/assess` | any member: "can I afford it?" (children see only their own wallet side) |
| GET | `/api/plan/cashflow` | parents: free cash, free until pay day, the 12-month calendar, bills coming up |
| GET, POST | `/api/plan/income`, `/api/plan/commitments` | parents; through an agent run they are saved as drafts |
| POST | `/api/plan/income/{id}/receipts` | parents: what a commission income paid this month |
| GET | `/api/plan/drafts` | parents: what is waiting for Confirm |
| POST | `/api/plan/drafts/{type}/{id}/confirm`, `.../discard` | a parent in the app (an agent run is refused) |
| GET | `/api/plan/goals`, POST `/api/plan/options` | parents: relaxed, steady and faster paces for a goal |
| POST | `/api/plan/goals/{id}/pace` | a parent in the app |
| GET | `/api/plan/buckets`, `/api/plan/buckets/suggestion` | parents |
| POST | `/api/plan/buckets/draft`, `/api/plan/buckets/confirm` | draft: the Planner or a parent; confirm: a parent in the app |
| GET, POST | `/api/accounts`, POST `/api/accounts/{id}/balance` | parents: accounts, cards and cash, typed in |
| GET | `/api/accounts/{id}/card-check?amount=` | parents: the card's bill with this purchase, against free cash on its due date |
| POST | `/api/payments` | parents: a payment typed in; it must say what paid it |
| POST | `/api/agent-runs`, `/api/agent-runs/{id}/finish`, GET `/api/agent-runs` | the agent service opens and closes runs; parents read the trail |
| POST | `/api/documents` (multipart `file`, `kind` RECEIPT or STATEMENT, optional `accountId`) | a parent in the app; photo, PDF or CSV up to 5 MB |
| GET | `/api/documents`, `/api/documents/{id}`, `/api/documents/{id}/file` | parents (the Bookkeeper reads the file with the parent's token) |
| POST | `/api/documents/{id}/extraction`, `/api/documents/{id}/failed` | the Bookkeeper's typed draft (or why it couldn't read it) |
| POST | `/api/documents/{id}/confirm`, `/api/documents/{id}/discard` | a parent in the app (an agent run is refused) |
| GET | `/api/documents/samples/receipt`, `/api/documents/samples/statement` | parents: demo files dated to match the seed |
| GET | `/api/findings`, POST `/api/findings/decide` | parents: double charges and unlisted subscriptions; deciding is a parent's tap |
| GET, POST | `/api/gold`, POST `/api/gold/price`, `/api/gold/{id}/archive` | parents: gold in grams and AED at today's price |
| GET | `/api/wealth` | parents: bank + cash + gold − cards − loans |

Agent service (the web app reaches it at `/agent/...`): `POST /chat`, `GET /nudge` and `POST /documents/{id}/read`.

## Receipts and statements (week 3)

A parent uploads a file in Money > Uploads. The Bookkeeper (`agents/app/documents.py`) reads it into a typed draft:
CSV statement exports and text receipts by rules (`doc_parser.py`, exact, works with the AI off), photos and PDFs by
Claude with a JSON schema. Claude only copies what is printed; it never adds anything up. The core
(`core/.../documents/`) converts every amount, matches the account by the card's last 4 digits, and compares each
line with the ledger (same account, same amount, at most 3 days apart), so a receipt, a typed payment and the card
statement never count the same purchase twice. Nothing counts until a parent presses Confirm. `FindingsEngine` then
finds double charges and monthly payments missing from the commitments list. With the AI off, a photo is marked
"couldn't read" with a way forward (upload the CSV or type it in).

## Gold and family wealth (week 3)

Gold is logged in grams (stored as milligrams), purity and price paid, for a goal or for Invest aside. The engine
values it at the price per gram for its purity: the seeded sample price, or one a parent typed in from the shop.
There is no live price feed yet (source still to choose); the screen always says which price it used and when.
The app never suggests buying gold. Family wealth = bank + cash + gold − what is on the cards − loans left.

## The Planner's Claude step (week 2)

"Make our plan for this month" goes to the Planner (`agents/app/planner.py`). It reads the plan from the core,
saves a draft bucket split with the engine's own shares, and asks Claude only for the words. Claude answers as JSON
(structured outputs), with server-side refusal fallbacks on. If any number in Claude's text is not in the core's
facts, or Claude is off, slow (6 s) or fails, the rule-based template answers instead. Set `ANTHROPIC_API_KEY` to
turn it on; `AGENTS_LLM=off` forces the template (use it for a recording if the network is unsure). The model is
`AGENTS_LLM_MODEL` (default `claude-opus-5-5`). Every run is in "What the agent did" (`GET /api/agent-runs`),
including whether Claude or the fallback answered and why.

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
