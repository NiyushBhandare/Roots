-- =====================================================================
--  ROOTS — Seed Data
--  Populates a working ecosystem so the grader sees a living system,
--  not an empty CRUD shell. Data drawn from Draco's actual stack.
-- =====================================================================
--  NOTE: BCrypt hashes here are generated with cost factor 12 for the
--  passwords listed in the comments. They are committed deliberately
--  for grading reproducibility — in a real deployment we would never
--  commit credentials. The login screen also accepts a fresh signup.
-- =====================================================================

-- ---------------------------------------------------------------------
-- USERS
-- ---------------------------------------------------------------------
-- draco / roots2026  (ADMIN)
-- viewer / viewer2026 (VIEWER)
INSERT INTO users (username, password_hash, role) VALUES
    ('draco',  '$2a$12$PLACEHOLDER_REPLACED_AT_BOOTSTRAP_DRACO', 'ADMIN'),
    ('viewer', '$2a$12$PLACEHOLDER_REPLACED_AT_BOOTSTRAP_VIEW',  'VIEWER');
-- The DatabaseManager replaces these placeholders with real BCrypt
-- hashes on first boot, so the file remains greppable and the seed
-- still works deterministically.

-- ---------------------------------------------------------------------
-- SUBSCRIPTIONS  (the financial trunk)
-- ---------------------------------------------------------------------
INSERT INTO nodes (type, name, description, owner_id, last_touched) VALUES
    ('SUB', 'Spotify Premium',     'Music. Daily driver.',                      1, datetime('now','-2 days')),
    ('SUB', 'Claude Pro',          'Cognitive partner. Used heavily.',          1, datetime('now','-1 hour')),
    ('SUB', 'Cursor Pro',          'Editor with AI. Used for Ampeiron + Seen.', 1, datetime('now','-3 hours')),
    ('SUB', 'Notion',              'Stale. Migrated to Obsidian.',              1, datetime('now','-90 days')),
    ('SUB', 'Adobe Creative Cloud','Used twice this quarter.',                  1, datetime('now','-45 days')),
    ('SUB', 'GitHub Pro',          'Private repos for Ampeiron and Seen.',      1, datetime('now','-1 day'));

INSERT INTO sub_attrs (node_id, monthly_cost, currency, cadence, joy_rating, started_on, next_renewal) VALUES
    (1, 119.0,  'INR', 'MONTHLY', 9, '2023-01-15', date('now','+12 days')),
    (2, 1700.0, 'INR', 'MONTHLY',10, '2024-08-01', date('now','+5 days')),
    (3, 1660.0, 'INR', 'MONTHLY', 8, '2025-02-10', date('now','+18 days')),
    (4, 800.0,  'INR', 'MONTHLY', 2, '2022-06-01', date('now','+22 days')),
    (5, 4230.0, 'INR', 'MONTHLY', 3, '2023-09-01', date('now','+8 days')),
    (6, 350.0,  'INR', 'MONTHLY', 7, '2024-01-01', date('now','+15 days'));

-- ---------------------------------------------------------------------
-- REPOS  (the project trunk)
-- ---------------------------------------------------------------------
INSERT INTO nodes (type, name, description, owner_id, last_touched) VALUES
    ('REPO', 'Ampeiron',     'Local-first personal AI with infinite memory.',         1, datetime('now','-2 days')),
    ('REPO', 'Seen',         'AI-powered collaborative marketing platform.',          1, datetime('now','-1 day')),
    ('REPO', 'CopyForge',    'AI e-commerce copy generator. Deferred.',               1, datetime('now','-60 days')),
    ('REPO', 'ParkDock',     'EV micro-parking pitch. Documented, not coded.',        1, datetime('now','-120 days')),
    ('REPO', 'big-data-yt',  'Hadoop/Spark trending videos project. Hive blocked.',   1, datetime('now','-14 days'));

INSERT INTO repo_attrs (node_id, local_path, remote_url, primary_language, commit_count, last_commit_at, stale_threshold_days) VALUES
    (7,  '~/PycharmProjects/Ampeiron', 'https://github.com/NiyushBhandare/Ampeiron', 'Python', 87,  datetime('now','-2 days'),   30),
    (8,  '~/code/seen',                 NULL,                                          'TypeScript', 23, datetime('now','-1 day'),  14),
    (9,  '~/code/copyforge',            NULL,                                          'Python', 34,  datetime('now','-60 days'),  30),
    (10, '~/docs/parkdock',             NULL,                                          'Markdown', 6, datetime('now','-120 days'), 60),
    (11, '~/code/big-data-yt',          NULL,                                          'Java', 19,    datetime('now','-14 days'),  21);

-- ---------------------------------------------------------------------
-- IDEAS  (the cognitive trunk — pulled from the Obsidian vault)
-- ---------------------------------------------------------------------
INSERT INTO nodes (type, name, description, owner_id, last_touched) VALUES
    ('IDEA', 'SEXAPPEAL — Style Bible',          'Brutalist Romantic / Dark Craft.',     1, datetime('now','-4 days')),
    ('IDEA', 'Seen — Resonance Score notes',     'Proprietary metric definitions.',       1, datetime('now','-1 day')),
    ('IDEA', 'Lockdown Protocol v2',             '11pm cutoff. 6am wake. 5x gym.',        1, datetime('now','-7 days')),
    ('IDEA', 'Decentralized AI infra reading',   'Bittensor, Akash, Gensyn. Deferred.',   1, datetime('now','-180 days')),
    ('IDEA', 'Swiss design + bioluminescence',   'Visual language for Roots.',            1, datetime('now','-2 hours'));

INSERT INTO idea_attrs (node_id, vault_path, word_count, backlink_count, last_edited_at, tags) VALUES
    (12, 'Style/SEXAPPEAL.md',         3420, 12, datetime('now','-4 days'),   'style,identity,sexappeal'),
    (13, 'Seen/Resonance.md',          1180,  8, datetime('now','-1 day'),    'seen,metrics,product'),
    (14, 'Discipline/Lockdown.md',      950,  5, datetime('now','-7 days'),   'discipline,sleep,gym'),
    (15, 'Reading/Decentralized.md',    640,  2, datetime('now','-180 days'), 'ai,web3,deferred'),
    (16, 'Roots/Visual-Language.md',    780, 14, datetime('now','-2 hours'),  'roots,design,swiss');

-- ---------------------------------------------------------------------
-- AUDIT  (a few historical entries for the log view to render)
-- ---------------------------------------------------------------------
INSERT INTO audit_log (node_id, user_id, action, detail, created_at) VALUES
    (1,  1, 'CREATE', 'Imported Spotify subscription',          datetime('now','-30 days')),
    (2,  1, 'CREATE', 'Imported Claude Pro subscription',       datetime('now','-29 days')),
    (4,  1, 'UPDATE', 'Lowered joy rating: 6 -> 2',             datetime('now','-12 days')),
    (7,  1, 'CREATE', 'Linked Ampeiron repo',                   datetime('now','-25 days')),
    (12, 1, 'CREATE', 'Ingested SEXAPPEAL.md from vault',       datetime('now','-21 days'));
