-- Family Finance Agent: core schema (week 1)
--
-- Conventions
--   * Money is stored as BIGINT minor units (fils; 1 AED = 100 fils) in columns ending in _minor,
--     with an ISO currency code next to it. No floats anywhere near money.
--   * Every family-owned row carries family_id so queries can always be scoped to one family.
--   * Children's data is kept minimal (PDPL): display name, age band, username. No birth date,
--     no email, no phone.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------- families & members
CREATE TABLE family (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(120) NOT NULL,
    currency      CHAR(3)      NOT NULL DEFAULT 'AED',
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id      UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    display_name   VARCHAR(80)  NOT NULL,
    role           VARCHAR(16)  NOT NULL CHECK (role IN ('PARENT', 'CHILD')),
    age_band       VARCHAR(16)  NOT NULL CHECK (age_band IN ('CHILD_6_9', 'PRETEEN_10_12', 'TEEN_13_17', 'ADULT')),
    username       VARCHAR(60)  NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,           -- bcrypt
    email          VARCHAR(160),                    -- parents only (notifications)
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT member_child_has_no_email CHECK (role = 'PARENT' OR email IS NULL),
    CONSTRAINT member_parent_is_adult    CHECK (role = 'CHILD' OR age_band = 'ADULT')
);
CREATE INDEX member_family_idx ON member (family_id);

-- Parental consent for each child account (PDPL). A child account is only usable with a granted consent.
CREATE TABLE consent (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id         UUID        NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    subject_member_id UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    granted_by_id     UUID        NOT NULL REFERENCES member (id),
    purpose           VARCHAR(40) NOT NULL,          -- e.g. CHILD_ACCOUNT, DOCUMENT_PROCESSING, AI_PROCESSING
    granted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    UNIQUE (subject_member_id, purpose)
);

-- ---------------------------------------------------------------- documents & ledger
CREATE TABLE source_document (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    uploaded_by   UUID         NOT NULL REFERENCES member (id),
    kind          VARCHAR(20)  NOT NULL CHECK (kind IN ('RECEIPT', 'INVOICE', 'BANK_STATEMENT', 'OTHER')),
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    storage_path  VARCHAR(500) NOT NULL,
    sha256        CHAR(64)     NOT NULL,             -- same file uploaded twice is caught here
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED', 'EXTRACTED', 'FAILED')),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (family_id, sha256)
);

