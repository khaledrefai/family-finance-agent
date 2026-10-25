-- Week 3: documents, duplicates and subscriptions, saving in gold, the family wealth view.
--
--   * receipts and statements a parent uploads; the Bookkeeper reads them into a typed draft (EXTRACTED)
--     and nothing reaches the ledger until a parent confirms it
--   * each statement line is matched against what is already in the ledger, so a receipt, a typed payment
--     and the card statement never count the same purchase twice
--   * a parent's answer to each finding (double charge, subscription) so it is not shown again
--   * gold the family already holds, and the price per gram it is valued at
--
-- Everything is additive or a change of a CHECK; no data is lost.

-- ---------------------------------------------------------------- uploaded documents
ALTER TABLE source_document DROP CONSTRAINT source_document_kind_check;
ALTER TABLE source_document ADD CONSTRAINT source_document_kind_check
    CHECK (kind IN ('RECEIPT', 'INVOICE', 'BANK_STATEMENT', 'CARD_STATEMENT', 'OTHER'));
-- UPLOADED: stored, not read yet. EXTRACTED: the Bookkeeper's draft, waiting for a parent.
-- CONFIRMED: a parent confirmed it and its lines are in the ledger. FAILED: it could not be read.
-- Discarding a draft deletes it, so the same file can be uploaded again.
ALTER TABLE source_document DROP CONSTRAINT source_document_status_check;
ALTER TABLE source_document ADD CONSTRAINT source_document_status_check
    CHECK (status IN ('UPLOADED', 'EXTRACTED', 'CONFIRMED', 'FAILED'));

ALTER TABLE source_document
    -- Prototype: the file itself lives in the database (max 5 MB), so there is no file store to back up.
    ADD COLUMN content          BYTEA,
    ADD COLUMN size_bytes       INT          CHECK (size_bytes >= 0),
    -- Which account or card it belongs to: picked by the parent, or matched by the card's last 4 digits.
    ADD COLUMN account_id       UUID         REFERENCES money_account (id),
    ADD COLUMN match_reason     VARCHAR(160),
    -- The draft as the agent sent it (for the audit), and how it was read: LLM (Claude) or RULES (the fallback).
    ADD COLUMN extracted        JSONB,
    ADD COLUMN read_by          VARCHAR(8)   CHECK (read_by IN ('LLM', 'RULES', 'PARENT')),
    ADD COLUMN agent_run_id     UUID         REFERENCES agent_run (id) ON DELETE SET NULL,
    -- Receipt: shop, date, total and the one budget it goes to. Statement: the closing balance and its date.
    ADD COLUMN merchant         VARCHAR(160),
    ADD COLUMN doc_date         DATE,
    ADD COLUMN total_minor      BIGINT       CHECK (total_minor > 0),
    ADD COLUMN category         VARCHAR(40),
    ADD COLUMN closing_minor    BIGINT       CHECK (closing_minor >= 0),
    -- A receipt for a purchase already in the ledger (typed in, or from a statement) is attached to it, not added.
    ADD COLUMN matched_tx_id    UUID         REFERENCES ledger_transaction (id) ON DELETE SET NULL,
    ADD COLUMN note             VARCHAR(300),
    ADD COLUMN confirmed_by     UUID         REFERENCES member (id),
    ADD COLUMN confirmed_at     TIMESTAMPTZ;
CREATE INDEX source_document_family_idx ON source_document (family_id, uploaded_at DESC);

-- One line of a document. A statement line is one payment (it becomes a ledger line on Confirm, unless it is
-- ALREADY_LOGGED). A receipt line is one item (ITEM): shown to the parent, the receipt's total is the payment.
CREATE TABLE document_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id        UUID         NOT NULL REFERENCES source_document (id) ON DELETE CASCADE,
    line_no            INT          NOT NULL,
    occurred_on        DATE,
    description        VARCHAR(255) NOT NULL,
    merchant_key       VARCHAR(60),                        -- "SPINNEYS", for matching and subscriptions
    direction          VARCHAR(8)   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    category           VARCHAR(40),
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('NEW', 'ALREADY_LOGGED', 'ITEM')),
    matched_tx_id      UUID         REFERENCES ledger_transaction (id) ON DELETE SET NULL,
    match_note         VARCHAR(200),
    -- Same day, same amount, same shop as an earlier line of this statement: probably charged twice.
    possible_duplicate BOOLEAN      NOT NULL DEFAULT FALSE,
    ledger_tx_id       UUID         REFERENCES ledger_transaction (id) ON DELETE SET NULL,
    UNIQUE (document_id, line_no)
);
CREATE INDEX document_line_matched_idx ON document_line (matched_tx_id);

