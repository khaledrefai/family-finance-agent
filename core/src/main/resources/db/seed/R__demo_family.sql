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
DELETE FROM source_document   WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM agent_run         WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM finding_decision  WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM family_rule       WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM saving_tip_decision WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM saving_tip_words  WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM gold_holding      WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM gold_price        WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM ledger_transaction WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM spending_request  WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM commitment        WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM income            WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM bucket_plan       WHERE family_id = '00000000-0000-0000-0000-00000000f001';
DELETE FROM money_account     WHERE family_id = '00000000-0000-0000-0000-00000000f001';
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

-- ---------------------------------------------------------------- the money plan (week 2)
-- PROVISIONAL numbers, taken from the app mockups' sample family. khaled to confirm or correct them.
-- Amounts in fils: 2800000 = AED 28,000.

-- Income: Omar's fixed salary and Sara's commission (variable). Sara's planning base is worked out by the
-- engine from the last 6 months below: the average of the 3 lowest = AED 6,500.
INSERT INTO income (id, family_id, member_id, name, kind, amount_minor, frequency, next_on, status, source) VALUES
 ('00000000-0000-0000-0000-0000000001a2', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', 'Omar salary',     'FIXED',    2800000, 'MONTHLY', current_date + 20, 'ACTIVE', 'SEED'),
 ('00000000-0000-0000-0000-0000000001a1', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', 'Sara commission', 'VARIABLE',  750000, 'MONTHLY', current_date + 24, 'ACTIVE', 'SEED');

INSERT INTO income_receipt (income_id, received_on, amount_minor)
SELECT '00000000-0000-0000-0000-0000000001a1', (date_trunc('month', current_date) - make_interval(months => k))::date + 24, amt
FROM (VALUES (1, 980000), (2, 620000), (3, 740000), (4, 650000), (5, 1120000), (6, 680000)) AS r(k, amt);

-- Accounts and cards, typed in by hand (no bank links). The Visa's bill falls due before Omar's next pay,
-- so putting Layla's AED 300 headphones on it shows the card warning.
INSERT INTO money_account (id, family_id, owner_member_id, kind, label, last4, balance_minor, balance_as_of,
                           is_savings, limit_minor, statement_day, due_day) VALUES
 ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', 'BANK',        'ENBD current',      '4821', 1165000, current_date,     FALSE, NULL,    NULL, NULL),
 ('00000000-0000-0000-0000-0000000000e2', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', 'BANK',        'ADCB savings',      '1093', 3260000, current_date,     TRUE,  NULL,    NULL, NULL),
 ('00000000-0000-0000-0000-0000000000e3', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', 'CREDIT_CARD', 'Omar''s Visa',      '7710',  595000, current_date - 1, FALSE, 2000000,
  extract(day FROM current_date + 1)::int, extract(day FROM current_date + 19)::int),
 ('00000000-0000-0000-0000-0000000000e4', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', 'CREDIT_CARD', 'Sara''s Mastercard', '3356', 135000, current_date - 1, FALSE, 1200000,
  extract(day FROM current_date + 3)::int, extract(day FROM current_date + 28)::int),
 ('00000000-0000-0000-0000-0000000000e5', '00000000-0000-0000-0000-00000000f001', NULL,                                   'CASH',        'Cash at home',      NULL,     65000, current_date,     FALSE, NULL,    NULL, NULL);

-- Commitments: rent in 4 cheques, school fees in 3 terms, the car loan, DEWA (an estimate, higher in summer),
-- du, and a forgotten subscription. Half of term 2 is already put aside; term 3 comes too soon after it, so March is a tight month.
INSERT INTO commitment (id, family_id, name, kind, amount_minor, frequency, next_due_on, remaining_count, status, source,
                        is_estimate, summer_amount_minor, reserved_minor, balance_left_minor, paid_from_account_id) VALUES
 ('00000000-0000-0000-0000-0000000002c1', '00000000-0000-0000-0000-00000000f001', 'Apartment rent',             'RENT',         3000000, 'CUSTOM',  current_date + 68, NULL, 'ACTIVE', 'SEED', FALSE, NULL,   2000000, NULL,    '00000000-0000-0000-0000-0000000000e1'),
 ('00000000-0000-0000-0000-0000000002c2', '00000000-0000-0000-0000-00000000f001', 'School fees',                'SCHOOL_FEES',  1500000, 'CUSTOM',  current_date + 72, NULL, 'ACTIVE', 'SEED', FALSE, NULL,    750000, NULL,    '00000000-0000-0000-0000-0000000000e1'),
 ('00000000-0000-0000-0000-0000000002c3', '00000000-0000-0000-0000-00000000f001', 'Car loan',                   'LOAN',          210000, 'MONTHLY', current_date + 22, 18,   'ACTIVE', 'SEED', FALSE, NULL,         0, 3840000, '00000000-0000-0000-0000-0000000000e1'),
 ('00000000-0000-0000-0000-0000000002c4', '00000000-0000-0000-0000-00000000f001', 'DEWA electricity and water', 'UTILITY',        90000, 'MONTHLY', current_date + 1,  NULL, 'ACTIVE', 'SEED', TRUE,  140000,      0, NULL,    '00000000-0000-0000-0000-0000000000e1'),
 ('00000000-0000-0000-0000-0000000002c5', '00000000-0000-0000-0000-00000000f001', 'du home and mobile',         'TELECOM',        45000, 'MONTHLY', current_date + 7,  NULL, 'ACTIVE', 'SEED', FALSE, NULL,         0, NULL,    '00000000-0000-0000-0000-0000000000e3'),
 ('00000000-0000-0000-0000-0000000002c6', '00000000-0000-0000-0000-00000000f001', 'Streaming bundle',           'SUBSCRIPTION',    5900, 'MONTHLY', current_date + 3,  NULL, 'ACTIVE', 'SEED', FALSE, NULL,         0, NULL,    '00000000-0000-0000-0000-0000000000e4');

INSERT INTO commitment_occurrence (commitment_id, due_on, amount_minor, label) VALUES
 ('00000000-0000-0000-0000-0000000002c1', current_date + 68,  3000000, 'Cheque 2 of 4'),
 ('00000000-0000-0000-0000-0000000002c1', current_date + 159, 3000000, 'Cheque 3 of 4'),
 ('00000000-0000-0000-0000-0000000002c1', current_date + 250, 3000000, 'Cheque 4 of 4'),
 ('00000000-0000-0000-0000-0000000002c1', current_date + 341, 3000000, 'Cheque 1 of 4, next contract'),
 ('00000000-0000-0000-0000-0000000002c2', current_date + 72,  1500000, 'Term 2'),
 ('00000000-0000-0000-0000-0000000002c2', current_date + 162, 1500000, 'Term 3'),
 ('00000000-0000-0000-0000-0000000002c2', current_date + 312, 1500000, 'Term 1, next year');

-- Budgets: the family's everyday money each month, and weekly pocket money per child
-- (Layla's AED 100 includes school canteen money).
INSERT INTO budget (family_id, member_id, category, period, limit_minor, bucket) VALUES
 ('00000000-0000-0000-0000-00000000f001', NULL, 'GROCERIES',     'MONTHLY', 300000, 'EVERYDAY'),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'EATING_OUT',    'MONTHLY', 150000, 'FUN'),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'ENTERTAINMENT', 'MONTHLY', 120000, 'FUN'),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'TRANSPORT',     'MONTHLY', 100000, 'EVERYDAY'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1', 'POCKET_MONEY', 'WEEKLY', 10000, 'EVERYDAY'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'POCKET_MONEY', 'WEEKLY',  6000, 'EVERYDAY');

-- Family rules, in the parents' own words (week 4: the agent drafts a rule from words like these, a parent turns it on).
-- Adam's allergy blocks anything whose name says peanut, and any food he asks for waits for a parent to check the label.
INSERT INTO family_rule (family_id, applies_to_member_id, kind, category, allergen, limit_minor, note, said, status, source, created_by, confirmed_by, confirmed_at) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'BLOCK_ALLERGEN', NULL, 'PEANUT', NULL, 'Adam has a peanut allergy. Always check the pack label.', 'No peanuts for Adam', 'ACTIVE', 'SEED', '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1', now()),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'BLOCK_CATEGORY', 'IN_GAME_PURCHASES', NULL, NULL, 'No in-game purchases', 'No in-game purchases', 'ACTIVE', 'SEED', '00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-0000000000a2', now()),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'BLOCK_CATEGORY', 'SUGARY_DRINKS', NULL, NULL, 'No fizzy drinks for Adam', 'No fizzy drinks for Adam', 'ACTIVE', 'SEED', '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1', now()),
 ('00000000-0000-0000-0000-00000000f001', NULL, 'APPROVAL_LIMIT', NULL, NULL, 5000, 'Anything over AED 50 needs a parent', 'Anything over AED 50 needs a parent', 'ACTIVE', 'SEED', '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1', now());

