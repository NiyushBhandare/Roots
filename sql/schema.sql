-- =====================================================================
--  ROOTS — A Cognitive and Financial Operating System
--  Schema: schema.sql
--  Database: SQLite 3
--  Author: Draco Bhandare
-- =====================================================================
--
--  Design notes:
--  -------------
--  RootNode is modelled with a "table-per-hierarchy + attribute tables"
--  pattern. The `nodes` table holds the columns common to every subtype
--  (id, type discriminator, name, timestamps, owner). Each subtype has
--  its own attribute table (sub_attrs, repo_attrs, idea_attrs) joined
--  by node_id. This keeps queries on the common shape fast while letting
--  each subtype evolve its own columns without nullable bloat.
--
--  Foreign keys are enabled per-connection in DatabaseManager via
--  `PRAGMA foreign_keys = ON;` (SQLite ships with FKs off by default).
-- =====================================================================

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------
-- 1.  USERS  — authentication and role-based access
-- ---------------------------------------------------------------------
-- Local auth: username + bcrypt password hash.
-- Optional Google auth: link a user to a Google account via OAuth 2.0.
--   google_sub           — Google's stable user ID ("subject"); source of truth
--   google_email         — email at link time, for display only (users change emails)
--   google_access_token  — short-lived (1hr) token, encrypted at rest
--   google_refresh_token — long-lived token for silent reauth, encrypted at rest
--   google_token_expires — when the access token expires, used to know when to refresh
-- These are nullable because a user may have only local auth, only Google auth,
-- or both linked to the same account.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    username               TEXT    NOT NULL UNIQUE,
    password_hash          TEXT    NOT NULL,
    role                   TEXT    NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at             TEXT    NOT NULL DEFAULT (datetime('now')),
    google_sub             TEXT    UNIQUE,
    google_email           TEXT,
    google_access_token    TEXT,
    google_refresh_token   TEXT,
    google_token_expires   TEXT
);

-- ---------------------------------------------------------------------
-- 2.  NODES  — the polymorphic root table
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    type            TEXT    NOT NULL CHECK (type IN ('SUB', 'REPO', 'IDEA')),
    name            TEXT    NOT NULL,
    description     TEXT,
    owner_id        INTEGER NOT NULL,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now')),
    last_touched    TEXT    NOT NULL DEFAULT (datetime('now')),
    archived        INTEGER NOT NULL DEFAULT 0,    -- boolean: 0 = active, 1 = archived
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_nodes_type        ON nodes(type);
CREATE INDEX IF NOT EXISTS idx_nodes_owner       ON nodes(owner_id);
CREATE INDEX IF NOT EXISTS idx_nodes_last_touched ON nodes(last_touched);

