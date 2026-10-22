-- Demo family seed. Safe to run any number of times: it deletes the demo family and recreates it.
-- Runs automatically (Flyway repeatable migration) when the "demo" profile is active, and on demand
-- with `./deploy.sh reseed` before every recording.
--
-- All logins use the password: demo1234   (DEMO ONLY)
-- Dates are relative to today so the story always looks current.
--
--   Sara  (parent)  sara / demo1234
--   Omar  (parent)  omar / demo1234
--   Layla (13)      layla / demo1234   -> wants AED 300 headphones from noon
--   Adam  (9)       adam / demo1234    -> peanut allergy, tries to buy peanut snacks

-- Delete children before parents. Relying on ON DELETE CASCADE alone fails once a request has been approved
-- (approval -> member has no cascade, and Postgres can check it before the cascade reaches the approval).
DELETE FROM approval a USING spending_request r
 WHERE a.request_id = r.id AND r.family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM demo_wallet_entry e USING demo_wallet w
 WHERE e.wallet_id = w.id AND w.family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM agent_run         WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM ledger_transaction WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM spending_request  WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM family WHERE id = '00000000-0000-0000-0000-00000000f001';

INSERT INTO family (id, name, currency, is_demo)
VALUES ('00000000-0000-0000-0000-00000000f001', 'The Haddad Family (demo)', 'AED', TRUE);

INSERT INTO member (id, family_id, display_name, role, age_band, username, password_hash, email) VALUES
 ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000f001', 'Sara',  'PARENT', 'ADULT',      'sara',  crypt('demo1234', gen_salt('bf', 10)), 'sara@demo.local'),
 ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-00000000f001', 'Omar',  'PARENT', 'ADULT',      'omar',  crypt('demo1234', gen_salt('bf', 10)), 'omar@demo.local'),
 ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000f001', 'Layla', 'CHILD',  'TEEN_13_17', 'layla', crypt('demo1234', gen_salt('bf', 10)), NULL),
 ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000f001', 'Adam',  'CHILD',  'CHILD_6_9',  'adam',  crypt('demo1234', gen_salt('bf', 10)), NULL);

-- Parental consent for each child account (PDPL)
INSERT INTO consent (family_id, subject_member_id, granted_by_id, purpose)
SELECT '00000000-0000-0000-0000-00000000f001', c.id, '00000000-0000-0000-0000-0000000000a1', p.purpose
FROM (VALUES ('00000000-0000-0000-0000-0000000000c1'::uuid), ('00000000-0000-0000-0000-0000000000c2'::uuid)) AS c(id)
CROSS JOIN (VALUES ('CHILD_ACCOUNT'), ('AI_PROCESSING')) AS p(purpose);

-- Income (amounts in fils: 2500000 = AED 25,000)
INSERT INTO income (family_id, member_id, name, amount_minor, frequency, next_on) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', 'Sara salary', 1800000, 'MONTHLY', date_trunc('month', current_date)::date + 24),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', 'Omar salary', 1500000, 'MONTHLY', date_trunc('month', current_date)::date + 24);

-- Commitments: rent, school fees soon, car instalment, and a forgotten subscription
INSERT INTO commitment (family_id, name, kind, amount_minor, frequency, next_due_on, remaining_count) VALUES
 ('00000000-0000-0000-0000-00000000f001', 'Apartment rent (cheque)',    'RENT',         2000000, 'QUARTERLY', current_date + 40, NULL),
 ('00000000-0000-0000-0000-00000000f001', 'School fees, term 2',        'SCHOOL_FEES',  1450000, 'TERMLY',    current_date + 18, NULL),
 ('00000000-0000-0000-0000-00000000f001', 'Car instalment',             'LOAN',          185000, 'MONTHLY',   current_date + 6,  22),
 ('00000000-0000-0000-0000-00000000f001', 'DEWA electricity and water', 'UTILITY',        95000, 'MONTHLY',   current_date + 12, NULL),
 ('00000000-0000-0000-0000-00000000f001', 'Streaming bundle',           'SUBSCRIPTION',    5900, 'MONTHLY',   current_date + 3,  NULL);

-- Budgets: family food budget + weekly pocket money per child
INSERT INTO budget (family_id, member_id, category, period, limit_minor) VALUES
 ('00000000-0000-0000-0000-00000000f001', NULL, 'GROCERIES', 'WEEKLY', 70000),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'DINING_OUT', 'MONTHLY', 80000),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1', 'POCKET_MONEY', 'WEEKLY', 5000),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'POCKET_MONEY', 'WEEKLY', 3000);