-- Demo wallets (DEMO MONEY) with an opening balance
INSERT INTO demo_wallet (id, family_id, member_id) VALUES
 ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1'),
 ('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2');

-- Weekly pocket money every Sunday for the last 11 weeks: Layla AED 100, Adam AED 60 (matches the budgets above).
-- "Today" is UAE time so it lines up with the core's clock.
INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description, created_at)
SELECT w.wallet_id, 'POCKET_MONEY', w.amount, 'Weekly pocket money (demo money)',
       (t.d - (extract(isodow FROM t.d)::int % 7) - 7 * k) + time '09:00'
FROM (SELECT (now() AT TIME ZONE 'Asia/Dubai')::date AS d) t
CROSS JOIN generate_series(0, 10) AS k
CROSS JOIN (VALUES ('00000000-0000-0000-0000-0000000000d1'::uuid, 10000),
                   ('00000000-0000-0000-0000-0000000000d2'::uuid, 6000)) AS w(wallet_id, amount);

INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description) VALUES
 ('00000000-0000-0000-0000-0000000000d1', 'PAYMENT', -1200, 'Bookshop (demo money)');

-- Goals: the emergency buffer first (Survival), then the summer trip (Good life), then the dream.
-- The kids' goals are their own and come out of their pocket money, not the family plan.
INSERT INTO goal (id, family_id, member_id, name, tier, target_minor, saved_minor, monthly_minor, target_date, priority) VALUES
 ('00000000-0000-0000-0000-0000000003f1', '00000000-0000-0000-0000-00000000f001', NULL, 'Emergency buffer', 'SURVIVAL',  2500000, 1000000, 500000, NULL,              10),
 ('00000000-0000-0000-0000-0000000003f2', '00000000-0000-0000-0000-00000000f001', NULL, 'Summer trip',      'GOOD_LIFE', 1200000,  360000, 150000, current_date + 248, 50),
 ('00000000-0000-0000-0000-0000000003f3', '00000000-0000-0000-0000-00000000f001', NULL, 'Home deposit',     'DREAM',    15000000,       0, NULL,   NULL,              90),
 ('00000000-0000-0000-0000-0000000003c1', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1', 'Headphones',   'GOOD_LIFE', 30000, 12000, NULL, NULL, 100),
 ('00000000-0000-0000-0000-0000000003c2', '00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 'Building set', 'GOOD_LIFE', 16000,  7200, NULL, NULL, 100);

INSERT INTO points_entry (family_id, member_id, points, reason) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c1', 40, 'Finished lesson: needs vs wants'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000c2', 25, 'Saved pocket money 2 weeks in a row');

-- A few parent payments, each tagged with what paid it. They happened before the cards' balances were typed in,
-- so the card balances already include them. The Visa statement demo file (documents/DemoSamples.java) has the
-- fuel and the dinner on it too: uploading it shows them as "already in your spending", not counted twice.
INSERT INTO ledger_transaction (family_id, member_id, occurred_on, description, merchant, category, direction, amount_minor,
                                duplicate_hash, source, paid_from_account_id) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', current_date - 5, 'Weekly groceries', 'Carrefour',        'GROCERIES',  'DEBIT', 61250, encode(digest('seed-1', 'sha256'), 'hex'), 'SEED', '00000000-0000-0000-0000-0000000000e4'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', current_date - 3, 'Fuel',             'ENOC',             'TRANSPORT',  'DEBIT', 18000, encode(digest('seed-2', 'sha256'), 'hex'), 'SEED', '00000000-0000-0000-0000-0000000000e3'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a1', current_date - 2, 'Dinner out',       'Local restaurant', 'EATING_OUT', 'DEBIT', 23500, encode(digest('seed-3', 'sha256'), 'hex'), 'SEED', '00000000-0000-0000-0000-0000000000e3');

-- Two earlier months of a streaming subscription on the Visa, read from earlier statements. Nobody put it on the
-- commitments list. Uploading this month's Visa statement adds the third, and the Bookkeeper finds it.
INSERT INTO ledger_transaction (family_id, member_id, occurred_on, description, merchant, category, direction, amount_minor,
                                duplicate_hash, source, paid_from_account_id) VALUES
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', current_date - 71, 'SHAHID VIP', 'SHAHID VIP', 'ENTERTAINMENT', 'DEBIT', 3500, encode(digest('seed-4', 'sha256'), 'hex'), 'DOCUMENT', '00000000-0000-0000-0000-0000000000e3'),
 ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-0000000000a2', current_date - 40, 'SHAHID VIP', 'SHAHID VIP', 'ENTERTAINMENT', 'DEBIT', 3500, encode(digest('seed-5', 'sha256'), 'hex'), 'DOCUMENT', '00000000-0000-0000-0000-0000000000e3');

-- Family outings (week 4), paid from the ENBD current account, so the Visa demo and the card warning are untouched.
-- With the two Shahid VIP payments above, the last 3 months average AED 1,520 against the AED 1,200 entertainment budget, and this month is already
-- AED 1,560: the overspend alert and the "Outings" tip in Ways to save. Amounts differ every month on purpose,
-- so none of them looks like a subscription. This month's dates never fall before the 1st.
INSERT INTO ledger_transaction (family_id, member_id, occurred_on, description, merchant, category, direction, amount_minor,
                                duplicate_hash, source, paid_from_account_id)
SELECT '00000000-0000-0000-0000-00000000f001', v.member,
       CASE WHEN v.months_ago = 0 THEN greatest(date_trunc('month', current_date)::date, current_date - v.day)
            ELSE (date_trunc('month', current_date) - make_interval(months => v.months_ago))::date + v.day END,
       v.descr, v.merchant, 'ENTERTAINMENT', 'DEBIT', v.amt,
       encode(digest('seed-fun-' || v.months_ago || '-' || v.merchant || '-' || v.amt, 'sha256'), 'hex'), 'SEED',
       '00000000-0000-0000-0000-0000000000e1'
FROM (VALUES
 -- this month: day = days ago
 (0,  9, '00000000-0000-0000-0000-0000000000a1'::uuid, 'KidZania tickets',        'KidZania',           56000),
 (0,  6, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Theme park day',          'Dubai Parks',        64000),
 (0,  4, '00000000-0000-0000-0000-0000000000a1'::uuid, 'Cinema, family of four',  'VOX Cinemas',        18500),
 (0,  1, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Bowling',                 'Bowling alley',      17500),
 -- last month and the two before: day = day of that month
 (1,  4, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Ski Dubai',               'Ski Dubai',          69000),
 (1, 11, '00000000-0000-0000-0000-0000000000a1'::uuid, 'Cinema, family of four',  'VOX Cinemas',        22500),
 (1, 17, '00000000-0000-0000-0000-0000000000a1'::uuid, 'Aquarium tickets',        'Dubai Aquarium',     38000),
 (1, 24, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Bowling',                 'Bowling alley',      18500),
 (2,  3, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Water park',              'Wild Wadi',          78000),
 (2, 10, '00000000-0000-0000-0000-0000000000a1'::uuid, 'Cinema, family of four',  'VOX Cinemas',        24000),
 (2, 16, '00000000-0000-0000-0000-0000000000a1'::uuid, 'KidZania tickets',        'KidZania',           42000),
 (2, 23, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Escape room',             'Escape room',        18000),
 (3,  5, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Global Village',          'Global Village',     35000),
 (3, 12, '00000000-0000-0000-0000-0000000000a1'::uuid, 'Cinema, family of four',  'VOX Cinemas',        19500),
 (3, 18, '00000000-0000-0000-0000-0000000000a1'::uuid, 'Dubai Frame tickets',     'Dubai Frame',        31500),
 (3, 25, '00000000-0000-0000-0000-0000000000a2'::uuid, 'Trampoline park',         'Trampoline park',    53000)
) AS v(months_ago, day, member, descr, merchant, amt);

-- ---------------------------------------------------------------- saving in gold (week 3)
-- Gold the family already holds (from the app mockups): 40 g of 24K for the home deposit and a 22K bangle for the
-- emergency buffer. Valued at today's sample price below: 40 g x AED 320 + 10 g x AED 296.50 = AED 15,765.
-- The bangle is worth less than was paid (making charges), so the screen shows the jewellery note.
INSERT INTO gold_holding (family_id, description, grams_mg, purity, paid_minor, bought_on, seller, bucket, goal_id,
                          is_jewellery, created_by) VALUES
 ('00000000-0000-0000-0000-00000000f001', 'Gold bar',            20000, '24K', 580000, current_date - 229, 'Gold Souk, Deira',     'GOALS', '00000000-0000-0000-0000-0000000003f3', FALSE, '00000000-0000-0000-0000-0000000000a2'),
 ('00000000-0000-0000-0000-00000000f001', 'Gold coins, 4 x 5 g', 20000, '24K', 620000, current_date - 115, 'Mall of the Emirates', 'GOALS', '00000000-0000-0000-0000-0000000003f3', FALSE, '00000000-0000-0000-0000-0000000000a1'),
 ('00000000-0000-0000-0000-00000000f001', 'Bangle',              10000, '22K', 340000, current_date - 40,  'Gold Souk, Deira',     'GOALS', '00000000-0000-0000-0000-0000000003f1', TRUE,  '00000000-0000-0000-0000-0000000000a1');

-- Today's price for the demo, so the "as of" time is always this morning. A sample, not a real rate.
INSERT INTO gold_price (family_id, purity, per_gram_minor, as_of, source, seeded) VALUES
 ('00000000-0000-0000-0000-00000000f001', '24K', 32000, least(now(), date_trunc('day', now() AT TIME ZONE 'Asia/Dubai') AT TIME ZONE 'Asia/Dubai' + interval '10 hours'), 'Sample Dubai retail rate (demo)', TRUE),
 ('00000000-0000-0000-0000-00000000f001', '22K', 29650, least(now(), date_trunc('day', now() AT TIME ZONE 'Asia/Dubai') AT TIME ZONE 'Asia/Dubai' + interval '10 hours'), 'Sample Dubai retail rate (demo)', TRUE),
 ('00000000-0000-0000-0000-00000000f001', '21K', 28300, least(now(), date_trunc('day', now() AT TIME ZONE 'Asia/Dubai') AT TIME ZONE 'Asia/Dubai' + interval '10 hours'), 'Sample Dubai retail rate (demo)', TRUE),
 ('00000000-0000-0000-0000-00000000f001', '18K', 24250, least(now(), date_trunc('day', now() AT TIME ZONE 'Asia/Dubai') AT TIME ZONE 'Asia/Dubai' + interval '10 hours'), 'Sample Dubai retail rate (demo)', TRUE);

-- ---------------------------------------------------------------- kids' logged spending (chat logging)
-- About 10 weeks of what Layla and Adam told the agent they bought, so "where did Layla's money go this month?"
-- has something to answer from on any day you reseed. Amounts in fils. Some school days are missing on purpose:
-- real kids forget to log, and the parent's answer should say so.
--
-- Layla (13): canteen Monday to Thursday, crisps on Friday, bubble tea every other Saturday, plus one-offs
-- Adam (9):   canteen Tue/Thu, ice cream on Saturday, cards or stickers every Sunday, one Robux card and
--             one cola (both against family rules)
WITH today AS (SELECT (now() AT TIME ZONE 'Asia/Dubai')::date AS d),
days AS (
    SELECT g AS ago, t.d - g AS d, extract(isodow FROM t.d - g)::int AS dow, (extract(week FROM t.d - g)::int % 2) AS odd_week
    FROM today t, generate_series(1, 74) AS g   -- nothing today, so the agent's nudge shows in the demo
),
habits AS (
    -- Layla
    SELECT '00000000-0000-0000-0000-0000000000c1'::uuid AS member, ago, d,
           (ARRAY['Chicken wrap', 'Pasta', 'Sandwich and juice'])[ago % 3 + 1] AS descr,
           'School canteen' AS merchant, 'SCHOOL_CANTEEN' AS cat, (ARRAY[800, 700, 900])[ago % 3 + 1] AS amt
    FROM days WHERE dow IN (1, 2, 3, 4) AND ago % 7 <> 3
    UNION ALL
    SELECT '00000000-0000-0000-0000-0000000000c1', ago, d, 'Crisps and water', 'School canteen', 'SNACKS', 400
    FROM days WHERE dow = 5 AND ago % 3 <> 0
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
    UNION ALL
    SELECT '00000000-0000-0000-0000-0000000000c2', ago, d,
           CASE WHEN odd_week = 1 THEN 'Pokemon cards' ELSE 'Football stickers' END, 'Toy shop', 'TOYS_AND_HOBBIES',
           CASE WHEN odd_week = 1 THEN 1500 ELSE 800 END
    FROM days WHERE dow = 7
),
one_offs AS (
    SELECT v.member, v.ago, t.d - v.ago AS d, v.descr, v.merchant, v.cat, v.amt
    FROM today t, (VALUES
     -- Layla
     ('00000000-0000-0000-0000-0000000000c1'::uuid,  2, 'Shawarma with friends',   'Shawarma place', 'EATING_OUT',              1200),
     ('00000000-0000-0000-0000-0000000000c1'::uuid,  5, 'Phone case',              'Mall kiosk',     'CLOTHES_AND_ACCESSORIES', 2500),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 12, 'Birthday gift for Mariam', NULL,            'GIFTS_AND_CHARITY',       2500),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 19, 'Notebook and gel pens',   'Bookshop',       'BOOKS_AND_STATIONERY',    1500),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 26, 'Popcorn at the cinema',   'Cinema',         'SNACKS',                  1800),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 33, 'Metro card top-up',       'Metro',          'TRANSPORT',               2000),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 40, 'Lip balm and hair clips', 'Pharmacy',       'CLOTHES_AND_ACCESSORIES', 2000),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 47, 'Shawarma with friends',   'Shawarma place', 'EATING_OUT',              1200),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 54, 'Gift for Teachers'' Day', NULL,             'GIFTS_AND_CHARITY',       2000),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 61, 'Sketchbook',              'Bookshop',       'BOOKS_AND_STATIONERY',    2200),
     ('00000000-0000-0000-0000-0000000000c1'::uuid, 68, 'Popcorn at the cinema',   'Cinema',         'SNACKS',                  1800),
     -- Adam
     ('00000000-0000-0000-0000-0000000000c2'::uuid,  3, 'Cola',                    'Supermarket',    'SUGARY_DRINKS',            300),
     ('00000000-0000-0000-0000-0000000000c2'::uuid,  6, 'Robux gift card',         'Supermarket',    'IN_GAME_PURCHASES',       2000),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 20, 'Slime kit',               'Toy shop',       'TOYS_AND_HOBBIES',        1200),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 34, 'Lego minifigure',         'Toy shop',       'TOYS_AND_HOBBIES',        1800),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 48, 'Charity box at school',   NULL,             'GIFTS_AND_CHARITY',        500),
     ('00000000-0000-0000-0000-0000000000c2'::uuid, 55, 'Slime kit',               'Toy shop',       'TOYS_AND_HOBBIES',        1200)
    ) AS v(member, ago, descr, merchant, cat, amt)
)
INSERT INTO ledger_transaction (family_id, member_id, occurred_on, description, merchant, category, direction,
                                amount_minor, duplicate_hash, source, logged_text)
