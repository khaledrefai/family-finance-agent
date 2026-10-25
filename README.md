# Family Finance Coach

Live at https://family-finance-coach.com (test server).

An agentic family accountant and money coach. Prototype for the Dubai Chambers "Create AI Agents" championship.
**No real money moves. No live bank connections. The wallet holds clearly labelled demo money only.**

## What is where

| Folder | What | Tech |
|---|---|---|
| `core/` | The core backend: families, members, roles, login, requests and approvals, and (coming) ledger, budgets, rules, demo wallet and the money engine. All money maths lives here. | Java 21, Spring Boot 3.5, Spring Security JWT, Flyway, PostgreSQL |
| `agents/` | The AI agents: an orchestrator that routes each message to Bookkeeper, Gatekeeper, Shopper, Coach or Planner. Talks to `core` only through its typed API. | Python 3.13, FastAPI, LangGraph |
| `web/` | The phone web app (installable PWA) with a parent view and a child view. | Next.js 16, TypeScript |
| `deploy.sh` + `docker-compose.yml` | The one deploy script. | Docker Compose |
| `video/` | The story video (two men, one salary, then the real app) and how to make it again. | Playwright, ffmpeg, Kokoro |

## Run it

```bash
cp .env.example .env      # then edit the two secrets
./deploy.sh               # builds and starts db, core, agents, web
open http://localhost:3000
```

`/` is the landing page (what the app does, with the coach); the app itself starts at `/login`, which is also
where the installed phone app opens. The landing page's demo family tiles open `/login?as=sara` and so on, with that
person filled in.

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
| `SERPAPI_KEY` | secret | Optional. Live UAE shop prices (SerpApi Google Shopping) for price search. Without it: the cache, then the saved price list. |
| `SEARCH_LIVE` | variable | Optional. `live` (default), `cache` (for recording, after a pre-warm) or `off` (saved price list only). |
| `PRICE_VERIFY_TOP` | variable | Optional. How many top offers each comparison re-checks (default 3; `0` saves searches). |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `MAIL_FROM` | variable or secret | Optional. Email alerts to parents. Empty `SMTP_HOST` = no email. Port 587 (STARTTLS) by default. |
| `SMTP_PASSWORD` | secret | Optional. The SMTP password (for Gmail, an app password). |
| `PUSH_CONTACT` | variable or secret | Optional. `mailto:you@example.com`, given to the browsers' push services. |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | secret | Optional. Web push keys; leave unset and the core makes them once and keeps them in the database. |
| `DOMAIN` | variable | Optional. The app's domain (default `family-finance-coach.com`; `none` = use the IP). |
| `CADDY` | variable | Optional. `on` starts our own Caddy on ports 80/443. Off by default (another web server has them). |
| `APP_PUBLIC_URL` | variable | Optional. The app's address for links in emails (default `https://DOMAIN`). |
| `WEB_PORT`, `AGENTS_LLM` | variable | Optional (3000, auto). |

The VPS needs Docker with the compose plugin, and the user must be allowed to run `docker`.
Releases live in `~/ffa/releases/<commit>`, `~/ffa/current` points at the running one,
and `~/ffa/shared/.env` holds the settings (the JWT secret is generated there once).

**HTTPS.** DNS (Namecheap): `A @` and `A www`, both pointing at the VPS IP. This VPS also hosts other sites,
so the web server already running there (on ports 80 and 443) forwards `family-finance-coach.com` and `www.` to
`http://127.0.0.1:3000`, with a Let's Encrypt certificate from certbot. For live updates it must not buffer
(nginx: `proxy_buffering off;`). The repo also has a Caddy option (`ops/caddy/Caddyfile`, GitHub variable
`CADDY=on`) for a server where nothing else uses 80/443; it is off by default. `http://VPS_HOST:3000` keeps working.

To reset the demo family on the server: `ssh` in, then `cd ~/ffa/current && COMPOSE_PROJECT_NAME=ffa ./deploy.sh reseed`.

## Live updates and notifications (week 5)

Anything a family member should hear about writes a row to the `family_event` table (the outbox) in the same
transaction as the change. The core reads new rows every 2 seconds and sends them on:

- **Open screens** get a ping over `GET /api/live` (Server-Sent Events) and reload that part: the parent's
  Waiting list, the kid's requests and wallet. About 2 seconds from tap to screen.
- **Phones** get a web push (Family > Notifications > Turn on for this phone). Browsers only allow this on
  `https://` or `localhost`, and iPhones only from the Home Screen app, so use https://family-finance-coach.com.
  On the plain `http://VPS_HOST:3000` address the live screens work but phone notifications can't be turned on.
- **Parents' email** for the core's own alerts, when SMTP is set up.
- **The bell** (Today, top right) keeps the last 30.

