-- Week 6: better purchases (price search in the core, POST /api/pricing/compare).
--
--   * price_search_cache keeps each raw answer from the live search (SerpApi Google Shopping) for 24 hours, so the
--     same search twice costs one paid search, and the demo can be recorded from the cache after a pre-warm.
--     The raw answer is kept (not our parsed offers), so a parser fix applies to cached searches too.
--   * price_comparison is each comparison a family member saw: what they asked for and the ranked offers.
--     A request made from an offer points here, so the price on the request is the core's, not typed in.
--   * spending_request.offer is the offer a request was made from (shop, price, delivery, total, when checked).
--
-- Everything is new; no existing data changes.

CREATE TABLE price_search_cache (
    cache_key   VARCHAR(300)  PRIMARY KEY,
    engine      VARCHAR(40)   NOT NULL,
    query       VARCHAR(300)  NOT NULL,
    body        JSONB         NOT NULL,
    fetched_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE price_comparison (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id   UUID          NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id   UUID          NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    intent      JSONB         NOT NULL,
    result      JSONB         NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX price_comparison_family_idx ON price_comparison (family_id, created_at DESC);

ALTER TABLE spending_request ADD COLUMN offer JSONB;