-- Family rules
INSERT INTO family_rule (family_id, applies_to_member_id, kind, category, allergen, limit_minor, note, created_by) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'BLOCK_ALLERGEN', NULL, 'PEANUT', NULL, 'Adam has a peanut allergy. Always check the pack label.', '00000000-0000-0000-0000-0000000000a1'),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'BLOCK_CATEGORY', 'IN_GAME_PURCHASES', NULL, NULL, 'No in-game purchases', '00000000-0000-0000-0000-0000000000a2'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'BLOCK_CATEGORY', 'SUGARY_DRINKS', NULL, NULL, 'No fizzy drinks for Adam', '00000000-0000-0000-0000-0000000000a1'),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'APPROVAL_LIMIT', NULL, NULL, 5000, 'Anything over AED 50 needs a parent', '00000000-0000-0000-0000-0000000000a1');

-- Demo wallets (DEMO MONEY) with an opening balance
INSERT INTO demo_wallet (id, family_id, member_id) VALUES
 ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1'),
 ('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2');

-- Weekly pocket money every Sunday for the last 11 weeks: Layla AED 50, Adam AED 30 (matches the budgets above).
-- "Today" is UAE time so it lines up with the core's clock.
INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description, created_at)
SELECT w.wallet_id, 'POCKET_MONEY', w.amount, 'Weekly pocket money (demo money)',
       (t.d - (extract(isodow FROM t.d)::int % 7) - 7 * k) + time '09:00'
FROM (SELECT (now() AT TIME ZONE 'Asia/Dubai')::date AS d) t
CROSS JOIN generate_series(0, 10) AS k
CROSS JOIN (VALUES ('00000000-0000-0000-0000-0000000000d1'::uuid, 5000),
                   ('00000000-0000-0000-0000-0000000000d2'::uuid, 3000)) AS w(wallet_id, amount);

INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description) VALUES
 ('00000000-0000-0000-0000-0000000000d1', 'PAYMENT', -1200, 'Bookshop (demo money)');

-- Goals
INSERT INTO goal (family_id, member_id, name, target_minor, saved_minor, target_date) VALUES
 ('00000000-0000-0000-0000-00000000f001', NULL, 'Family emergency fund', 3000000, 850000, current_date + 365),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1', 'Headphones', 30000, 6000, NULL);

INSERT INTO points_entry (family_id, member_id, points, reason) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1', 40, 'Finished lesson: needs vs wants'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 25, 'Saved pocket money 2 weeks in a row');

-- A few ledger lines so dashboards are not empty (documents and duplicates arrive in week 3)
INSERT INTO ledger_transaction (family_id, member_id, occurred_on, description, merchant, category, direction, amount_minor, duplicate_hash) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', current_date - 5, 'Weekly groceries', 'Carrefour', 'GROCERIES', 'DEBIT', 61250, encode(digest('seed-1', 'sha256'), 'hex')),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', current_date - 3, 'Fuel', 'ENOC', 'TRANSPORT', 'DEBIT', 18000, encode(digest('seed-2', 'sha256'), 'hex')),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', current_date - 2, 'Dinner out', 'Local restaurant', 'DINING_OUT', 'DEBIT', 23500, encode(digest('seed-3', 'sha256'), 'hex'));

