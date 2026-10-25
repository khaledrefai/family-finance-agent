-- A kid's "Send to a parent" gave no sign it had worked, so some asks were sent several times in a row.
-- From now on the core returns the request already sent (RequestService.create). This cleans up the repeats
-- already saved: the first one stays as it is, and each later copy of the same ask (same member, kind, title and
-- amount, still waiting or blocked, sent within 10 minutes of the one before) becomes CANCELLED. Nothing is
-- deleted, and CANCELLED requests are left out of the lists.
WITH ordered AS (
    SELECT id, created_at,
           lag(created_at) OVER (PARTITION BY requester_id, kind, title, amount_minor ORDER BY created_at) AS previous
    FROM spending_request
    WHERE status IN ('PENDING', 'BLOCKED')
)
UPDATE spending_request r
SET status = 'CANCELLED',
    decided_at = now(),
    agent_summary = 'Sent again by mistake; the first one is kept.'
FROM ordered o
WHERE r.id = o.id
  AND o.previous IS NOT NULL
  AND o.created_at - o.previous <= INTERVAL '10 minutes';