CREATE TABLE ledger_transaction (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id           UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id           UUID         REFERENCES member (id),          -- who spent / received, if known
    source_document_id  UUID         REFERENCES source_document (id) ON DELETE SET NULL,
    occurred_on         DATE         NOT NULL,
    description         VARCHAR(255) NOT NULL,
    merchant            VARCHAR(160),
    category            VARCHAR(40)  NOT NULL DEFAULT 'UNCATEGORISED',
    direction           VARCHAR(8)   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3)      NOT NULL DEFAULT 'AED',
    -- hash of (date, amount, normalised merchant/description); computed by the money engine, not by agents
    duplicate_hash      CHAR(64)     NOT NULL,
    duplicate_of_id     UUID         REFERENCES ledger_transaction (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ledger_tx_family_date_idx ON ledger_transaction (family_id, occurred_on);
CREATE INDEX ledger_tx_dup_idx ON ledger_transaction (family_id, duplicate_hash);

-- ---------------------------------------------------------------- planning
CREATE TABLE commitment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id       UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('RENT', 'SCHOOL_FEES', 'LOAN', 'UTILITY', 'SUBSCRIPTION', 'INSURANCE', 'OTHER')),
    amount_minor    BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3)      NOT NULL DEFAULT 'AED',
    frequency       VARCHAR(12)  NOT NULL CHECK (frequency IN ('ONCE', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'TERMLY', 'YEARLY')),
    next_due_on     DATE         NOT NULL,
    remaining_count INT,                              -- e.g. instalments left; NULL = open-ended
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE income (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id     UUID         REFERENCES member (id),
    name          VARCHAR(120) NOT NULL,
    amount_minor  BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency      CHAR(3)      NOT NULL DEFAULT 'AED',
    frequency     VARCHAR(12)  NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    next_on       DATE         NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

-- A budget is either family-wide for a category (member_id NULL) or personal (pocket money, member_id set).
CREATE TABLE budget (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id     UUID         REFERENCES member (id) ON DELETE CASCADE,
    category      VARCHAR(40)  NOT NULL,
    period        VARCHAR(10)  NOT NULL CHECK (period IN ('WEEKLY', 'MONTHLY')),
    limit_minor   BIGINT       NOT NULL CHECK (limit_minor >= 0),
    currency      CHAR(3)      NOT NULL DEFAULT 'AED',
    UNIQUE NULLS NOT DISTINCT (family_id, member_id, category, period)
);

-- Family rules: block a category, block an allergen, or require approval above a limit.
-- applies_to_member_id NULL = applies to every child.
CREATE TABLE family_rule (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id             UUID        NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    applies_to_member_id  UUID        REFERENCES member (id) ON DELETE CASCADE,
    kind                  VARCHAR(20) NOT NULL CHECK (kind IN ('BLOCK_CATEGORY', 'BLOCK_ALLERGEN', 'APPROVAL_LIMIT')),
    category              VARCHAR(40),
    allergen              VARCHAR(40),
    limit_minor           BIGINT CHECK (limit_minor >= 0),
    note                  VARCHAR(255),
    created_by            UUID        NOT NULL REFERENCES member (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_shape CHECK (
        (kind = 'BLOCK_CATEGORY' AND category IS NOT NULL)
     OR (kind = 'BLOCK_ALLERGEN' AND allergen IS NOT NULL)
     OR (kind = 'APPROVAL_LIMIT' AND limit_minor IS NOT NULL))
);

-- ---------------------------------------------------------------- requests & approvals
CREATE TABLE spending_request (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id       UUID          NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    requester_id    UUID          NOT NULL REFERENCES member (id),
    kind            VARCHAR(20)   NOT NULL CHECK (kind IN ('SPEND', 'ASK_TO_BUY', 'CHALLENGE', 'REWARD')),
    title           VARCHAR(200)  NOT NULL,
    amount_minor    BIGINT        NOT NULL CHECK (amount_minor >= 0),
    currency        CHAR(3)       NOT NULL DEFAULT 'AED',
    category        VARCHAR(40),
    product_url     VARCHAR(1000),
    status          VARCHAR(12)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'BLOCKED')),
    agent_summary   TEXT,                              -- the agent's explanation shown to the parent
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ
);
CREATE INDEX spending_request_family_status_idx ON spending_request (family_id, status);

