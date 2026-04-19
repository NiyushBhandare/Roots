# SECURITY.md

This document describes how Roots protects your data, what it explicitly
does **not** protect against, and why each choice was made.

It is written in the voice of the project author and is meant to be read
by a thoughtful user or a code reviewer — not as marketing.

---

## The threat model

Roots is a **local-first** application. Your ecosystem — subscriptions,
repositories, ideas, audit log — lives in a single SQLite database file
at `~/.roots/roots.db` on your own machine. There is no server, no
account you don't own, no telemetry, no network egress.

Because Roots never talks to the network, the threat model is radically
simpler than a typical web app. The only way an attacker can harm you
is if they gain **local access** to your machine. Given that constraint,
here's what we worry about, in order of seriousness:

1. **Someone steals or inspects your laptop** and tries to read the
   `~/.roots/roots.db` file directly — bypassing the Roots UI entirely.
2. **Someone with a terminal session** on your machine tries to read
   your data by running `sqlite3 ~/.roots/roots.db` or `cat` on the file.
3. **Someone tampers with an audit log row directly** in the database
   file to hide an action they took, then closes the file before you
   notice.
4. **A hostile application on your machine** tries to sniff the Roots
   HTTP port and issue requests to the API.
5. **A forensic tool** inspects the `~/.roots` directory while Roots
   isn't running.

Roots is **not** designed to protect against a sophisticated attacker
with root/kernel access, a memory dumper running against the Roots
JVM process, or a compromised OS. If your attacker can read your JVM's
memory, nothing a userspace app can do will save you.

---

## What Roots does about it

### 1. Passwords

Passwords are hashed with **PBKDF2-HMAC-SHA256** at **600,000
iterations** and a fresh random 16-byte salt per user. The stored
format is self-describing:

```
pbkdf2$600000$<base64-salt>$<base64-hash>
```

600,000 iterations is the OWASP 2024 recommendation for PBKDF2-SHA256.
Verifying a single password takes roughly 300ms on modern hardware,
which makes a brute-force attack against a stolen hash file infeasible.

**Why not Argon2id?** Argon2id is the OWASP gold standard and we would
love to use it. Every Java library that implements Argon2id ships a
native shared library (JNI binding). Adding a native dependency days
before a project deadline is a build-risk we explicitly rejected.
PBKDF2-HMAC-SHA256 lives inside the JDK itself, works on every JVM,
and is on the OWASP list of acceptable alternatives.

**Legacy support.** The seeded admin and viewer accounts shipped with
the database use the older **BCrypt** format (`$2a$12$...`). The password
verifier recognises both formats. When a legacy BCrypt user logs in
successfully, their hash is transparently upgraded to the new PBKDF2
format on the way out, so the migration happens organically with use.

All of this lives in `src/main/java/com/atlas/roots/util/CryptoService.java`.

### 2. Sensitive fields are encrypted at rest

Your node **descriptions** — the text fields where you actually write
what a thing is and why it matters — are encrypted with
**AES-256-GCM** before they hit the database. AES-GCM is an
authenticated cipher: an attacker who tampers with a ciphertext blob
cannot silently produce garbage, the decryption fails visibly with a
tag mismatch.

The format is `enc1:<base64-iv>:<base64-ciphertext>`, with a 12-byte
random IV per row, a 128-bit auth tag, and the `enc1:` prefix as a
version marker so future versions can read old blobs.

**The key.** The AES key is derived from your plaintext password at
login time via PBKDF2-HMAC-SHA256 (fewer iterations than the password
hash — the cost is paid once per session, and the password has already
been authenticated at that point). The salt is pulled from your stored
password hash, mixed with a `DATAKEY_` domain-separation tag so the
data key is mathematically distinct from the password hash even though
they share a salt.

**Key lifecycle.** The AES key lives in memory only, inside a map
held by `VaultGuardian`. When you log out, the byte array is zeroed
out and the map entry is dropped. When the JVM exits, the key is gone.
**At no point is the key written to disk.** If you want to read your
own database file without the key (i.e., from `sqlite3`), the
descriptions show as opaque Base64 blobs.

**Why field-level instead of SQLCipher?** SQLCipher would encrypt the
whole database file transparently, which sounds better. It isn't,
for us. SQLCipher requires a native library not available on Maven
Central, and adding a native dependency days before a deadline is
the kind of risk that kills projects. Field-level AES-256-GCM with
JDK primitives gives us the same security outcome for the fields
that actually matter — an attacker reading `~/.roots/roots.db`
directly cannot recover your descriptions. The tradeoff is that
row counts, column names, dates, and numeric fields remain visible
in the raw file. For the local-first threat model (stolen laptop,
terminal snoop, forensic recovery) this is the right level. The
pointed data is protected; the metadata is acceptable to lose.

### 3. The audit log is tamper-evident

Every action in Roots — login, logout, node create/update/delete,
signup, markdown import — is recorded in the `audit_log` table as
an **append-only** row. There is no public API that updates or
deletes audit rows.

Each row carries two SHA-256 hashes:

- `prev_hash`: the `this_hash` of the row inserted immediately before it
- `this_hash`: `SHA-256(prev_hash || user_id || action || detail || created_at)`

