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

INSERT INTO demo_wallet_entry (wallet_id, kind, amount_minor, description) VALUES
 ('00000000-0000-0000-0000-0000000000d1', 'POCKET_MONEY', 5000, 'Weekly pocket money (demo money)'),
 ('00000000-0000-0000-0000-0000000000d1', 'PAYMENT',     -1200, 'Bookshop (demo money)'),
 ('00000000-0000-0000-0000-0000000000d2', 'POCKET_MONEY', 3000, 'Weekly pocket money (demo money)');

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