CREATE TABLE approval (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id    UUID         NOT NULL UNIQUE REFERENCES spending_request (id) ON DELETE CASCADE,
    approver_id   UUID         NOT NULL REFERENCES member (id),
    decision      VARCHAR(10)  NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    note          VARCHAR(500),
    decided_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Last line of defence: even if application code has a bug, the database refuses an approval
-- by the requester themselves, by a non-parent, or by someone from another family.
CREATE FUNCTION approval_guard() RETURNS trigger AS $$
DECLARE
    req       spending_request%ROWTYPE;
    approver  member%ROWTYPE;
BEGIN
    SELECT * INTO req FROM spending_request WHERE id = NEW.request_id;
    SELECT * INTO approver FROM member WHERE id = NEW.approver_id;

    IF NEW.approver_id = req.requester_id THEN
        RAISE EXCEPTION 'approval_guard: a member cannot decide their own request' USING ERRCODE = 'check_violation';
    END IF;
    IF approver.role <> 'PARENT' THEN
        RAISE EXCEPTION 'approval_guard: only a parent can decide a request' USING ERRCODE = 'check_violation';
    END IF;
    IF approver.family_id <> req.family_id THEN
        RAISE EXCEPTION 'approval_guard: approver is not in the request''s family' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER approval_guard_trg
    BEFORE INSERT OR UPDATE ON approval
    FOR EACH ROW EXECUTE FUNCTION approval_guard();

-- ---------------------------------------------------------------- demo wallet (DEMO MONEY ONLY)
CREATE TABLE demo_wallet (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id      UUID        NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id      UUID        NOT NULL UNIQUE REFERENCES member (id) ON DELETE CASCADE,
    currency       CHAR(3)     NOT NULL DEFAULT 'AED',
    is_demo_money  BOOLEAN     NOT NULL DEFAULT TRUE CHECK (is_demo_money),   -- can never hold real money
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Balance = sum of entries (computed by the money engine). Entries are append-only.
CREATE TABLE demo_wallet_entry (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id     UUID         NOT NULL REFERENCES demo_wallet (id) ON DELETE CASCADE,
    kind          VARCHAR(16)  NOT NULL CHECK (kind IN ('POCKET_MONEY', 'PAYMENT', 'REWARD', 'ADJUSTMENT', 'SAVINGS_MOVE')),
    amount_minor  BIGINT       NOT NULL CHECK (amount_minor <> 0),            -- signed: + in, - out
    request_id    UUID         REFERENCES spending_request (id),
    description   VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX demo_wallet_entry_wallet_idx ON demo_wallet_entry (wallet_id);

-- ---------------------------------------------------------------- goals, challenges, points
CREATE TABLE goal (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id      UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id      UUID         REFERENCES member (id) ON DELETE CASCADE,     -- NULL = family goal
    name           VARCHAR(120) NOT NULL,
    target_minor   BIGINT       NOT NULL CHECK (target_minor > 0),
    saved_minor    BIGINT       NOT NULL DEFAULT 0 CHECK (saved_minor >= 0),
    currency       CHAR(3)      NOT NULL DEFAULT 'AED',
    target_date    DATE,
    status         VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REACHED', 'CANCELLED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE challenge (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id           UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id           UUID         NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    goal_id             UUID         REFERENCES goal (id) ON DELETE SET NULL,
    request_id          UUID         REFERENCES spending_request (id),          -- the approval that started it
    title               VARCHAR(160) NOT NULL,
    weekly_target_minor BIGINT       CHECK (weekly_target_minor >= 0),
    weeks               INT          CHECK (weeks > 0),
    reward_points       INT          NOT NULL DEFAULT 0 CHECK (reward_points >= 0),
    status              VARCHAR(12)  NOT NULL DEFAULT 'PROPOSED'
                        CHECK (status IN ('PROPOSED', 'ACTIVE', 'COMPLETED', 'FAILED', 'CANCELLED')),
    starts_on           DATE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Points are an append-only ledger; a member's total is the sum.
CREATE TABLE points_entry (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id     UUID         NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    challenge_id  UUID         REFERENCES challenge (id) ON DELETE SET NULL,
    points        INT          NOT NULL CHECK (points <> 0),
    reason        VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- meals
CREATE TABLE meal_plan (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id       UUID        NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    week_start      DATE        NOT NULL,
    budget_minor    BIGINT      NOT NULL CHECK (budget_minor >= 0),
    estimated_minor BIGINT      CHECK (estimated_minor >= 0),          -- totalled by the money engine
    currency        CHAR(3)     NOT NULL DEFAULT 'AED',
    plan            JSONB       NOT NULL DEFAULT '{}'::jsonb,           -- days -> meals
    shopping_list   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (family_id, week_start)
);

-- ---------------------------------------------------------------- agent audit
CREATE TABLE agent_run (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id        UUID        NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    triggered_by_id  UUID        REFERENCES member (id),             -- NULL = scheduled / system event
    agent            VARCHAR(20) NOT NULL CHECK (agent IN ('ORCHESTRATOR', 'BOOKKEEPER', 'GATEKEEPER', 'SHOPPER', 'COACH', 'PLANNER')),
    trigger_kind     VARCHAR(20) NOT NULL CHECK (trigger_kind IN ('MESSAGE', 'SCHEDULE', 'EVENT')),
    input_summary    TEXT,
    output_summary   TEXT,
    model            VARCHAR(80),
    status           VARCHAR(12) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
CREATE INDEX agent_run_family_idx ON agent_run (family_id, started_at DESC);

CREATE TABLE tool_execution (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_run_id      UUID        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    tool_name         VARCHAR(80) NOT NULL,
    request_payload   JSONB       NOT NULL,
    response_payload  JSONB,
    changes_state     BOOLEAN     NOT NULL DEFAULT FALSE,     -- true = needed an explicit permission
    authorised_by_id  UUID        REFERENCES member (id),     -- the member whose permission covered it
    status            VARCHAR(12) NOT NULL CHECK (status IN ('OK', 'DENIED', 'ERROR')),
    executed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT state_change_needs_authoriser CHECK (NOT changes_state OR status <> 'OK' OR authorised_by_id IS NOT NULL)
);