-- ---------------------------------------------------------------- findings a parent has answered
-- Findings (a double charge, a repeating payment not on the commitments list) are worked out by the engine
-- each time from the ledger. Only the parent's answer is stored, so a finding is never shown twice.
CREATE TABLE finding_decision (
    family_id    UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    finding_key  VARCHAR(120) NOT NULL,
    decision     VARCHAR(12)  NOT NULL CHECK (decision IN ('DUPLICATE', 'KEEP', 'ADDED', 'IGNORED')),
    decided_by   UUID         NOT NULL REFERENCES member (id),
    decided_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, finding_key)
);

-- ---------------------------------------------------------------- saving in gold
-- Gold the family already holds. Saving, not investing: the app records it, it never suggests buying.
-- Weight in milligrams so the engine only ever multiplies whole numbers (10.5 g = 10500).
CREATE TABLE gold_holding (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    description   VARCHAR(120) NOT NULL,
    grams_mg      BIGINT       NOT NULL CHECK (grams_mg > 0),
    purity        VARCHAR(3)   NOT NULL CHECK (purity IN ('24K', '22K', '21K', '18K')),
    paid_minor    BIGINT       NOT NULL CHECK (paid_minor > 0),
    currency      CHAR(3)      NOT NULL DEFAULT 'AED',
    bought_on     DATE         NOT NULL,
    seller        VARCHAR(120),
    -- The money came from the Goals bucket (for one goal) or from Invest aside.
    bucket        VARCHAR(16)  NOT NULL DEFAULT 'GOALS' CHECK (bucket IN ('GOALS', 'INVEST_ASIDE')),
    goal_id       UUID         REFERENCES goal (id) ON DELETE SET NULL,
    is_jewellery  BOOLEAN      NOT NULL DEFAULT FALSE,     -- making charges: worth less than paid if sold by weight
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by    UUID         REFERENCES member (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT goal_only_from_goals CHECK (goal_id IS NULL OR bucket = 'GOALS')
);
CREATE INDEX gold_holding_family_idx ON gold_holding (family_id, status);

-- The price per gram, by purity. family_id NULL = for everyone (the seeded fallback, later a daily feed);
-- a family's own row = a price a parent typed in from the shop. The newest one wins.
CREATE TABLE gold_price (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id        UUID         REFERENCES family (id) ON DELETE CASCADE,
    purity           VARCHAR(3)   NOT NULL CHECK (purity IN ('24K', '22K', '21K', '18K')),
    per_gram_minor   BIGINT       NOT NULL CHECK (per_gram_minor > 0),
    currency         CHAR(3)      NOT NULL DEFAULT 'AED',
    as_of            TIMESTAMPTZ  NOT NULL,
    source           VARCHAR(120) NOT NULL,
    seeded           BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX gold_price_latest_idx ON gold_price (purity, as_of DESC);

-- The fallback, so gold always has a value even with no feed and no typed price. A sample, not a real rate.
INSERT INTO gold_price (family_id, purity, per_gram_minor, as_of, source, seeded) VALUES
 (NULL, '24K', 32000, now(), 'Seeded sample price, not a live rate', TRUE),
 (NULL, '22K', 29650, now(), 'Seeded sample price, not a live rate', TRUE),
 (NULL, '21K', 28300, now(), 'Seeded sample price, not a live rate', TRUE),
 (NULL, '18K', 24250, now(), 'Seeded sample price, not a live rate', TRUE);