This forms a **hash chain**: mutating any historical row changes its
content, which changes its `this_hash`, which breaks the `prev_hash`
link of the row after it, and every row from that point forward.

The application provides a **"VERIFY CHAIN"** button in the Audit Log
view. It hits `GET /api/audit/verify`, which walks the entire log from
the earliest row, recomputes every hash, and reports:

- `{intact: true, rowsChecked: N}` — the log is unmodified since insertion
- `{intact: false, brokenAtId: X, reason: "..."}` — the first row where
  the chain is broken, with a human-readable reason

This is the proof mechanism. An attacker with DB access can mutate
any row, but they cannot do so silently. Anyone can click the button
and see the exact row ID where the tampering was detected.

Code: `CryptoService.chainHash()`, `AuditDao.log()`, `AuditDao.verifyChain()`.

### 4. The server binds to localhost only

The Javalin HTTP server is started with an explicit bind address:

```java
app.start("127.0.0.1", PORT);
```

This means the Roots server is **not reachable from other machines**
on the same network, even if you're on hostile WiFi at a cafe. A
hostile application on the *same* machine can still reach it — which
is consistent with the rest of the threat model (kernel-level attackers
win regardless).

The boot banner prints `(bound to 127.0.0.1 — not reachable from other
machines)` so the user can verify at a glance.

### 5. Input validation is centralised

Every form field on every CRUD page is validated before it hits the
database. The rules live in a single file, `Validators.java`, so
there is exactly one place to audit them. Violations throw
`ValidationException` (our own subtype of `IllegalArgumentException`),
which the web layer catches and converts to HTTP 400 with a human-
readable message in the JSON body.

The rules include:
- username must be 3–24 lowercase alphanumeric + underscore
- password must be at least 8 characters
- currency must be a 3-letter ISO code
- numeric ranges on joy rating (0–10), cost (≥0), commit count (≥0)
- dates cannot be in the future for historical fields like `started_on`

---

## What Roots does **not** protect against

Being explicit about the gaps is more important than being generous
about the wins. Roots does not try to solve:

- **Memory-resident attackers.** A process running on your machine as
  the same user as the Roots JVM can read the session key directly
  out of the heap. The only defense against that is OS-level process
  isolation, which is not our job.
- **Keyloggers.** If your attacker can read your keystrokes, they can
  read your password and nothing we do matters.
- **Trojaned JVMs or compromised JDKs.** If the crypto primitives
  themselves are lying to us, we lose.
- **Cold boot attacks on unencrypted disks.** Use FileVault or LUKS.
  Roots does not replace full-disk encryption; it complements it.
- **Weak passwords.** We enforce an 8-character minimum and that's it.
  We don't require symbols, we don't rate-limit login attempts beyond
  the BCrypt/PBKDF2 cost itself, and we don't disallow dictionary words.
  If you pick "password123" as your password, Roots cannot save you.
- **Physical access to an unlocked laptop with Roots already logged in.**
  If your session is active and someone sits down at your keyboard,
  they have everything. Log out when you step away.

---

## For a reviewer: how to verify these claims hands-on

1. **Field encryption** — `sqlite3 ~/.roots/roots.db "SELECT description FROM nodes LIMIT 5"`
   should return opaque strings starting with `enc1:` (once you log in
   and write a few nodes post-encryption). These are not readable
   without the session key.
2. **Tamper detection** — open DB Browser for SQLite, modify any row
   in `audit_log` (change the `detail` column of any historical row),
   save, then in Roots click **Audit Log → VERIFY CHAIN**. The UI
   will report the exact row ID where the chain broke.
3. **Password upgrade** — log in as the seeded `draco` user (BCrypt
   hash). Check `~/.roots/roots.db` immediately after: the
   `users.password_hash` column for that user will now start with
   `pbkdf2$600000$` instead of `$2a$12$`.
4. **Localhost binding** — `nmap -p 3000 <your-machine-ip>` from another
   machine on the same network should show the port as closed/filtered,
   not open. `curl http://localhost:3000` from the same machine works.
5. **Session key lifecycle** — take a heap dump while logged in, you
   can find the key. Log out, take another heap dump, the key bytes
   are zeroed. (This is hard to verify rigorously because GC may have
   moved the array, but the `Arrays.fill(key, 0)` call is visible in
   `VaultGuardian.logout()`.)

---

## Summary

| Concern | How Roots handles it | Strength |
|---|---|---|
| Password hashing | PBKDF2-SHA256 @ 600k iterations | OWASP 2024 acceptable |
| Legacy password support | BCrypt verifier + transparent upgrade on login | Safe migration |
| Sensitive fields at rest | AES-256-GCM with per-session key | Strong |
| Audit log integrity | SHA-256 hash chain with verify endpoint | Tamper-evident |
| Network exposure | 127.0.0.1 bind, no remote | Not exposed |
| Input validation | Centralised `Validators`, typed exceptions | Consistent |
| Key lifecycle | In-memory only, zeroed on logout | Session-scoped |

Roots is security-aware, not security-omniscient. Read this document
before you trust it with anything you wouldn't want a roommate to read
over your shoulder.
