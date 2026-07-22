# Orchestrator Architecture

How the `neo-to-cf-migration-orchestrator` skill runs a migration end-to-end:
who executes what, where parallelism lives, and what state crosses phase
boundaries. The authoritative behavioural spec lives in `SKILL.md`; this file
is the visual companion — read it first to orient, then follow the section
pointers in SKILL.md for the prompts and exact commands.

## At-a-glance

| Phase                              | Executor                              | Concurrency                |
|------------------------------------|---------------------------------------|----------------------------|
| **0 · Copy**                       | **Main agent** (inline)               | sequential, trivial        |
| **1 · Analysis**                   | **Explore subagents**                 | **PARALLEL, up to 13**     |
| **2 · Planning**                   | **Main agent** (inline)               | sequential                 |
| **3 · Execution**                  | (see sub-steps below)                 | sequential across sub-steps |
| &nbsp;&nbsp;3.1 · Foundation skills | **General-purpose subagents**        | sequential, 1 at a time    |
| &nbsp;&nbsp;3.2 · Feature skills   | **General-purpose subagents**         | sequential, 1 at a time    |
| &nbsp;&nbsp;3.3 · mta-descriptor   | **Main agent** (inline)               | sequential                 |
| **4 · Verification**               | **General-purpose subagent** + inline | one subagent + inline lint |

## Diagram

```
╔════════════════════════════════════════════════════════════════════════╗
║                       MAIN AGENT (orchestrator)                        ║
║                                                                        ║
║  Loaded skill body: neo-to-cf-migration-orchestrator/SKILL.md only     ║
║  Holds in context:  plan + short worker reports                        ║
║  Does NOT load:     child skill bodies                                 ║
║                     (except mta-descriptor in 3.3 — see below)         ║
║  Writes:            .migration/orchestrator.log (append-only)          ║
║                     .migration/cf-migration-config.json (plan + state) ║
║                     $COPY_DIR/ (the migrated app)                      ║
╚════════════════════════════════════════════════════════════════════════╝
                  │
                  │ runs 5 phases in order
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│ PHASE 0 · Copy                                            ▶  inline   │
│ cp -r <neo>/ → <neo>-cf-migration/                                     │
└────────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│ PHASE 1 · Analysis                              ▶  ╔══════════════╗   │
│ Fan out detection to read-only Explore agents.    ║  PARALLEL    ║    │
│ All 13 dispatched in ONE message → barrier sync.  ║  up to 13 at ║    │
│                                                   ║   one time   ║    │
│   ┌──────────────┐ ┌──────────────┐               ╚══════════════╝    │
│   │ Explore      │ │ Explore      │  ... (13 total, one per skill)    │
│   │ detect       │ │ detect       │                                   │
│   │ jakarta-     │ │ sdk-         │  jakarta-java25-migration         │
│   │ java25       │ │ replacement  │  sdk-replacement                  │
│   └──────┬───────┘ └──────┬───────┘  dependency-compatibility         │
│          │                │          authentication-xsuaa             │
│   ┌──────────────┐ ┌──────────────┐  approuter-setup                  │
│   │ Explore      │ │ Explore      │  persistence-hana                 │
│   │ detect       │ │ detect       │  destinations                     │
│   │ persistence- │ │ keystore-    │  connectivity-onpremise           │
│   │ hana         │ │ credstore    │  mail-destinations                │
│   └──────┬───────┘ └──────┬───────┘  document-management-sdm          │
│          │                │          keystore-credstore               │
│          │     ...        │          tomee-runtime                    │
│          │                │          monitoring-logging               │
│          ▼                ▼          mta-descriptor (always = needed) │
│   ┌──────────────────────────────┐                                    │
│   │ Each subagent returns        │                                    │
│   │ ≤ 30-line JSON detection     │                                    │
│   │ report. Main agent aggregates│                                    │
│   │ inline (cheap, decision-     │                                    │
│   │ shaped).                     │                                    │
│   └──────────────────────────────┘                                    │
└────────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│ PHASE 2 · Planning                                       ▶  inline    │
│ Main agent renders the migration plan from detections.                 │
│ Optionally asks the user to confirm.                                   │
│ Writes .migration/cf-migration-config.json.                            │
└────────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│ PHASE 3 · Execution                ▶  SEQUENTIAL — 1 worker at a time │
│                                       (overlapping file writes        │
│                                       prevent parallelism here)       │
│                                                                       │
│  Step 3.1 · Foundation skills (always)                                │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │ general-purpose subagent → jakarta-java25-migration          │    │
│  │ verifies: mvn test-compile                                   │    │
│  └────────────────┬─────────────────────────────────────────────┘    │
│  ┌────────────────▼─────────────────────────────────────────────┐    │
│  │ general-purpose subagent → sdk-replacement                   │    │
│  │ verifies: mvn clean compile                                  │    │
│  └────────────────┬─────────────────────────────────────────────┘    │
│  ┌────────────────▼─────────────────────────────────────────────┐    │
│  │ general-purpose subagent → dependency-compatibility (if any) │    │
│  └────────────────┬─────────────────────────────────────────────┘    │
│                   │                                                  │
│  Step 3.2 · Feature skills (only ones marked [x] in the plan)        │
│  ┌────────────────▼─────────────────────────────────────────────┐    │
│  │ approuter-setup            (if web-facing)                   │    │
│  │ authentication-xsuaa       (if auth detected)                │    │
│  │ persistence-hana           (if DataSource JNDI)              │    │
│  │ destinations               (if outbound HTTP)                │    │
│  │ connectivity-onpremise     (if Cloud Connector signals)      │    │
│  │ mail-destinations          (if javax.mail)                   │    │
│  │ document-management-sdm    (if EcmService)                   │    │
│  │ keystore-credstore         (if KeyStoreService/PasswordStor) │    │
│  │ tomee-runtime              (if @Stateless/EJB)               │    │
│  │ monitoring-logging         (if logging present)              │    │
│  │   one subagent each, sequential, ≤30-line summary each       │    │
│  └────────────────┬─────────────────────────────────────────────┘    │
│                   │                                                  │
│  Step 3.3 · mta-descriptor                                           │
│  ┌────────────────▼─────────────────────────────────────────────┐    │
│  │  ⚠  RUNS INLINE IN MAIN AGENT — NOT a subagent.              │    │
│  │                                                              │    │
│  │  Main agent reads mta-descriptor/SKILL.md directly,          │    │
│  │  compiles the inputs (feature skills that ran, cross-skill   │    │
│  │  rules, pom.xml's <artifactId>), and writes mtad.yaml itself.│    │
│  │                                                              │    │
│  │  Why inline: `mtad.yaml` is THE deliverable, and subagent    │    │
│  │  dispatch was introducing fidelity drift (wrong service      │    │
│  │  types, WAR name mismatches, etc.).                          │    │
│  └──────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│ PHASE 4 · Verification                          ▶  one subagent +     │
│                                                    inline lints       │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │ general-purpose subagent                                     │    │
│  │   · mvn clean package -DskipTests                            │    │
│  │   · recursive grep for residual Neo imports                  │    │
│  │   · ls -la mtad.yaml && cat | head -50                       │    │
│  │   · returns pass/fail + last 10 log lines on failure         │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  Main agent also runs Step 4.5 inline:                                │
│   · lint mtad.yaml for HANA resource-level parameter pollution        │
│     (service: hana / service-plan: hdi-shared / schema-name:)         │
└────────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
              ┌─────────┐
              │  DONE   │  output: $COPY_DIR with mtad.yaml + report
              └─────────┘
```