-- ---------------------------------------------------------------------
-- 3.  SUB_ATTRS  — subscription-specific columns
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sub_attrs (
    node_id         INTEGER PRIMARY KEY,
    monthly_cost    REAL    NOT NULL CHECK (monthly_cost >= 0),
    currency        TEXT    NOT NULL DEFAULT 'INR',
    cadence         TEXT    NOT NULL CHECK (cadence IN ('MONTHLY','YEARLY','WEEKLY','ONE_TIME')),
    joy_rating      INTEGER NOT NULL CHECK (joy_rating BETWEEN 0 AND 10),
    started_on      TEXT    NOT NULL,
    next_renewal    TEXT,
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 4.  REPO_ATTRS  — git repository-specific columns
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS repo_attrs (
    node_id             INTEGER PRIMARY KEY,
    local_path          TEXT    NOT NULL,
    remote_url          TEXT,
    primary_language    TEXT,
    commit_count        INTEGER NOT NULL DEFAULT 0 CHECK (commit_count >= 0),
    last_commit_at      TEXT,
    stale_threshold_days INTEGER NOT NULL DEFAULT 30 CHECK (stale_threshold_days > 0),
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 5.  IDEA_ATTRS  — Obsidian note-specific columns
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idea_attrs (
    node_id         INTEGER PRIMARY KEY,
    vault_path      TEXT    NOT NULL,
    word_count      INTEGER NOT NULL DEFAULT 0 CHECK (word_count >= 0),
    backlink_count  INTEGER NOT NULL DEFAULT 0 CHECK (backlink_count >= 0),
    last_edited_at  TEXT,
    tags            TEXT,                    -- comma-separated, simple for v1
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 6.  AUDIT_LOG  — every mutation leaves a trace
--
-- The `prev_hash` and `this_hash` columns form a tamper-evident hash
-- chain: each row's `this_hash` is SHA-256 over its predecessor's
-- `this_hash` concatenated with the row's own content. Mutating any
-- historical row (or deleting one) breaks the chain at that point,
-- and AuditDao.verifyChain() will report the exact row where the
-- break was detected.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id     INTEGER,
    user_id     INTEGER NOT NULL,
    action      TEXT    NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE','LOGIN','LOGOUT')),
    detail      TEXT,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    prev_hash   TEXT,
    this_hash   TEXT,
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE SET NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_audit_user    ON audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);

-- ---------------------------------------------------------------------
-- 7.  VITALITY_SNAPSHOTS  — historical vitality readings (for charts)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vitality_snapshots (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id     INTEGER NOT NULL,
    vitality    REAL    NOT NULL CHECK (vitality BETWEEN 0 AND 1),
    joy_score   REAL    NOT NULL CHECK (joy_score BETWEEN 0 AND 1),
    captured_at TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_snapshots_node ON vitality_snapshots(node_id);

-- ---------------------------------------------------------------------
-- 8.  SKILLS  — TF-IDF-extracted skill clusters from a user's vault
-- ---------------------------------------------------------------------
-- A row per inferred skill per user. Rebuilt from scratch by
-- SkillExtractor.recomputeForUser() — not user-editable via CRUD.
--
-- `tokens` stores the member keywords as a comma-separated list (e.g.
-- "seen,metrics,marketing,formula"). Keeping them inline rather than
-- in a join table is a deliberate simplification: skills are rebuilt
-- atomically, so there is no need for per-token referential integrity.
--
-- `confidence` is the average TF-IDF weight of the cluster's tokens,
-- roughly 0..1. Used to sort skills by strength in the UI.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skills (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id      INTEGER NOT NULL,
    name          TEXT    NOT NULL,
    tokens        TEXT    NOT NULL,
    idea_count    INTEGER NOT NULL DEFAULT 0,
    confidence    REAL    NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_skills_owner ON skills(owner_id);

-- ---------------------------------------------------------------------
-- 9.  SKILL_OVERRIDES  — user renames that survive re-extraction
-- ---------------------------------------------------------------------
-- Skills are rebuilt atomically on every recompute, so a naive rename
-- would be blown away on the next re-extract. This table stores
-- renames keyed by a *signature* of the cluster (sorted top-3 tokens
-- joined by '|'). After extraction, any new cluster whose signature
-- matches an override has its name replaced with the user's preferred
-- name. Overrides for clusters that don't re-appear are retained —
-- the user may add more Ideas later and the cluster could return.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skill_overrides (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id     INTEGER NOT NULL,
    signature    TEXT    NOT NULL,   -- sorted top-3 tokens, pipe-delimited
    display_name TEXT    NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE (owner_id, signature)
);

CREATE INDEX IF NOT EXISTS idx_skill_overrides_owner ON skill_overrides(owner_id);

-- ---------------------------------------------------------------------
-- 10.  SKILL_SUBS  — many-to-many: which subscriptions belong to which skills
-- ---------------------------------------------------------------------
-- A subscription can support multiple skills (Notion → writing + research),
-- and a skill is expressed through multiple subscriptions (AI Engineering
-- → Claude Pro + Cursor Pro + GitHub Copilot). This join table encodes
-- that relationship with a single row per pairing.
--
-- The (sub_id, skill_id) pair is unique — attempting to link the same
-- pair twice is a no-op via INSERT OR IGNORE. Deletes cascade from
-- either side so stale rows never outlive their nodes.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skill_subs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id   INTEGER NOT NULL,
    sub_id     INTEGER NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE,
    FOREIGN KEY (sub_id)   REFERENCES nodes(id)  ON DELETE CASCADE,
    UNIQUE (skill_id, sub_id)
);

CREATE INDEX IF NOT EXISTS idx_skill_subs_skill ON skill_subs(skill_id);
CREATE INDEX IF NOT EXISTS idx_skill_subs_sub   ON skill_subs(sub_id);
