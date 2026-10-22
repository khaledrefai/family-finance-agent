# Data model (week 1)

Source of truth: `core/src/main/resources/db/migration/V1__core_schema.sql`.
Money is always `BIGINT` fils (`*_minor` columns) plus a 3-letter currency. 30000 = AED 300.00.

| Table | Brief record | Notes |
|---|---|---|
| `family` | Family | `is_demo` marks the seeded family |
| `member` | Member (role, age band) | Role `PARENT`/`CHILD`; age band instead of birth date; children cannot have an email; parents must be `ADULT` |
| `consent` | Consent | Parent consent per child and purpose (`CHILD_ACCOUNT`, `AI_PROCESSING`). Child login needs `CHILD_ACCOUNT` |
| `source_document` | (document behind a Transaction) | `sha256` unique per family catches the same file uploaded twice |
| `ledger_transaction` | Transaction | `duplicate_hash` + `duplicate_of_id` for duplicate charges (week 3). `source` (`CHAT_LOG`, `SEED`...) and `logged_text` (the child's own words) added by V2 for spend logging |
| `income` | (household income) | Needed for "what's free this month" |
| `commitment` | Commitment | Rent, school fees, loan instalments, subscriptions, with `next_due_on` |
| `budget` | Budget | Family-wide per category, or personal (pocket money) when `member_id` is set |
| `family_rule` | Rule (category, allergen, limit) | `BLOCK_CATEGORY`, `BLOCK_ALLERGEN`, `APPROVAL_LIMIT`; for one child or all |
| `spending_request` | Request | `SPEND`, `ASK_TO_BUY`, `CHALLENGE`, `REWARD`; `agent_summary` holds the Gatekeeper's explanation |
| `approval` | Approval | One per request. Trigger `approval_guard` blocks self-approval, non-parents and other families |
| `demo_wallet`, `demo_wallet_entry` | DemoWallet | `is_demo_money` can only be true. Balance = sum of append-only entries |
| `goal` | Goal | Per child or family-wide |
| `challenge` | Challenge | Can be linked to the request/approval that started it |
| `points_entry` | Points | Append-only; total = sum |
| `meal_plan` | MealPlan | Plan and shopping list as JSON |
| `agent_run`, `tool_execution` | AgentRun, ToolExecution (audit) | A state-changing tool call must record whose permission covered it |

Only `family`, `member`, `spending_request` and `approval` have Java entities so far. The other tables get
entities in the week their feature is built, so we don't maintain code nobody uses yet.

## Demo family (`db/seed/R__demo_family.sql`)

The Haddad Family (demo): Sara and Omar (parents), Layla 13, Adam 9.
Salaries AED 18,000 + 15,000; rent AED 20,000 quarterly (due in 40 days); school fees AED 14,500 (due in 18 days);
car instalment AED 1,850 (due in 6 days); a forgotten streaming subscription AED 59.
Rules: Adam peanut allergy, Adam no fizzy drinks, no in-game purchases for anyone, AED 50 approval limit.
Layla has a headphones goal (AED 60 of AED 300 saved) and a demo wallet. Dates are relative to the day you seed.
Pocket money: Layla AED 100 a week (includes canteen money), Adam AED 60, paid every Sunday (about AED 690 a month in total).
About 10 weeks of logged kid spending: Layla mostly canteen, bubble tea and one-offs; Adam mostly Pokemon cards and
stickers, plus a Robux card and a cola that break family rules. Nothing is logged for today, so the nudge shows.