## The two subagent types

The orchestrator dispatches via the `Agent` tool with two different
`subagent_type` values:

- **`Explore`** — used only in Phase 1. Read-only: no `Edit`/`Write` tools.
  Cheaper, faster, safer for fan-out. Each returns a small structured
  detection report.
- **`general-purpose`** — used in Phases 3.1, 3.2, and 4. Full read/write
  access. Heavier, runs sequentially in Phase 3 to avoid races on shared
  files (`pom.xml`, `web.xml`).

## Where the parallelism is — and isn't

- **Phase 1 is the only parallel phase.** The 13 Explore agents all run
  read-only, all dispatched in one message, all return short reports. This
  is the big context-saving win — without fan-out, the orchestrator would
  either load 13 skill bodies sequentially or do 13 sequential `Read`/`grep`
  passes itself.
- **Phase 3 is deliberately sequential.** Sub-sections below.
- **Phase 3.3 sits in the main agent's own context.** That's the one
  architectural exception to "every skill goes to a subagent" — subagent
  dispatch was producing malformed `mtad.yaml` (wrong service types, WAR
  filename drift, dangling property references). See SKILL.md → Phase 3 →
  Step 3.3 for the rationale.

### Why Phase 3.1 stays sequential

The three foundation skills aren't just touching overlapping files — they
have a **causal dependency chain**:

```
jakarta-java25-migration  →  sdk-replacement  →  dependency-compatibility
```

- `sdk-replacement` operates on the *Jakarta-namespace* `pom.xml` and the
  `jakarta.*` imports that `jakarta-java25-migration` produced. If they run
  in parallel, `sdk-replacement` sees the pre-Jakarta state and rewrites
  it incorrectly.
