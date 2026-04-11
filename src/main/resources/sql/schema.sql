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
CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        TEXT    NOT NULL UNIQUE,
    password_hash   TEXT    NOT NULL,        -- BCrypt hash
    role            TEXT    NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at      TEXT    NOT NULL DEFAULT (datetime('now'))
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
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id     INTEGER,
    user_id     INTEGER NOT NULL,
    action      TEXT    NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE','LOGIN','LOGOUT')),
    detail      TEXT,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
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
