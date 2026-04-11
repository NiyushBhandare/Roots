# ROOTS

> A Cognitive and Financial Operating System.
> Local-first. Human-centric. Brutalist.

Roots treats your digital life as an ecosystem. Subscriptions drain you,
repositories grow or rot, ideas in your Obsidian vault thrive or go
dormant. Roots watches all of it through one lens — **vitality** — and
shows you what's alive, what's draining, and what's been forgotten.

This is the codebase for an academic submission at Atlas Skilltech
University, but the architecture is designed as a real local-first
prototype, not an assignment shell.

---

## Architecture

Four layers, strictly enforced.

```
┌─────────────────────────────────────────────────┐
│  ui/        JavaFX views + Swiss Bioluminescent │   Presentation
│             theme                               │
├─────────────────────────────────────────────────┤
│  service/   VaultGuardian (auth)                │   Business Logic
│             VitalityCalculator                  │
│             JoyCostAnalyzer                     │
│             CognitiveHeatmap (TF-IDF)           │
│             ObsidianBridge (WatchService)       │
├─────────────────────────────────────────────────┤
│  dao/       NodeRepository<T>                   │   Data Access
│             SubNodeDao, RepoNodeDao,            │
│             IdeaNodeDao, UserDao, AuditDao      │
├─────────────────────────────────────────────────┤
│  model/     RootNode (abstract)                 │   Domain
│             ├─ SubNode                          │
│             ├─ RepoNode                         │
│             └─ IdeaNode                         │
│             User, AuditEvent                    │
│             Vitalizable, Drainable, Auditable   │
├─────────────────────────────────────────────────┤
│  db/        DatabaseManager (SQLite)            │   Persistence
└─────────────────────────────────────────────────┘
```

## OOP Spine

The core abstraction is `RootNode`, an abstract class declaring two
methods every subclass must implement:

```java
public abstract double getVitality();   // 0..1
public abstract double getJoyScore();   // 0..1
```

Each concrete subclass overrides these with a *genuinely different*
algorithm:

| Subclass    | Vitality is...                                            | Joy is...                          |
|-------------|-----------------------------------------------------------|------------------------------------|
| `SubNode`   | recency × user-supplied joy rating, decayed over 90 days  | the joy rating itself              |
| `RepoNode`  | commit recency relative to per-repo staleness threshold   | derived from commit cadence        |
| `IdeaNode`  | edit recency × backlink centrality (60d + log saturation) | log of word count, capped at 4000  |

That is the polymorphism the rubric asks for, except it is load-bearing:
remove it and the dashboard cannot rank nodes against each other.

## Running

### Prerequisites

- JDK 17 or later
- Maven 3.8 or later

### Build & launch

```bash
git clone <this repo>
cd roots
mvn clean javafx:run
```

On first launch, Roots creates `~/.roots/roots.db` (SQLite) and seeds it
with a working ecosystem so the app is never empty.

### Default credentials

| Username | Password    | Role   |
|----------|-------------|--------|
| `draco`  | `roots2026` | ADMIN  |
| `viewer` | `viewer2026`| VIEWER |

These are deterministic seed credentials for grading. The seed file ships
with placeholder hashes that `DatabaseManager` substitutes with real
BCrypt outputs on first boot, so the committed file contains no real
credentials.

### Tests

```bash
mvn test
```

The test suite covers the metric algorithms in isolation — proving that
`getVitality()` and `getJoyScore()` return what the design says they
should under known inputs. These tests are the most direct evidence
that the OOP hierarchy works correctly under polymorphism.

## Database

SQLite, single file, foreign keys enabled per-connection. Schema lives
at `sql/schema.sql` (also bundled in the jar at
`/sql/schema.sql` for the bootstrap loader).

Seven tables:

- `users` — authentication
- `nodes` — polymorphic root, discriminator column `type`
- `sub_attrs`, `repo_attrs`, `idea_attrs` — per-subtype attribute tables
- `audit_log` — append-only mutation trail
- `vitality_snapshots` — historical readings for trend charts

The single-table-inheritance + attribute-table pattern keeps queries
on the common shape fast while letting each subtype evolve its own
columns without nullable bloat. Foreign keys cascade so deleting a node
also removes its attribute row and any audit references.

## Bonus features

- Login authentication (BCrypt, work factor 12)
- Role-based access (ADMIN sees everything, VIEWER sees their own nodes)
- Search & filter by name, type, vitality band
- Reports: Joy-to-Cost analysis, Stale Repo alert, Dormant Ideas
- **Cognitive Heatmap** — TF-IDF cosine similarity over node text,
  rendered as a force-directed graph. The clustering happens live.
- **Obsidian Bridge** — point Roots at a vault folder, it ingests
  `.md` files as IdeaNodes and updates them on save via Java's
  `WatchService`.
- Input validation centralised in `util/Validators`
- Audit log with action history view
- JUnit 5 unit tests on all metric algorithms

## Project layout

```
roots/
├── pom.xml
├── README.md
├── sql/
│   ├── schema.sql      # standalone copy for grading
│   └── seed.sql
├── docs/
│   ├── diagrams/       # class diagram, ER diagram (SVG)
│   ├── screenshots/    # UI screenshots
│   └── report.docx     # full project report
└── src/
    ├── main/
    │   ├── java/com/atlas/roots/
    │   │   ├── model/      # 10 files
    │   │   ├── dao/        # 6 files
    │   │   ├── db/         # 1 file
    │   │   ├── service/    # 5 files
    │   │   ├── bridge/     # 1 file
    │   │   ├── util/       # 2 files
    │   │   └── ui/         # JavaFX views
    │   └── resources/
    │       ├── sql/        # bootstrap copies
    │       ├── css/        # Swiss Bioluminescent theme
    │       └── fxml/       # JavaFX layouts
    └── test/
        └── java/com/atlas/roots/   # JUnit 5 tests
```

## Author

Draco Bhandare
B.Tech Data Engineering, Atlas Skilltech University
April 2026
