-- Spend logging by chat (spike, week 2)
--
-- Kids tell the agent what they bought ("12 dirhams shawarma"). The agent turns that into typed items and the
-- money engine records each one as a DEBIT in ledger_transaction for that child. These columns say where a
-- ledger line came from, and keep the child's own words so a parent can see exactly what was said.

ALTER TABLE ledger_transaction
    ADD COLUMN source      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL'
                           CHECK (source IN ('MANUAL', 'CHAT_LOG', 'DOCUMENT', 'SEED')),
    ADD COLUMN logged_text VARCHAR(500);

CREATE INDEX ledger_tx_member_date_idx ON ledger_transaction (family_id, member_id, occurred_on);
