-- First-run setup for a real new family (sign up, add the family, income, first budgets and plan).
--
-- setup_done_at: when a parent finished the setup steps. NULL = the family is still setting up, and the app sends a
-- parent to /setup instead of Today. The demo family counts as set up through is_demo, so a reseed (which recreates
-- the family row) never sends the video's family back to setup. Families made before this migration are set up.

ALTER TABLE family ADD COLUMN setup_done_at TIMESTAMPTZ;
UPDATE family SET setup_done_at = created_at;