SELECT '00000000-0000-0000-0000-00000000f001', x.member, x.d, x.descr, x.merchant, x.cat, 'DEBIT', x.amt,
       encode(digest('seed-kid-' || x.member || '-' || x.ago || '-' || x.descr, 'sha256'), 'hex'), 'SEED',
       (x.amt / 100) || ' dirhams ' || lower(x.descr)
FROM (SELECT * FROM habits UNION ALL SELECT * FROM one_offs) AS x;

-- What the kids logged came out of their demo wallets. Layla also moved AED 120 into her headphones goal
-- and Adam AED 72 into his building set (the saved amounts on their goals).
INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description)
SELECT w.id, 'PAYMENT', -SUM(t.amount_minor), 'Spending logged in chat (demo money)'
FROM demo_wallet w JOIN ledger_transaction t ON t.member_id = w.member_id AND t.source = 'SEED'
WHERE w.family_id = '00000000-0000-0000-0000-00000000f001'
GROUP BY w.id;
INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description) VALUES
 ('00000000-0000-0000-0000-0000000000d1', 'SAVINGS_MOVE', -12000, 'Into the headphones goal (demo money)'),
 ('00000000-0000-0000-0000-0000000000d2', 'SAVINGS_MOVE',  -7200, 'Into the building set goal (demo money)');
