-- The weekly money check (an agent acting on its own): what it proposes for a parent to approve or dismiss.
-- NOTE: something to know. BUDGET: a budget to slow down (changed by the parent in Budgets). NUDGE: a short message
-- to one child, sent only when a parent approves it. Only an agent run can make one; only a parent's tap decides it.
CREATE TABLE agent_proposal (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id      UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    agent_run_id   UUID         REFERENCES agent_run (id) ON DELETE SET NULL,
    kind           VARCHAR(10)  NOT NULL CHECK (kind IN ('NOTE', 'BUDGET', 'NUDGE')),
    title          VARCHAR(120) NOT NULL,
    body           VARCHAR(400) NOT NULL,
    why            JSONB        NOT NULL DEFAULT '[]',   -- the core's facts it is based on, word for word
    link           VARCHAR(120),
    child_id       UUID         REFERENCES member (id) ON DELETE CASCADE,
    child_message  VARCHAR(300),
    status         VARCHAR(10)  NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'APPROVED', 'DISMISSED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by     UUID         REFERENCES member (id) ON DELETE SET NULL,
    decided_at     TIMESTAMPTZ,
    CONSTRAINT nudge_has_child CHECK (kind <> 'NUDGE' OR (child_id IS NOT NULL AND child_message IS NOT NULL))
);
CREATE INDEX agent_proposal_family_idx ON agent_proposal (family_id, created_at DESC);
-- The same suggestion is not proposed twice while the first is still waiting
CREATE UNIQUE INDEX agent_proposal_waiting_once ON agent_proposal (family_id, title) WHERE status = 'PROPOSED';