-- ---------------------------------------------------------------- kids' logged spending (chat logging)
-- About 10 weeks of what Layla and Adam told the agent they bought, so "where did Layla's money go this month?"
-- has something to answer from on any day you reseed. Amounts in fils. Some school days are missing on purpose:
-- real kids forget to log, and the parent's answer should say so.
--
-- Layla (13): canteen Mon/Tue/Thu, crisps on Friday, bubble tea every other Saturday, plus one-offs
-- Adam (9):   canteen Tue/Thu, ice cream on Saturday, lots of Pokemon cards, one Robux card and one cola
--             (both against family rules)
WITH today AS (SELECT (now() AT TIME ZONE 'Asia/Dubai')::date AS d),
days AS (
    SELECT g AS ago, t.d - g AS d, extract(isodow FROM t.d - g)::int AS dow, (extract(week FROM t.d - g)::int % 2) AS odd_week
    FROM today t, generate_series(0, 74) AS g
),
habits AS (
    -- Layla
    SELECT '00000000-0000-0000-0000-0000000000c1'::uuid AS member, ago, d,
           (ARRAY['Chicken wrap', 'Pasta', 'Sandwich and juice'])[ago % 3 + 1] AS descr,
           'School canteen' AS merchant, 'SCHOOL_CANTEEN' AS cat, (ARRAY[800, 700, 600])[ago % 3 + 1] AS amt
    FROM days WHERE dow IN (1, 2, 4) AND ago % 5 <> 3
    UNION ALL
    SELECT '00000000-0000-0000-0000-0000000000c1', ago, d, 'Crisps and water', 'School canteen', 'SNACKS', 400
    FROM days WHERE dow = 5 AND ago % 5 <> 3
    UNION ALL
    SELECT '00000000-0000-0000-0000-0000000000c1', ago, d, 'Bubble tea', 'Mall', 'EATING_OUT', 1600
    FROM days WHERE dow = 6 AND odd_week = 1
    -- Adam
    UNION ALL
    SELECT '00000000-0000-0000-0000-0000000000c2', ago, d, 'Juice and croissant', 'School canteen', 'SCHOOL_CANTEEN', 500
    FROM days WHERE dow IN (2, 4) AND ago % 4 <> 1
    UNION ALL
    SELECT '00000000-0000-0000-0000-0000000000c2', ago, d, 'Ice cream', 'Ice cream shop', 'SNACKS', 700
    FROM days WHERE dow = 6
),
one_offs AS (
    SELECT v.member, v.ago, t.d - v.ago AS d, v.descr, v.merchant, v.cat, v.amt
    FROM today t, (VALUES
     -- Layla
     ('00000000-0000-0000-0000-0000000000c1'::uuid,  2, 'Shawarma with friends',   'Shawarma place', 'EATING_OUT',              1200),
     ('00000000-0000-0000-0000-0000000000c1'::uuid,  5, 'Phone case',              'Mall kiosk',     'CLOTHES_AND_ACCESSORIES', 3500),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 12, 'Birthday gift for Mariam', NULL,            'GIFTS_AND_CHARITY',       3000),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 19, 'Notebook and gel pens',   'Bookshop',       'BOOKS_AND_STATIONERY',    1500),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 26, 'Popcorn at the cinema',   'Cinema',         'SNACKS',                  1800),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 33, 'Metro card top-up',       'Metro',          'TRANSPORT',               2000),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 40, 'Lip balm and hair clips', 'Pharmacy',       'CLOTHES_AND_ACCESSORIES', 2500),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 47, 'Shawarma with friends',   'Shawarma place', 'EATING_OUT',              1200),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 54, 'Gift for Teachers'' Day', NULL,             'GIFTS_AND_CHARITY',       2000),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 61, 'Sketchbook',              'Bookshop',       'BOOKS_AND_STATIONERY',    2200),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 68, 'Popcorn at the cinema',   'Cinema',         'SNACKS',                  1800),
     -- Adam
     ('00000000-0000-0000-0000-0000000000c2'::uuid,  3, 'Cola',                    'Supermarket',    'SUGARY_DRINKS',            300),
     ('00000000-0000-0000-0000-0000000000c2'::uuid,  6, 'Robux gift card',         'Supermarket',    'IN_GAME_PURCHASES',       2000),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 13, 'Pokemon cards',           'Toy shop',       'TOYS_AND_HOBBIES',        1500),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 20, 'Slime kit',               'Toy shop',       'TOYS_AND_HOBBIES',        1200),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 27, 'Pokemon cards',           'Toy shop',       'TOYS_AND_HOBBIES',        1500),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 34, 'Football stickers',       'Supermarket',    'TOYS_AND_HOBBIES',         800),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 41, 'Pokemon cards',           'Toy shop',       'TOYS_AND_HOBBIES',        1500),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 48, 'Charity box at school',   NULL,             'GIFTS_AND_CHARITY',        500),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 55, 'Slime kit',               'Toy shop',       'TOYS_AND_HOBBIES',        1200),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 62, 'Pokemon cards',           'Toy shop',       'TOYS_AND_HOBBIES',        1500)
    ) AS v(member, ago, descr, merchant, cat, amt)
)
INSERT INTO ledger_transaction (family_id, member_id, occurred_on, description, merchant, category, direction,
                                amount_minor, duplicate_hash, source, logged_text)
SELECT '00000000-0000-0000-0000-00000000f001', x.member, x.d, x.descr, x.merchant, x.cat, 'DEBIT', x.amt,
       encode(digest('seed-kid-' || x.member || '-' || x.ago || '-' || x.descr, 'sha256'), 'hex'), 'SEED',
       (x.amt / 100) || ' dirhams ' || lower(x.descr)
FROM (SELECT * FROM habits UNION ALL SELECT * FROM one_offs) AS x;