The core's own alerts come from fixed rules, checked every 15 minutes, each sent once: a bill due in the next 3 days,
a card bill due in 5 days (or in 14 when it won't be covered), a budget over / heading over / above usual, and on
Sunday from 8 am the plan for the week. "Check bills and budgets now" in Family runs them at once (and sends this
week's plan on any day, for the demo).

Approving a child's request now pays for it from their demo wallet when it holds enough; otherwise a parent pays.

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
| POST | `/api/requests/assess` | any member: "can I afford it, and is it allowed?" (children see only their own wallet side and the rules) |
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
| GET | `/api/rules` | parents: the family rules; a child: the rules that are on for them |
| POST | `/api/rules/drafts` | parents (or the Gatekeeper in an agent run): always saved as a draft |
| POST | `/api/rules/{id}/confirm`, `/api/rules/{id}/off` | a parent in the app (an agent run is refused) |
| GET | `/api/budgets` | parents: this month's budgets, spent so far, and the overspend alerts |
| GET | `/api/ways-to-save`, POST `/api/ways-to-save/words` | parents: up to 3 tips; the Planner saves this month's words |
| POST | `/api/ways-to-save/{key}/dismiss`, `/snooze` | a parent in the app (an agent run is refused) |
| POST | `/api/pricing/compare` | any member: `{intent, requesterId?, paidFromAccountId?}`; the shops' offers ranked by payable total, and the best one checked like a request (a child: own wallet side, no shop links) |
| GET | `/api/pricing/status` | parents: SEARCH_LIVE, whether a SerpApi key is set, how many items the saved price list has |
| POST | `/api/requests` with `comparisonId` + `offerKey` | any member: ask for one offer; the core takes title, total and shop from the saved comparison |

Agent service (the web app reaches it at `/agent/...`): `POST /chat`, `GET /nudge`, `POST /documents/{id}/read`,
`POST /ways-to-save` and `POST /prices` (`{text}`: the Shopper reads the words, the core compares).

## Price search (week 6)

Nobody pastes a link. A kid (Ask, "Something you want") or a parent (Money, Add spending, Compare prices) just says
what it is: "wireless headphones", "Sony WH-CH720N headphones", "air fryer". Then:

1. The **Shopper** agent (`agents/app/shopper.py`) turns the words into a `PurchaseIntent`: what to search for, and the
   brand and model code only if the person wrote them. Claude reads the words when it is on; a rule-based reader
   otherwise. That is all the agent does.
2. The **core** (`core/.../pricing/`) searches: a search from the last 24 hours in `price_search_cache`, otherwise a
   live SerpApi Google Shopping search for the UAE (`gl=ae`, 6 s at most), otherwise an older cached one, otherwise
   the saved price list (`core/src/main/resources/pricing/catalogue.json`, 90 items typed in by hand). The screen
   always says which: live, checked at a time, or "saved prices, not live".
3. `PriceEngine` (pure Java) leaves out parts and add-ons ("case for..."), converts each shop's price text to fils,
   works out the **payable total** = price + delivery + fees (delivery the shop doesn't list stays "not listed",
   never guessed), counts the same listing once, drops offers priced under half the usual price, and ranks by total.
   With a model code, only that exact item is ranked and other models are shown apart as "similar, not the same model".
4. The top 3 are **re-checked** with a narrower search (that item at that shop). Only a re-checked offer can be
   "Best price", so the promise is: the lowest re-checked total among the shops searched.
5. The best offer goes through the same check as any request: the kid's wallet and the family rules (Adam's peanut
   butter cups are still blocked), or the family's free cash and the card bill for a parent.
6. "Ask for this one" makes a request from the saved comparison, so the price on it is the core's, and the parent's
   "Why this?" shows which shop and how many offers were compared. Kids never see links to shop pages.

**Before recording the demo:** with `SEARCH_LIVE=live` and `AGENTS_LLM=off`, run `./deploy.sh prewarm` (or
`ops/prewarm-prices.sh https://family-finance-coach.com`) to cache the demo's searches, then set `SEARCH_LIVE=cache`
so the recording makes no live search. Check that the headphones' best total is still above the free cash on
Omar's Visa (about AED 258), or the card warning won't show. Each new search costs up to 4 SerpApi searches.
Tests replay recorded SerpApi answers (`core/src/test/resources/pricing/serpapi/`) and never call SerpApi.

## Family rules, overspend alerts and ways to save (week 4)

**Rules.** A parent writes a rule in their own words in Family ("No peanuts for Adam", "Layla: snacks up to AED 50 a
month"). The Gatekeeper (`agents/app/rules.py`) types it: Claude with a JSON schema, checked (the child must exist,
the amount must be one the parent wrote), or the rule-based parser when Claude is off. The core keeps it as a DRAFT;
only a parent's Turn on makes it ACTIVE, and turning on a rule that answers the same question archives the old one.
Kinds: approval limit, blocked category, blocked allergen, category limit (a month or a week) and monthly cap.

**The request check.** `RuleEngine` (pure Java, no database) checks every child's request against the active rules:
OK, NEEDS_PARENT or BLOCKED, with one line per rule. An allergen word in the name ("peanut butter cups") is blocked
and never reaches a parent. Other food can't be checked from a name, so it goes to a parent with "check the pack
label". A rule for one child beats the family-wide one. The Why page shows every rule checked.

**Overspend alerts.** `BudgetEngine` compares each family budget with this month's spending: over the limit, heading
over (from day 7, projected past 115%), or above the usual (125% of the last 3 months). Alerts show on Today and Budgets.

**Ways to save.** `WaysToSaveEngine` ranks recurring costs (subscriptions, outings over budget, rent against income,
phone and internet) and keeps the top 3, savings the engine can work out first. Once a month the Planner rewrites
them in warmer words; the core re-checks every number before it keeps them. A parent can dismiss a tip or snooze it
to next month. Information only, not financial advice.

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
