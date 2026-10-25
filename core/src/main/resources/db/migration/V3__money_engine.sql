-- Week 2: the money engine's inputs.
--
--   * income sources with a kind (FIXED salary, VARIABLE commission, ONE_OFF bonus)
--   * commitments with every UAE kind, optional dated occurrences (rent cheques, school terms)
--   * accounts and cards typed in by hand, and what paid each payment
--   * money buckets (a draft split a parent confirms)
--   * drafts: anything an agent fills in waits as DRAFT until a parent confirms it
--   * agent run audit columns (model, latency, tokens, whether the LLM or the fallback answered)
--
-- Everything is additive or a tightening of a CHECK; no data is lost.

-- ---------------------------------------------------------------- family
-- Phase 1 is UAE only. The country field is groundwork for later countries (study, "Going international").
ALTER TABLE family ADD COLUMN country CHAR(2) NOT NULL DEFAULT 'AE';

-- ---------------------------------------------------------------- record status (drafts)
-- DRAFT = an agent or a parent typed it, nobody confirmed yet; the engine ignores it.
-- ACTIVE = counts. ARCHIVED = kept for history, ignored.

-- ---------------------------------------------------------------- income sources
ALTER TABLE income
    ADD COLUMN kind              VARCHAR(10)  NOT NULL DEFAULT 'FIXED' CHECK (kind IN ('FIXED', 'VARIABLE', 'ONE_OFF')),
    -- VARIABLE only: the parent's own planning base. NULL = the engine's cautious base (avg of the 3 lowest of 6 months)
    ADD COLUMN base_override_minor BIGINT CHECK (base_override_minor >= 0),
    ADD COLUMN status            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    ADD COLUMN source            VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'SETUP_CHAT', 'DOCUMENT', 'SEED')),
    ADD COLUMN created_by        UUID REFERENCES member (id),
    ADD COLUMN confirmed_by      UUID REFERENCES member (id),
    ADD COLUMN created_at        TIMESTAMPTZ  NOT NULL DEFAULT now();
UPDATE income SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'ARCHIVED' END;
ALTER TABLE income DROP COLUMN active;
-- A one-off (bonus, Eid money) happens once, on next_on, and only counts once it has arrived.
ALTER TABLE income DROP CONSTRAINT income_frequency_check;
ALTER TABLE income ADD CONSTRAINT income_frequency_check CHECK (frequency IN ('ONCE', 'WEEKLY', 'MONTHLY', 'YEARLY'));
ALTER TABLE income ADD CONSTRAINT income_one_off_is_once CHECK ((kind = 'ONE_OFF') = (frequency = 'ONCE'));
CREATE INDEX income_family_idx ON income (family_id, status);

-- What a VARIABLE income actually paid each month (from statements or typed in). The cautious base is computed from it.
CREATE TABLE income_receipt (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    income_id     UUID        NOT NULL REFERENCES income (id) ON DELETE CASCADE,
    received_on   DATE        NOT NULL,
    amount_minor  BIGINT      NOT NULL CHECK (amount_minor >= 0),
    UNIQUE (income_id, received_on)
);

-- ---------------------------------------------------------------- accounts and cards (typed in, no bank links)
CREATE TABLE money_account (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id        UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    owner_member_id  UUID         REFERENCES member (id),          -- NULL = the family's (cash at home)
    kind             VARCHAR(12)  NOT NULL CHECK (kind IN ('BANK', 'CREDIT_CARD', 'CASH')),
    label            VARCHAR(80)  NOT NULL,
    last4            CHAR(4)      CHECK (last4 ~ '^[0-9]{4}$'),
    -- BANK and CASH: money in the account. CREDIT_CARD: what is owed on the card. Both as typed, on balance_as_of.
    balance_minor    BIGINT       NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    balance_as_of    DATE         NOT NULL,
    is_savings       BOOLEAN      NOT NULL DEFAULT FALSE,           -- a savings account is not spendable day to day
    limit_minor      BIGINT       CHECK (limit_minor > 0),
    statement_day    SMALLINT     CHECK (statement_day BETWEEN 1 AND 31),
    due_day          SMALLINT     CHECK (due_day BETWEEN 1 AND 31),
    currency         CHAR(3)      NOT NULL DEFAULT 'AED',
    status           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT card_has_cycle CHECK (kind <> 'CREDIT_CARD' OR (limit_minor IS NOT NULL AND statement_day IS NOT NULL AND due_day IS NOT NULL)),
    CONSTRAINT only_cards_have_cycle CHECK (kind = 'CREDIT_CARD' OR (limit_minor IS NULL AND statement_day IS NULL AND due_day IS NULL)),
    CONSTRAINT only_banks_save CHECK (kind = 'BANK' OR NOT is_savings)
);
CREATE INDEX money_account_family_idx ON money_account (family_id, status);

-- Every payment says what paid it. A card bill payment is a transfer between two accounts, never spending.
ALTER TABLE ledger_transaction
    ADD COLUMN paid_from_account_id     UUID REFERENCES money_account (id),
    ADD COLUMN transfer_to_account_id   UUID REFERENCES money_account (id),
    -- NOT VALID: payments typed before week 2 had nowhere to say what paid them; every new one must.
    ADD CONSTRAINT typed_payment_has_account CHECK (source <> 'MANUAL' OR paid_from_account_id IS NOT NULL) NOT VALID;
CREATE INDEX ledger_tx_account_idx ON ledger_transaction (paid_from_account_id, occurred_on);

