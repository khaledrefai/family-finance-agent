# Data model (weeks 1 and 2)

Source of truth: `core/src/main/resources/db/migration/` (V1 core schema, V2 spend logging, V3 the money engine).
Money is always `BIGINT` fils (`*_minor` columns) plus a 3-letter currency. 30000 = AED 300.00.

| Table | Brief record | Notes |
|---|---|---|
| `family` | Family | `is_demo` marks the seeded family |
| `member` | Member (role, age band) | Role `PARENT`/`CHILD`; age band instead of birth date; children cannot have an email; parents must be `ADULT` |
| `consent` | Consent | Parent consent per child and purpose (`CHILD_ACCOUNT`, `AI_PROCESSING`). Child login needs `CHILD_ACCOUNT` |
| `source_document` | (document behind a Transaction) | `sha256` unique per family catches the same file uploaded twice |
| `ledger_transaction` | Transaction | `duplicate_hash` + `duplicate_of_id` for duplicate charges (week 3). `source` (`CHAT_LOG`, `SEED`...) and `logged_text` (the child's own words) added by V2 for spend logging. V3: `paid_from_account_id` (required for a payment a parent types in) and `transfer_to_account_id` (paying a card bill: a transfer, never spending) |
| `income` | Income source | V3: `kind` `FIXED` (salary, take-home pay), `VARIABLE` (commission: planned on a cautious base), `ONE_OFF` (bonus, frequency `ONCE`); `status` `DRAFT`/`ACTIVE`/`ARCHIVED` |
| `income_receipt` | (what a variable income paid) | One row per month. The cautious base = average of the 3 lowest of the last 6 full months |
| `commitment` | Commitment | Rent, school fees, loans, DEWA, du, subscriptions. V3: `status`, `is_estimate`, `summer_amount_minor`, `reserved_minor` (already put aside), `balance_left_minor` (loans), `paid_from_account_id`; frequency `CUSTOM` for listed dates |
| `commitment_occurrence` | (one dated payment) | Rent cheque dates, school terms, for `CUSTOM` commitments |
| `money_account` | Account / card | `BANK` (savings or current), `CREDIT_CARD` (limit, statement day, due day), `CASH`. Typed in by a parent; balances are estimates with `balance_as_of` |
| `budget` | Budget | Family-wide per category, or personal (pocket money) when `member_id` is set. V3: `bucket` (`EVERYDAY` or `FUN`) |
| `bucket_plan`, `bucket_plan_line` | BucketPlan | Shares in basis points (4264 = 42.64%). One `DRAFT` (from the Planner, the engine or a parent) and one `ACTIVE` per family. Only a parent confirms |
| `family_rule` | Rule (category, allergen, limit) | `BLOCK_CATEGORY`, `BLOCK_ALLERGEN`, `APPROVAL_LIMIT`; for one child or all |
| `spending_request` | Request | `SPEND`, `ASK_TO_BUY`, `CHALLENGE`, `REWARD`; `agent_summary` holds the Gatekeeper's explanation. V3: `assessment` (JSON from the affordability check, stored when the request is made) |
| `approval` | Approval | One per request. Trigger `approval_guard` blocks self-approval, non-parents and other families |
| `demo_wallet`, `demo_wallet_entry` | DemoWallet | `is_demo_money` can only be true. Balance = sum of append-only entries |
| `goal` | Goal | Per child or family-wide. V3: `tier` (`SURVIVAL`, `GOOD_LIFE`, `DREAM`), `monthly_minor` (the pace a parent picked), `priority` (lower is funded first) |
| `challenge` | Challenge | Can be linked to the request/approval that started it |
| `points_entry` | Points | Append-only; total = sum |
| `meal_plan` | MealPlan | Plan and shopping list as JSON |
| `agent_run`, `tool_execution` | AgentRun, ToolExecution (audit) | Written by the core, not the agent: every call carrying `X-Agent-Run-Id` is recorded. V3: `llm_used`, `fallback_reason`, `latency_ms`, tokens |

`family`, `member`, `spending_request`, `approval` and `ledger_transaction` have Java entities. The money engine
reads its tables with plain SQL (`plan/MoneyFacts.java`) into small records, and every amount is worked out in Java.

Anything an agent creates (through an agent run) is saved as `DRAFT` and ignored by the engine until a parent confirms it.

## Demo family (`db/seed/R__demo_family.sql`)

The Haddad Family (demo): Sara and Omar (parents), Layla 13, Adam 9. Dates are relative to the day you seed.
**The week 2 money figures are provisional** (taken from the app mockups) until khaled confirms them.

- Income: Omar's salary AED 28,000 (pay day in 20 days); Sara's commission, last 6 months 9,800 / 6,200 / 7,400 /
  6,500 / 11,200 / 6,800, so the plan uses a cautious AED 6,500.
- Commitments: rent AED 120,000 a year in 4 cheques (AED 20,000 already reserved), school fees AED 45,000 in 3 terms
  (half of term 2 reserved), car loan AED 2,100 a month (18 left, AED 38,400 owed), DEWA about AED 900 (AED 1,400 in
  summer), du AED 450, a forgotten streaming subscription AED 59.
- Budgets a month: groceries 3,000, eating out 1,500, entertainment 1,200, transport 1,000; pocket money Layla AED 100
  and Adam AED 60 a week.
- Accounts: ENBD current AED 11,650, ADCB savings AED 32,600, cash AED 650, Omar's Visa owing AED 5,950 (bill due
  the day before pay day), Sara's Mastercard owing AED 1,350.
- Goals: emergency buffer AED 25,000 (10,000 saved, 5,000 a month), summer trip AED 12,000 (3,600 saved, 1,500 a
  month), home deposit AED 150,000; Layla's headphones (AED 120 of 300) and Adam's building set (AED 72 of 160).
- The story it sets up: about AED 258 is free until pay day, so Layla's AED 300 headphones on the Visa show the
  card warning; March is the tight month because school term 3 comes before its reserve is full.

Rules: Adam peanut allergy, Adam no fizzy drinks, no in-game purchases for anyone, AED 50 approval limit.
About 10 weeks of logged kid spending: Layla mostly canteen, bubble tea and one-offs; Adam mostly Pokemon cards and
stickers, plus a Robux card and a cola that break family rules. Nothing is logged for today, so the nudge shows.
The kids' wallets are pocket money minus what they logged and what they moved into their goals.
