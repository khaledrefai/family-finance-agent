-- When a kid's own goal was reached: buying the thing they saved for with a parent's yes uses the jar first and
-- marks the goal REACHED. The kid's month still shows it (100%) until the month is over.
ALTER TABLE goal ADD COLUMN reached_at TIMESTAMPTZ;