-- ---------------------------------------------------------------- commitments
ALTER TABLE commitment
    ADD COLUMN status              VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    ADD COLUMN source              VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'SETUP_CHAT', 'DOCUMENT', 'SEED')),
    -- An estimate (DEWA) rather than a fixed amount. Shown as "about" and averaged from bills later.
    ADD COLUMN is_estimate         BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Higher amount for June to September (DEWA, district cooling). NULL = same all year.
    ADD COLUMN summer_amount_minor BIGINT CHECK (summer_amount_minor > 0),
    -- Already set aside for the next lumpy payment (rent cheque, school term).
    ADD COLUMN reserved_minor      BIGINT       NOT NULL DEFAULT 0 CHECK (reserved_minor >= 0),
    -- LOAN only: what is still owed, for the family wealth view.
    ADD COLUMN balance_left_minor  BIGINT CHECK (balance_left_minor >= 0),
    ADD COLUMN paid_from_account_id UUID REFERENCES money_account (id),
    ADD COLUMN created_by          UUID REFERENCES member (id),
    ADD COLUMN confirmed_by        UUID REFERENCES member (id);
UPDATE commitment SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'ARCHIVED' END;
ALTER TABLE commitment DROP COLUMN active;
ALTER TABLE commitment DROP CONSTRAINT commitment_kind_check;
ALTER TABLE commitment ADD CONSTRAINT commitment_kind_check CHECK (kind IN
    ('RENT', 'SCHOOL_FEES', 'UTILITY', 'TELECOM', 'CREDIT_CARD', 'LOAN', 'INSURANCE', 'SUBSCRIPTION', 'OTHER'));
-- CUSTOM = the dates are listed in commitment_occurrence (rent cheque dates from the tenancy contract, school terms).
ALTER TABLE commitment DROP CONSTRAINT commitment_frequency_check;
ALTER TABLE commitment ADD CONSTRAINT commitment_frequency_check CHECK (frequency IN
    ('ONCE', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'TERMLY', 'YEARLY', 'CUSTOM'));
CREATE INDEX commitment_family_idx ON commitment (family_id, status);

CREATE TABLE commitment_occurrence (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commitment_id  UUID         NOT NULL REFERENCES commitment (id) ON DELETE CASCADE,
    due_on         DATE         NOT NULL,
    amount_minor   BIGINT       NOT NULL CHECK (amount_minor > 0),
    label          VARCHAR(80),                                   -- "Cheque 2 of 4", "Term 2"
    UNIQUE (commitment_id, due_on)
);

-- ---------------------------------------------------------------- goals
ALTER TABLE goal
    ADD COLUMN tier          VARCHAR(10) NOT NULL DEFAULT 'GOOD_LIFE' CHECK (tier IN ('SURVIVAL', 'GOOD_LIFE', 'DREAM')),
    -- What the plan puts in each month (set when a parent picks a plan option). NULL = not funded yet.
    ADD COLUMN monthly_minor BIGINT CHECK (monthly_minor >= 0),
    ADD COLUMN priority      SMALLINT NOT NULL DEFAULT 100;      -- lower is funded first (the buffer before the trip)

-- ---------------------------------------------------------------- money buckets
-- A split of the month's income into labelled buckets, on paper. The Planner proposes it (DRAFT), a parent
-- confirms it (ACTIVE), the engine turns the shares into AED. Shares are basis points: 4986 = 49.86%.
CREATE TABLE bucket_plan (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     UUID        NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    status        VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    proposed_by   VARCHAR(20) NOT NULL CHECK (proposed_by IN ('PLANNER', 'ENGINE', 'PARENT')),
    created_by    UUID        REFERENCES member (id),
    confirmed_by  UUID        REFERENCES member (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at  TIMESTAMPTZ
);
-- At most one live plan and one waiting draft per family
CREATE UNIQUE INDEX bucket_plan_one_active ON bucket_plan (family_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX bucket_plan_one_draft  ON bucket_plan (family_id) WHERE status = 'DRAFT';

CREATE TABLE bucket_plan_line (
    plan_id     UUID         NOT NULL REFERENCES bucket_plan (id) ON DELETE CASCADE,
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN ('COMMITMENTS', 'EVERYDAY', 'GOALS', 'INVEST_ASIDE', 'FUN', 'GIVING')),
    share_bp    INT          NOT NULL CHECK (share_bp BETWEEN 0 AND 10000),
    reason      VARCHAR(240),                                      -- the Planner's one line, shown to the parent
    PRIMARY KEY (plan_id, kind)
);

-- Which bucket each budget category belongs to, so spend can be shown per bucket.
ALTER TABLE budget ADD COLUMN bucket VARCHAR(16) NOT NULL DEFAULT 'EVERYDAY'
    CHECK (bucket IN ('EVERYDAY', 'FUN'));

-- ---------------------------------------------------------------- assessments on requests
ALTER TABLE spending_request
    ADD COLUMN assessment           JSONB,
    ADD COLUMN assessment_version   SMALLINT,
    ADD COLUMN paid_from_account_id UUID REFERENCES money_account (id);

-- ---------------------------------------------------------------- agent audit (written by the core, not the agent)
ALTER TABLE agent_run
    ADD COLUMN llm_used       BOOLEAN,              -- false = the rule-based fallback answered
    ADD COLUMN fallback_reason VARCHAR(120),        -- why: LLM off, timeout, bad draft, number check failed
    ADD COLUMN latency_ms     INT,
    ADD COLUMN input_tokens   INT,
    ADD COLUMN output_tokens  INT;
ALTER TABLE tool_execution ADD COLUMN http_status SMALLINT;
CREATE INDEX tool_execution_run_idx ON tool_execution (agent_run_id, executed_at);
