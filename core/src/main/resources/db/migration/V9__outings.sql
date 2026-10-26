-- Week 7: cheaper family outings that fit the entertainment budget.
--
--   * outing_plan is the month's outings a parent saved (one plan per family per month). picks keeps each outing's
--     key, name, area, a line about it and its cost for this family in fils, as the engine worked it out when saved,
--     so a later change to the saved list doesn't change a plan the family already has.
--   * Live "on this week" events reuse price_search_cache (engine google_events), with the same 24 hour cache.
--
-- Everything is new; no existing data changes.

CREATE TABLE outing_plan (
    family_id    UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    month        DATE         NOT NULL CHECK (extract(day FROM month) = 1),
    picks        JSONB        NOT NULL,
    total_minor  BIGINT       NOT NULL CHECK (total_minor >= 0),
    saved_by     UUID         REFERENCES member (id) ON DELETE SET NULL,
    saved_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, month)
);
