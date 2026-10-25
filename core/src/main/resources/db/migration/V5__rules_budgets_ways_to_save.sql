-- Week 4: family rules, overspend alerts and the "Ways to save" card.
--
--   * family rules get a status (DRAFT until a parent turns them on), the parent's own words, and two new
--     kinds: CATEGORY_LIMIT (at most AED X a month or week on one category) and MONTHLY_CAP (at most AED X a
--     month in all). An agent can only draft a rule; only a parent's tap in the app turns it on.
--   * the parent's answer to each "Ways to save" tip (dismiss for good, or snooze to next month), and the words
--     the agent wrote for this month's tips (the numbers in them are checked against the engine's facts).
--
-- Everything is additive or a change of a CHECK; no data is lost. Existing rules stay on (ACTIVE).

-- ---------------------------------------------------------------- family rules
ALTER TABLE family_rule DROP CONSTRAINT family_rule_kind_check;
ALTER TABLE family_rule ADD CONSTRAINT family_rule_kind_check
    CHECK (kind IN ('BLOCK_CATEGORY', 'BLOCK_ALLERGEN', 'APPROVAL_LIMIT', 'CATEGORY_LIMIT', 'MONTHLY_CAP'));

ALTER TABLE family_rule
    -- DRAFT: written by the agent from the parent's words, not checked yet. ACTIVE: checked on every request.
    -- ARCHIVED: turned off or replaced, kept for history.
    ADD COLUMN status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    -- What the parent wrote, e.g. "no peanuts for Adam". Shown next to the typed rule.
    ADD COLUMN said          VARCHAR(300),
    -- CATEGORY_LIMIT only: per month or per week. MONTHLY_CAP is always a month.
    ADD COLUMN period        VARCHAR(10)  CHECK (period IN ('WEEKLY', 'MONTHLY')),
    ADD COLUMN source        VARCHAR(10)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'AGENT', 'SEED')),
    -- LLM (Claude) or RULES (the fallback parser) when an agent drafted it
    ADD COLUMN drafted_by    VARCHAR(8)   CHECK (drafted_by IN ('LLM', 'RULES', 'PARENT')),
    ADD COLUMN agent_run_id  UUID         REFERENCES agent_run (id) ON DELETE SET NULL,
    ADD COLUMN confirmed_by  UUID         REFERENCES member (id),
    ADD COLUMN confirmed_at  TIMESTAMPTZ,
    ADD COLUMN archived_at   TIMESTAMPTZ;

ALTER TABLE family_rule DROP CONSTRAINT rule_shape;
ALTER TABLE family_rule ADD CONSTRAINT rule_shape CHECK (
    (kind = 'BLOCK_CATEGORY' AND category IS NOT NULL)
 OR (kind = 'BLOCK_ALLERGEN' AND allergen IS NOT NULL)
 OR (kind = 'APPROVAL_LIMIT' AND limit_minor IS NOT NULL)
 OR (kind = 'CATEGORY_LIMIT' AND category IS NOT NULL AND limit_minor IS NOT NULL AND period IS NOT NULL)
 OR (kind = 'MONTHLY_CAP'    AND limit_minor IS NOT NULL));
-- Only a parent turns a rule on: an ACTIVE rule made after this migration says who confirmed it.
ALTER TABLE family_rule ADD CONSTRAINT active_rule_confirmed
    CHECK (status <> 'ACTIVE' OR source <> 'AGENT' OR confirmed_by IS NOT NULL);
CREATE INDEX family_rule_family_idx ON family_rule (family_id, status);

-- ---------------------------------------------------------------- ways to save
-- A parent's answer to a tip. DISMISSED: never show this tip again. SNOOZED: hide it until `until_month`.
CREATE TABLE saving_tip_decision (
    family_id    UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    tip_key      VARCHAR(40)  NOT NULL,
    decision     VARCHAR(10)  NOT NULL CHECK (decision IN ('DISMISSED', 'SNOOZED')),
    until_month  DATE,                                   -- first day of the month it comes back (SNOOZED)
    decided_by   UUID         NOT NULL REFERENCES member (id),
    decided_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, tip_key),
    CONSTRAINT snooze_has_month CHECK (decision <> 'SNOOZED' OR until_month IS NOT NULL)
);

-- The words for one tip in one month. The engine's own sentence is used until the agent writes these.
CREATE TABLE saving_tip_words (
    family_id     UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    month         DATE         NOT NULL,                 -- first day of the month
    tip_key       VARCHAR(40)  NOT NULL,
    text          VARCHAR(400) NOT NULL,
    written_by    VARCHAR(8)   NOT NULL CHECK (written_by IN ('LLM', 'RULES')),
    agent_run_id  UUID         REFERENCES agent_run (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, month, tip_key)
);