- `dependency-compatibility` fixes third-party library breakage *introduced
  by* the Jakarta upgrade (Liquibase 3→4, Mockito 1→5, Guice for
  `jakarta.inject`, etc.). It depends on knowing which dependencies survived
  `sdk-replacement`.

Parallel here doesn't just race on file bytes — it produces a deterministically
broken pom. Keep sequential.

### Why Phase 3.2 stays sequential

Looking at what each feature skill actually writes (one row per skill,
showing the files it Edits or Writes during a typical run):

| Skill                     | `web.xml` | `pom.xml` | `mtad.yaml` | Other |
|---------------------------|:---------:|:---------:|:-----------:|---|
| approuter-setup           | ✓ | — | ✓ | `xs-security.json`, `approuter/` |
| authentication-xsuaa      | ✓ | ✓ | ✓ | `xs-security.json`, `approuter/` |
| persistence-hana          | ✓ | — | ✓ | `context.xml`, `persistence.xml` |
| destinations              | ✓ | — | ✓ | — |
| connectivity-onpremise    | ✓ | — | ✓ | — |
| mail-destinations         | ✓ | ✓ | — | — |
| document-management-sdm   | ✓ | ✓ | ✓ | — |
| keystore-credstore        | ✓ | ✓ | ✓ | — |
| tomee-runtime             | ✓ | ✓ | — | `context.xml`, `resources.xml`, `persistence.xml` |
| monitoring-logging        | — | ✓ | ✓ | `logback.xml` |

Three shared mutation targets:

- **`web.xml`** — touched by 9 of 10 feature skills. Each removes one Neo
  `<resource-ref>` block (or adds a servlet mapping). Bytes are small;
  conflicts are huge.
- **`pom.xml`** — touched by 6 of 10. Each adds a dependency block.
- **`mtad.yaml`** (or its fragment precursor) — touched by 8 of 10. Each
  contributes a service `requires:` plus a matching `resources:` entry,
  which mta-descriptor reconciles in 3.3.

The hazard isn't byte-level conflict — Edit/Write does atomic file
replacement. The hazard is the **read-modify-write race** at the skill
boundary: subagent A reads `web.xml` at state S, plans its edit; subagent B
reads `web.xml` at the same state S (before A has written), plans its edit;
whichever writes second silently clobbers the other's change. A single subagent
returning SUCCESS doesn't mean its edit survived.

`xs-security.json` is doubly contested between `approuter-setup` and
`authentication-xsuaa`. `persistence.xml` is contested between
`persistence-hana` and `tomee-runtime`. Even **pairwise** parallelism isn't
safe across most combinations.

**No safe parallel subset exists today** — every feature skill touches at
least `web.xml` *or* `mtad.yaml` precursors. The path to real parallelism
would require refactoring the contested files to a **fragment-merging**
architecture: each skill writes its own `web.xml.fragment-<skill>.yaml`,
`pom.xml.deps-<skill>.xml`, `mtad.yaml.frag-<skill>.yaml`, and a separate
merge step at the end of Phase 3 (probably folded into 3.3) reconciles
them. That's a several-hours change touching the orchestrator plus every
feature skill. Not worth it for a 10-skill pipeline where wall-clock is
dominated by the model's reasoning and Maven, not file IO.

> **The parallelism we already get is at a different scale.** The CI
> matrix runs all 8 scenarios (`keystore-api`, `storing-passwords`,
> `connectivity`, …) **in parallel as separate jobs**, each one internally
> sequential. That's a better-shaped parallelism for this workload than
> fan-out within a single scenario would be.

## State across phases

Two files track progress so the migration can resume mid-flight if the
orchestrator's own context gets summarized:

- **`.migration/orchestrator.log`** — append-only, one line per dispatch:
  ```
  <ISO timestamp> phase=<n> skill=<name> status=<SUCCESS|FAILED|PARTIAL> attempt=<k>
  ```
- **`.migration/cf-migration-config.json`** — the plan plus per-step "done"
  markers. Used by the Resume protocol in SKILL.md when the orchestrator
  needs to pick up where it left off.

## Pointers into SKILL.md

If you're reading the architecture and want the exact dispatch shapes, prompts,
or verification commands:

- Phase 1 fan-out prompt template → SKILL.md § Phase 1 § Step 1.0
- Phase 3.1 / 3.2 dispatch pattern → SKILL.md § Phase 3 § "Subagent Dispatch Pattern"
- Phase 3.3 inline procedure → SKILL.md § Phase 3 § Step 3.3
- Phase 4 verification subagent → SKILL.md § Phase 4
- Step 4.5 HANA lint script → SKILL.md § Phase 4 § Step 4.5
- Resume protocol → SKILL.md § "Resume protocol"
- Concurrency/failure policy → SKILL.md § Orchestration Algorithm
