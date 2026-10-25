-- Week 5: live updates and notifications (Contract 3: the outbox).
--
--   * family_event is the outbox. Whatever changes something a family member should hear about (a request
--     waiting, a parent's answer, a wallet change, a bill due soon, a budget over) writes one row here in the SAME
--     transaction as the change. A poller in the core reads new rows every 2 seconds and sends them on: a live
--     ping to open screens (SSE), a web push to phones, an email to parents. No message broker.
--   * notification is each member's own list of what they were told (the bell), one row per event and person.
--   * push_subscription is one phone or browser that said yes to notifications.
--   * app_setting holds the web push (VAPID) keys the core makes on its first start, unless they are set in .env.
--
-- Everything is new; no existing data changes.

CREATE TABLE family_event (
    id                 BIGSERIAL    PRIMARY KEY,
    family_id          UUID         NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    kind               VARCHAR(30)  NOT NULL,
    -- Whose thing it is: the child who asked, the wallet's owner. NULL for family-wide events (a bill).
    subject_member_id  UUID         REFERENCES member (id) ON DELETE CASCADE,
    -- Who did it (the parent who said yes). NULL when the core itself noticed it (a bill due soon).
    actor_member_id    UUID         REFERENCES member (id) ON DELETE SET NULL,
    payload            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Alerts the core raises by itself carry a key ("bill:<id>:<date>") so the same alert is only sent once.
    dedupe_key         VARCHAR(160),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    attempts           INT          NOT NULL DEFAULT 0,
    last_error         VARCHAR(300),
    CONSTRAINT family_event_once UNIQUE (family_id, dedupe_key)
);
CREATE INDEX family_event_pending_idx ON family_event (id) WHERE processed_at IS NULL;

CREATE TABLE notification (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id   UUID          NOT NULL REFERENCES family (id) ON DELETE CASCADE,
    member_id   UUID          NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    event_id    BIGINT        NOT NULL REFERENCES family_event (id) ON DELETE CASCADE,
    kind        VARCHAR(30)   NOT NULL,
    title       VARCHAR(120)  NOT NULL,
    body        VARCHAR(400)  NOT NULL,
    link        VARCHAR(200)  NOT NULL,          -- a page in the app, e.g. /parent or /child
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ,
    pushed_at   TIMESTAMPTZ,                     -- at least one phone took the web push
    emailed_at  TIMESTAMPTZ,
    CONSTRAINT notification_once UNIQUE (event_id, member_id)
);
CREATE INDEX notification_member_idx ON notification (member_id, created_at DESC);

CREATE TABLE push_subscription (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id   UUID           NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    endpoint    VARCHAR(1000)  NOT NULL UNIQUE,  -- the browser maker's push address for this phone
    p256dh      VARCHAR(200)   NOT NULL,         -- the phone's public key (base64url), to encrypt for it
    auth        VARCHAR(100)   NOT NULL,         -- the phone's auth secret (base64url)
    user_agent  VARCHAR(200),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    last_ok_at  TIMESTAMPTZ,
    failures    INT            NOT NULL DEFAULT 0
);
CREATE INDEX push_subscription_member_idx ON push_subscription (member_id);

CREATE TABLE app_setting (
    key         VARCHAR(60)  PRIMARY KEY,
    value       TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
