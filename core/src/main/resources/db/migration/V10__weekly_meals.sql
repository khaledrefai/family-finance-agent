-- Weekly dinners and the shopping list (moved back into Phase 1 on 2026-10-26).
--
--   * member_food_profile: what each person eats. Halal, vegetarian, vegan, and foods they don't like (typed words,
--     comma separated, "mushrooms, olives"). Allergies are not here: they stay family rules (BLOCK_ALLERGEN), so one
--     allergy rule covers both ask-to-buy and the dinner plan.
--   * meal_plan: the week's dinners a parent saved, one plan per family per week. plan keeps the whole plan as the
--     engine worked it out when saved (nights, shopping list, totals), so a later change to the saved price list
--     doesn't change a plan the family already has.
--
-- V1 made a placeholder meal_plan table that nothing ever wrote to; it is replaced by the one below. Everything
-- else is new; no existing data changes.

CREATE TABLE member_food_profile (
    member_id   UUID         PRIMARY KEY REFERENCES member (id) ON DELETE CASCADE,
    family_id   UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    halal       BOOLEAN      NOT NULL DEFAULT false,
    vegetarian  BOOLEAN      NOT NULL DEFAULT false,
    vegan       BOOLEAN      NOT NULL DEFAULT false,
    dislikes    TEXT         NOT NULL DEFAULT '' CHECK (length(dislikes) <= 300),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT vegan_is_vegetarian CHECK (NOT vegan OR vegetarian)
);

CREATE INDEX member_food_profile_family_idx ON member_food_profile (family_id);

DROP TABLE meal_plan;

CREATE TABLE meal_plan (
    family_id       UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    week_start      DATE         NOT NULL,
    plan            JSONB        NOT NULL,
    shopping_minor  BIGINT       NOT NULL CHECK (shopping_minor >= 0),
    delivery_minor  BIGINT       NOT NULL CHECK (delivery_minor >= 0),
    saved_by        UUID         REFERENCES member (id) ON DELETE SET NULL,
    saved_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, week_start)
);
