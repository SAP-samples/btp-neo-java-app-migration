---
name: neo-to-cf-migration-orchestrator
description: Invoke this skill to orchestrate complete Neo to Cloud Foundry migration. Analyzes the Neo app, creates a migration plan, and dispatches each migration scenario (Jakarta, SDK, auth, persistence, destinations, etc.) to a separate subagent so the orchestrator's context stays lean across the full 5–14 step pipeline. Use when user says 'migrate Neo app', 'convert to CF', or 'Neo to Cloud Foundry migration'.
disable-model-invocation: false
allowed-tools: Agent, Read, Edit, Write, Grep, Glob, Bash(curl *), Bash(python3 *), Bash(mvn *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *), Bash(find *), Bash(grep *)
---

# Neo to Cloud Foundry Migration Orchestrator

Orchestrates the complete migration of SAP BTP Neo Java applications to Cloud Foundry.

## Purpose

This skill coordinates the end-to-end migration process by:
1. Analyzing the Neo application to detect required transformations
2. Creating a migration plan with skills in the correct order
3. **Dispatching each skill to its own subagent** so the orchestrator never loads the bodies of the 5–14 child skills into its own context
4. Validating the migration at each step using cheap filesystem and `mvn` checks
5. Generating the final deployment descriptor

## Orchestration Algorithm

This skill follows the **Orchestrator-Worker pattern** with a verification gate between steps:

- **Orchestrator** (this skill, running in your main context): plans, dispatches, verifies, and recovers. Holds only the plan, status log, and short worker reports.
- **Workers** (subagents you spawn via the `Agent` tool): each handles one self-contained, idempotent unit of work in a fresh context and returns a ≤30-line structured report.
- **Gate**: after each worker returns SUCCESS, the orchestrator advances to the next step. The worker's verification output is trusted — the orchestrator does NOT re-run the same verification.

### Dispatch tool

Every subagent in this skill is spawned via the `Agent` tool. Use `subagent_type: "general-purpose"` for migration steps and `subagent_type: "Explore"` for read-only detection fan-outs.

```
Agent(
  subagent_type: "general-purpose",   // or "Explore" for read-only
  description: "<3-5 word summary>",
  prompt: <the full prompt below>
)
```

Never emulate a subagent inline — that defeats the entire context-saving purpose.

### Concurrency policy

| Phase | Mode | Cap |
|-------|------|-----|
| Phase 1 detection | **Fan-out (parallel)** | Up to 13 Explore agents in one batch (one per skill detection). All read-only — no file conflicts. |
| Phase 3 execution | **Sequential** | 1 at a time. Feature skills mutate overlapping files (`pom.xml`, `web.xml`, `mtad.yaml` precursors); parallel runs would race. Step 3.3 (`mta-descriptor`) is run **inline** by the orchestrator — see Step 3.3 for why this is the one exception to the per-skill subagent rule. |
| Phase 4 verification | Sequential | 1 subagent. |

When fanning out in Phase 1, issue all `Agent` calls in a single message so they run concurrently (barrier-sync pattern).

### Failure & retry policy

- **Max retries per step: 2.** After 2 failed subagent attempts on the same step, stop and surface to the user — do not loop indefinitely.
- **Recoverable failures** (single-line tweak): fix in `$COPY_DIR` directly with `Edit`/`Write`, then re-run verification inline. Don't re-spawn a subagent for a one-line fix.
- **Structural failures** (skill needs to re-run): re-spawn with the original prompt plus `Previous attempt failed because: <reason>. Address it and retry.`
- **Hard stop conditions**: BOM version cannot be resolved from the registry; `mvn clean package` fails on a step that previously passed; `mtad.yaml` not produced in Phase 3.3 after 2 attempts.

### orchestrator.log format

Every dispatch outcome appends exactly one line to `.migration/orchestrator.log` in this canonical format:

```
<ISO-8601-timestamp> phase=<n> skill=<name> status=<SUCCESS|FAILED|PARTIAL> attempt=<k>
```

Append-only. Never edit prior lines. Both the Resume protocol below and Step 3.4's hygiene rules read and write this single format — do not introduce variants.

### Resume protocol

The orchestrator's own context may be summarized mid-migration. Resume is driven by `.migration/orchestrator.log` and `.migration/cf-migration-config.json`:

```
1. cat .migration/orchestrator.log

2. last_success = last line whose status=SUCCESS
   resume_from  = step immediately after last_success in the plan

3. PARTIAL is treated as FAILED for resume purposes:
   if the last line has status=PARTIAL or FAILED, the resume_from is THAT step
   (re-dispatch it; child skills are idempotent so re-running is safe).

4. If no log exists → start from Phase 0.
   If log exists but plan absent → re-run Phase 1 + 2 (cheap, idempotent)
                                  to rebuild the plan, then jump to resume_from.

5. Honor the max-retry cap: count the existing FAILED+PARTIAL lines for the same
   skill in the log; if already at 2, stop and surface to user instead of re-dispatching.
```

Each child skill is required to be idempotent (re-running on already-migrated files is a no-op or detects the migrated state). This means the worst case of a duplicate dispatch is a wasted subagent call, never a corrupted workspace.

### Why subagents (motivation, kept for context)

Running each migration step inline would pull every child skill's `SKILL.md` (often hundreds of lines plus reference files) into the orchestrator's context. Across a full migration that's tens of thousands of tokens of skill bodies the orchestrator never needs to read — it only needs to know which skill to run next and whether the previous one succeeded.

| Phase | Inline or subagent? | Why |
|-------|---------------------|-----|
| Phase 0 — copy | Inline | Just `cp -r`, trivial. |
| Phase 1 — analysis | **Fan-out: 13 parallel Explore subagents** | Each does one skill's detection in parallel; orchestrator aggregates the small structured reports inline. |
| Phase 2 — planning | Inline | The orchestrator already has the aggregated detection results; it just renders the plan and asks the user. No new file reads. |
| Phase 3 — execution (feature skills, Steps 3.1–3.2) | **One general-purpose subagent per skill, sequential** | The original motivation — see the dispatch pattern below. |
| Phase 3 — execution (`mta-descriptor`, Step 3.3) | **Inline** in the orchestrator's context | One-shot, last step, and `mtad.yaml` is THE deliverable. The orchestrator already has the cross-skill rules and feature-skill list it needs to feed the descriptor; the subagent dispatch was a translation step that introduced drift. See Step 3.3. |
| Phase 4 — verification | **One general-purpose subagent** | `mvn clean package` output, recursive grep, file listings. The subagent returns pass/fail + last 10 lines on failure. |

See the per-phase dispatch prompts in **Phase 1.0**, **Phase 3** (Step 3.1–3.2 for subagent feature skills; Step 3.3 runs inline), and **Phase 4.0**.

## Artifact versions — resolve, don't invent

Never write an artifact version you remember from training data. SAP BOMs (`cf-tomcat-bom`, `sdk-modules-bom`, `cf-tomee-bom`, …) release frequently, and a number that doesn't exist in the registry breaks the BOM import with `Non-resolvable import POM`, which cascades into `'dependencies.dependency.version' is missing` for every dependency the BOM manages.

Resolve each SAP BOM with a one-line lookup against Maven Central — use this exact form, do not hand-pick a number:

```bash
latest_version () {
  curl -fsS --max-time 10 \
    "https://search.maven.org/solrsearch/select?q=g:$1+AND+a:$2&core=gav&rows=20&wt=json" \
  | jq -r '.response.docs | map(select(.v | test("^[0-9]+(\\.[0-9]+)*$")))
                          | sort_by(-.timestamp) | .[0].v'
}

latest_version com.sap.cloud.sjb.cf cf-tomcat-bom
latest_version com.sap.cloud.sdk    sdk-modules-bom
latest_version com.sap.cloud.sjb.cf cf-tomee-bom    # only if migrating to TomEE
```

Each invocation prints exactly one line: the latest *release* version. The subagent must capture that line and substitute it for the `RESOLVED_*` placeholders the child skills (`sdk-replacement`, `keystore-credstore`, `tomee-runtime`) ship in their pom snippets. If a lookup returns empty or `curl` exits non-zero — network failure, registry change — the subagent must stop and report. Do **not** ask another subagent to "just pick a recent one," do **not** fall back to a number from training data, and do **not** patch a version into the descriptor yourself.

Other identifiers the skills prescribe — buildpack names (`sap_java_buildpack_jakarta`, not `sap_java_buildpack`), service names, plan names — are fixed strings, not version numbers. Use them exactly as the child skill specifies.

## Full Subaccount Migration Order

When migrating a complete Neo subaccount (platform configuration + one or more applications), execute the phases in this order. Phases 1 and 3 run **once per subaccount**; Phase 2 runs **once per application**.

```
PHASE 0: Tooling setup — once
  Install CF CLI + BTP CLI, login to both

PHASE 1: Subaccount export — once (read-only, Neo side)
  subaccount-trust-export
  subaccount-roles-export              ← export now; import deferred to Phase 5
  (destinations are NOT exported to file — migrated directly in Phase 3 via neo-destinations-keystores-migrator)

PHASE 2: Per-app code migration — repeat for each app directory
  For each app:
    jakarta-java25-migration
    sdk-replacement
    authentication-xsuaa               ← creates xs-security.json + role-collections
    [feature skills: destinations, persistence-hana, etc.]
    mta-descriptor

PHASE 3: Platform import — once (CF side, before deploy)
  subaccount-trust-import
  (roles-import is NOT here — deferred)

PHASE 4: Deploy all apps — once per app
  mvn clean package -DskipTests
  cf deploy . -f

PHASE 5: Post-deploy — once (after ALL apps deployed)
  neo-destinations-keystores-migrator  ← requires CF apps to exist for app-level binding
  subaccount-roles-import              ← NOW: live XSUAA appIds exist
                                          assigns role-templates + users to collections
```

> **Why roles-import is last:** `btp add security/role` requires the live XSUAA `appId` (e.g. `myapp!t1234`) which is only assigned after the first `cf deploy`. Role collections are created by `authentication-xsuaa` via `xs-security.json` + deployment — `subaccount-roles-import` only links role-templates into those collections and assigns users.

### Multi-App Notes

For a subaccount with multiple applications:

- Phases 0, 1, 3, and 5 run **once** for the whole subaccount
- Phase 2 runs **once per app directory** — each app gets its own `xs-security.json`, `approuter/`, and `mtad.yaml`
- Phase 4 runs **once per app** — deploy each app separately with `cf deploy . -f` from its directory
- Phase 5 reads all apps from `.migration/neo-roles.json` and resolves each against live CF XSUAA apps in a single pass — run it only after **all** apps from Phase 4 are successfully deployed
- If one app fails to deploy, `subaccount-roles-import` will flag it and can be re-run after the issue is fixed

---

## Trigger

This skill is triggered when the user requests:
- "Migrate my Neo app to CF"
- "Convert this application to Cloud Foundry"
- "Migration from Neo to Cloud Foundry"
- "Help me migrate to CF"
- Any request involving Neo to CF migration

## Migration Workflow

```
+------------------------------------------------------------------+
|                     MIGRATION WORKFLOW                            |
+------------------------------------------------------------------+
|                                                                   |
|  PHASE 1: ANALYSIS                                                |
|  -----------------                                                |
|  Scan the application to identify which skills are needed:        |
|  - pom.xml - Neo dependencies, Java version                       |
|  - web.xml - Resource references, auth config                     |
|  - Java files - Neo API imports                                   |
|                                                                   |
|  PHASE 2: PLANNING                                                |
|  ----------------                                                 |
|  Create ordered migration plan based on detection results         |
|  Present plan to user for approval                                |
|                                                                   |
|  PHASE 3: EXECUTION                                               |
|  -----------------                                                |
|  Apply skills in dependency order:                                |
|  1. Foundation skills (always required)                           |
|  2. Feature skills (based on detection)                           |
|  3. Deployment skill (always last)                                |
|                                                                   |
|  PHASE 4: VERIFICATION                                            |
|  ---------------------                                            |
|  - Compile the application                                        |
|  - Verify no Neo imports remain                                   |
|  - Validate mtad.yaml structure                                   |
|                                                                   |
+------------------------------------------------------------------+
```

## Skill Dependency Order

Skills MUST be applied in this order:

```
FOUNDATION (Always Required)
|
+-> 1. jakarta-java25-migration
|      Migrate to Java 25 and Jakarta EE 10
|
+-> 2. sdk-replacement
       Replace Neo Java Web API with SAP Cloud SDK
|
+-> 3. dependency-compatibility (if third-party libs detected)
       Resolve library compatibility for Java 25 / Jakarta / HANA Cloud

FEATURES (Based on Detection)
|
+-> 4. approuter-setup (if web-facing app detected)
|      Set up SAP Application Router
|
+-> 5. authentication-xsuaa (if auth detected)
|      Set up XSUAA security configuration
|
+-> 6. persistence-hana (if DataSource detected)
|      Configure HANA Cloud database
|
+-> 7. destinations (if ConnectivityConfiguration detected)
|      Configure Destination service
|
+-> 8. connectivity-onpremise (if on-premise proxy detected)
|      Enable Cloud Connector connectivity
|
+-> 9. mail-destinations (if mail session detected)
|      Configure mail via destinations
|
+-> 10. document-management-sdm (if EcmService detected)
|      Migrate to Document Management Service
|
+-> 11. keystore-credstore (if KeyStoreService detected)
|      Migrate to Credential Store
|
+-> 12. tomee-runtime (if EJB detected - ALTERNATIVE to Tomcat)
|       Configure TomEE container
|
+-> 13. monitoring-logging (optional)
        Set up Cloud Logging

DEPLOYMENT (Always Last)
|
+-> 14. mta-descriptor
        Generate mtad.yaml deployment descriptor
```

## Phase 0: Create Migration Copy

Before analyzing or modifying anything, create a sibling copy of the application directory. All migration work — by this orchestrator and every skill it invokes — is done on the copy.

```bash
APP_DIR=$(pwd)
APP_NAME=$(basename "$APP_DIR")
COPY_DIR="$(dirname "$APP_DIR")/${APP_NAME}-cf-migration"

if [ -d "$COPY_DIR" ]; then
  echo "Migration copy already exists at $COPY_DIR — using it."
else
  cp -r "$APP_DIR" "$COPY_DIR"
  echo "Created migration copy at $COPY_DIR"
fi
cd "$COPY_DIR"
```

Now that we are inside the copy, create `.migration/` and save the config there:

```bash
mkdir -p .migration
```

Save the paths to `.migration/cf-migration-config.json` (create or update):

```json
{
  "sourceAppDir": "<original APP_DIR>",
  "migrationAppDir": "<COPY_DIR>"
}
```

> All subsequent steps and all invoked skills must operate inside `$COPY_DIR`. The original `$APP_DIR` is never modified. The `.migration/` directory is inside the copy, not the original.

## Phase 1: Analysis

### Step 1.0: Fan Out Detection to Parallel Explore Subagents

Phase 1 runs ~13 detection sweeps over every `pom.xml`, `web.xml`, and Java source tree in the project. The sweeps are **independent and read-only** — perfect for fan-out. Each sweep goes to its own `Explore` subagent, all dispatched in a single message so they run concurrently (barrier-sync pattern). The orchestrator then aggregates the 13 short reports inline (cheap, decision-shaped).

#### Step 1.0a: First, discover the project layout once (inline)

The fan-out workers need to know where `pom.xml`, `web.xml`, and Java sources live. Do this inline — it's three quick `find` calls, not worth a subagent:

```bash
find . -name "pom.xml" -type f -not -path "*/target/*"
find . -name "web.xml" -path "*/WEB-INF/*" -type f
find . -path "*/src/main/java" -type d
```

Note the layout: flat vs. multi-module, and which submodules contain Neo code. Pass this layout summary into every fan-out worker's prompt so they don't each re-discover it.

#### Step 1.0b: Dispatch 13 parallel Explore subagents

In a **single message**, issue 13 `Agent` tool calls (one per skill detection). All must use `subagent_type: "Explore"` and `description` of the form `"detect <skill>"`. The shared prompt template:

```
You are detecting whether the <SKILL_NAME> migration skill is needed for a Neo→CF migration.

Working directory (read-only, operate ONLY here): <COPY_DIR>
Project layout (already discovered): <flat | multi-module + submodule list>

Run EXACTLY these detection commands and report what they find:

<COPY THE COMMANDS FROM Step 1.2 FOR <SKILL_NAME> HERE>

Return ONLY this single-line JSON object:
{"skill": "<SKILL_NAME>", "required": true|false, "evidence": "<file:line or 'empty'>", "extras": {<see below>}}

Decision rule: required=true if ANY command returned ANY output. required=false only
if ALL commands returned empty. Matches in `neo/` submodules count as evidence.

The "extras" field carries skill-specific evidence the orchestrator needs to evaluate
the Step 1.3 cross-skill rules. Include only the keys relevant to this skill:

- jakarta-java25-migration: {"hasApachePOI": true|false, "currentJavaVersion": "<n>"}
- authentication-xsuaa: {"hasSAML": true|false, "hasBASIC": true|false,
                         "neoAuthFilters": ["<filter1>", ...],
                         "duplicateServletMappings": true|false}
- mta-descriptor: {"buildpack": "sap_java_buildpack_jakarta"|"sap_java_buildpack"|null}
- all other skills: {} (empty object)

Hard limit: 1 line of JSON. No commentary. No file contents. No diffs.
Your final message IS the return value.
```

Spawn one such Agent call for each of these 13 skills (the names match Step 1.2 headers exactly):
`jakarta-java25-migration`, `sdk-replacement`, `dependency-compatibility`, `approuter-setup`, `authentication-xsuaa`, `persistence-hana`, `destinations`, `connectivity-onpremise`, `mail-destinations`, `document-management-sdm`, `keystore-credstore`, `tomee-runtime`, `monitoring-logging`.

> `monitoring-logging` is dispatched only so the orchestrator's fan-out is uniform; its Step 1.2 body is a no-op (the rule is "OPT-IN, do not auto-detect" — see the catalog entry). The subagent returns empty, and Step 1.4 leaves the row at `[ ]`. Skip this skill in Phase 3 unless the user explicitly asked for Cloud Logging or OTEL.

Concrete `Agent` tool invocation for one skill:

```
Agent(
  subagent_type: "Explore",
  description: "detect jakarta-java25-migration",
  prompt: <the template above with <SKILL_NAME> = jakarta-java25-migration
           and the matching Step 1.2 commands inlined>
)
```

Repeat for the other 12 skills in the same message.

#### Step 1.0c: Aggregate inline + apply cross-skill rules

After the 13 workers return, aggregate inline:

1. Collect the 13 JSON lines into a single map `{skill → {required, evidence}}`.
2. Apply the Step 1.3 cross-skill rules table against the map and the project layout.
3. Render the Step 1.4 ASCII detection summary, marking `[x]` for `required=true` and `[ ]` otherwise.

This aggregation runs in the orchestrator's context, but only ~13 lines of JSON enter — not the raw grep output. The summary feeds Phase 2 planning directly.

#### Failure inside the fan-out

If an `Explore` agent fails (`null` return, non-JSON output, or worker error), re-spawn **just that one** with the same prompt — do not re-run the whole fan-out. Cap re-spawns at 2 per skill (consistent with the global max-retry policy). If a skill is still failing after 2 retries, mark its detection as `required=true, evidence="detection failed — assume needed"` and record the degradation in a separate `.migration/detection-warnings.log` file (plain text, one line per failed skill). **Do not write detection failures to `.migration/orchestrator.log`** — that log is reserved for Phase-3 execution outcomes and is the source of truth for the Resume protocol. Continue with the remaining skills — over-detection is safe (the corresponding migration step is idempotent on already-migrated code), under-detection is not.

### Step 1.1: Locate Project Files

The project layout (pom.xml, web.xml, Java source roots) was discovered inline in **Step 1.0a**. Reuse those results — do not re-run the `find` calls here.

### Step 1.2: Detection Command Catalog (worker bodies)

> **Who runs this:** the per-skill `Explore` subagents dispatched in **Step 1.0b** copy the relevant block from this section into their prompt and execute it. The orchestrator does **not** run any of these commands itself — that's the whole point of the fan-out. This section is the *catalog* the workers draw from.

Each subsection below specifies the detection commands for one skill plus the rule for marking it required.

> **CRITICAL — Detection Rules (these are baked into the worker prompt template in Step 1.0b):**
> 1. If a detection command returns **ANY output at all**, the skill is **REQUIRED** — mark it `[x]`
> 2. If **ALL** detection commands for a skill return **empty output**, the skill is **not needed** — mark it `[ ]`
> 3. **Multi-module projects:** Many Neo apps have submodules (e.g., `neo/`, `cf/`, `common/`). A match in ANY submodule counts as detected. Do NOT discount matches found in `neo/` subdirectories — those are the Neo patterns that need migration.
> 4. **Pre-existing CF code:** Some projects may already have partial CF implementations alongside Neo code. This does NOT suppress detection. If the Neo-side pattern exists, the skill must run to validate and complete the migration.

> **Layout note:** all detection commands below use `find`- or project-root-relative (`./`) scans to handle both flat and multi-module layouts. The layout itself was already discovered in Step 1.0a — workers receive it as part of their prompt and do not re-discover.

#### Check: jakarta-java25-migration (ALWAYS REQUIRED)
```bash
# Check Java version in pom.xml (check all pom.xml files for multi-module)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l -E "<(source|target|maven.compiler.source|maven.compiler.target)>" {} \;

# Check for javax.* imports (recursive from project root)
grep -r "import javax\." --include="*.java" . | head -10
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: sdk-replacement (ALWAYS REQUIRED)
```bash
# Check for Neo dependencies (check all pom.xml files for multi-module)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l -E "neo-java-web-api|scp-neo" {} \;
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: approuter-setup
```bash
# Check for webapp directory (web-facing app)
find . -path "*/webapp/*" -type f | head -5

# Check for HTML files
find . -name "*.html" -path "*/webapp/*" | head -5

# Check for web.xml (servlet-based app)
find . -name "web.xml" -path "*/WEB-INF/*" | head -5

# Check for authentication (approuter always needed if auth is present)
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l -E "<auth-method>|<security-constraint>" {} \;
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: authentication-xsuaa
```bash
# Check all web.xml files for auth config
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l -E "<auth-method>|<security-constraint>|<login-config>" {} \;

# Check for UserProvider imports (recursive from project root)
grep -r "com.sap.security.um.user" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: persistence-hana
```bash
# Check all web.xml files for DataSource
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l -E "javax.sql.DataSource|jakarta.sql.DataSource" {} \;

# Check for @Resource DataSource (recursive from project root)
grep -r "@Resource.*DataSource" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: destinations
```bash
# Check all web.xml files for ConnectivityConfiguration
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l -E "ConnectivityConfiguration|DestinationConfiguration" {} \;

# Check for connectivity API imports (recursive from project root)
grep -r "com.sap.core.connectivity.api" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED. Matches in `neo/` submodules count.

#### Check: connectivity-onpremise
```bash
# Check for on-premise proxy usage (recursive from project root)
grep -r "HC_OP_HTTP_PROXY" --include="*.java" .
grep -r "ProxyType.*OnPremise" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: mail-destinations
```bash
# Check all web.xml files for mail session
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l -E "javax.mail.Session|jakarta.mail.Session" {} \;

# Check for @Resource mail (recursive from project root)
grep -r "@Resource.*mail/Session" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: document-management-sdm
```bash
# Check all web.xml files for EcmService
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l "com.sap.ecm.api.EcmService" {} \;

# Check for ECM API imports (recursive from project root)
grep -r "com.sap.ecm.api" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED. Matches in `neo/` submodules count.

#### Check: keystore-credstore
```bash
# Check all web.xml files for KeyStoreService or PasswordStorage
find . -name "web.xml" -path "*/WEB-INF/*" -exec grep -l -E "KeyStoreService|PasswordStorage" {} \;

# Check for keystore/password imports (recursive from project root)
grep -r "com.sap.cloud.crypto.keystore\|com.sap.cloud.security.password" --include="*.java" .
```
**Detection:** If ANY command above returns output -> REQUIRED

#### Check: tomee-runtime
```bash
# Check for EJB annotations (recursive from project root)
grep -r "@Stateless\|@Singleton\|@EJB\|javax.ejb\|jakarta.ejb" --include="*.java" .

# Check for neo-javaee7-wp-api (check all pom.xml files for multi-module)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "neo-javaee7-wp-api" {} \;
```
**Detection:** If ANY command above returns output -> REQUIRED (use TomEE instead of Tomcat)

#### Check: dependency-compatibility
```bash
# Check for third-party libraries that may have Java 25 / Jakarta / HANA Cloud issues
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l -E "liquibase|flyway|guice|weld|dagger|poi-ooxml|itext|ehcache|hazelcast|quartz|retrofit|log4j" {} \;

# Check for DI framework usage
grep -r "com.google.inject\|GuiceServletContextListener\|javax.enterprise.context\|jakarta.enterprise.context" --include="*.java" . | head -5

# Check for schema migration tools + DI (complex interaction)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l -E "liquibase|flyway" {} \;
```
**Detection:** If ANY command above returns output -> REQUIRED. This skill resolves library-specific compatibility issues that fall outside the core jakarta-java25-migration.

#### Check: monitoring-logging (OPT-IN — DO NOT AUTO-DETECT)
```bash
# This skill is OPT-IN ONLY. The worker MUST return empty output here.
# Do not grep for slf4j / logback / log4j / OTEL / OpenTelemetry — those keywords
# are present in essentially every Java app and a naive match would flag every
# scenario as needing this skill, which provisions a `cloud-logging standard`
# managed service. In SAP BTP that plan is quota-limited per space and every
# additional unrequested instance fails the deploy with:
#   "Service broker error: Service broker cloud-logging failed with: Quota is not sufficient for this request"
# The buildpack already routes stdout/stderr to CF's built-in log aggregator,
# so apps without an explicit Cloud Logging requirement deploy fine without
# this skill.
:   # no-op — intentionally produces no output
```
**Detection:** This command produces NO output by design. Mark this skill `[ ]` (not selected). The plan rendering step (Step 1.4) leaves it unchecked. Only flip it to `[x]` if the user **explicitly** asks for Cloud Logging, OpenTelemetry tracing, or centralized observability — and even then, prefer asking the user to confirm before provisioning, since the service has space-level quota implications.

### Step 1.3: Cross-Skill Technology Combination Rules

After detection, check for these common technology combinations that require coordinated handling across skills. Apply the "Then Also Ensure" action during Phase 3 execution:

| If Detected | Then Also Ensure |
|-------------|-----------------|
| Apache POI + Java 25 | `jakarta-java25-migration` Step 10 includes JAXB test-scope dependencies (`jaxb-api` + `jaxb-impl`) |
| `sap_java_buildpack_jakarta` | `mta-descriptor` uses `SAPMachineJRE` (not `SAPMachineJDK`) in `JBP_CONFIG_COMPONENTS` and `JBP_CONFIG_SAP_MACHINE_JRE` |
| SAML + BASIC auth in Neo web.xml | Default to **standard** approuter (NOT extended) — XSUAA handles auth natively |
| Neo auth filters in web.xml (SAMLAuthFilter, BASICAuthFilter, CERTAuthFilter) | `authentication-xsuaa` Step 3 removes all Neo auth filters and their mappings |
| Multiple servlet mappings for same servlet (e.g., `/s/api/*`, `/b/api/*`, `/c/api/*`) | `authentication-xsuaa` Step 4 consolidates to a single mapping |

> **Why this matters:** Several migration issues fall between skills — e.g., JAXB + Java 25 + POI forms a dependency chain that no single skill fully covers. These rules ensure nothing falls through the cracks.

### Step 1.4: Create Detection Summary

After scanning, create a summary. Mark `[x]` for every skill whose detection commands returned ANY output. Mark `[ ]` ONLY if ALL detection commands for that skill returned empty output.

```
+---------------------------------------------------------+
|              MIGRATION ANALYSIS RESULTS                  |
+---------------------------------------------------------+
| Project: [project-name]                                  |
| Project Layout: [flat | multi-module]                    |
| Current Java Version: [version]                          |
| Neo Dependencies Found: [yes/no]                         |
+---------------------------------------------------------+
| REQUIRED SKILLS:       (* = always required)             |
| [x] jakarta-java25-migration *                           |
| [x] sdk-replacement *                                    |
| [?] dependency-compatibility (check: third-party libs?)  |
| [?] approuter-setup (check: webapp/ or web.xml found?)   |
| [?] authentication-xsuaa (check: auth-method found?)     |
| [?] persistence-hana (check: DataSource found?)          |
| [?] destinations (check: ConnectivityConfig found?)      |
| [?] connectivity-onpremise (check: HC_OP_HTTP found?)    |
| [?] mail-destinations (check: mail.Session found?)       |
| [?] document-management-sdm (check: EcmService found?)  |
| [?] keystore-credstore (check: KeyStoreService found?)   |
| [?] tomee-runtime (check: EJB annotations found?)        |
| [ ] monitoring-logging (OPT-IN — see note below)         |
| [x] mta-descriptor *                                     |
+---------------------------------------------------------+
| Replace [?] with [x] if detection returned output,       |
| or [ ] if detection returned nothing.                    |
|                                                          |
| monitoring-logging is the ONE exception: it stays [ ]    |
| even if the model thinks logging keywords (slf4j,        |
| logback, OTEL) appeared. Flip to [x] ONLY when the user  |
| has explicitly asked for Cloud Logging / OpenTelemetry.  |
+---------------------------------------------------------+
```

## Phase 2: Planning

### Step 2.1: Create Migration Plan

Based on detection results, create an ordered plan:

```
+---------------------------------------------------------+
|                   MIGRATION PLAN                         |
+---------------------------------------------------------+
|                                                          |
| Step 1: jakarta-java25-migration                         |
|         Migrate to Java 25 and Jakarta EE 10             |
|                                                          |
| Step 2: sdk-replacement                                  |
|         Replace Neo SDK with SAP Cloud SDK               |
|                                                          |
| Step 3: authentication-xsuaa                             |
|         Set up XSUAA authentication                      |
|                                                          |
| Step 4: persistence-hana                                 |
|         Configure HANA Cloud database                    |
|                                                          |
| Step 5: mta-descriptor                                   |
|         Generate deployment descriptor                   |
|                                                          |
+---------------------------------------------------------+
```

### Step 2.2: User Approval

Present the plan to the user and ask for approval before proceeding.

**Important:** Wait for user confirmation before executing the migration.

## Phase 3: Execution

### CRITICAL: Dispatch Each Feature Skill to a Subagent — EXCEPT `mta-descriptor`

The migration plan covers 5–14 skills, each with hundreds of lines of instructions and reference material. If the orchestrator invokes them all inline (loading each `SKILL.md` into its own context), it exhausts the context window before the migration is half done.

**To stay context-efficient, dispatch each feature skill (Steps 3.1–3.2) to a subagent via the `Agent` tool.** Each subagent gets a fresh context, reads only the one skill it needs, executes it against `$COPY_DIR`, and returns a short summary. The orchestrator keeps only the summary — not the skill body — and uses the saved `.migration/cf-migration-config.json` plus filesystem checks to track progress.

**Step 3.3 (`mta-descriptor`) is the one exception** — it runs INLINE in the orchestrator's context. `mtad.yaml` is the final deliverable, it must be byte-faithful to the cross-skill rules and the feature-skill list the orchestrator already holds, and we've seen subagent dispatch introduce drift here that breaks deploys. See Step 3.3 below.

#### Subagent Dispatch Pattern

For every skill in the plan, invoke the `Agent` tool with `subagent_type: "general-purpose"`:

```
Agent(
  subagent_type: "general-purpose",
  description: "apply <skill-name>",
  prompt: <the prompt below>
)
```

Prompt template:

```
You are migrating a SAP BTP Neo Java application to Cloud Foundry.

Working directory (operate ONLY here): <COPY_DIR>
Skill to apply: <skill-name>
Detected context: <relevant detection findings, e.g., "Apache POI present", "SAML+BASIC auth in web.xml">
Cross-skill rules to honor: <any rows from the Step 1.3 table that apply>

Your task:
1. cd <COPY_DIR>
2. Invoke the <skill-name> skill and follow its instructions exactly.
3. After completion, run the verification command(s) specified for this step:
   <e.g., mvn test-compile, ls -la mtad.yaml>
4. Return a concise report (≤ 30 lines) with:
   - Files created/modified (paths only, no diffs)
   - Verification command output (pass/fail + last 10 lines if failed)
   - Any blockers or follow-ups the orchestrator should know about
   - Status: SUCCESS | FAILED | PARTIAL

Do NOT return file contents, full diffs, or skill body. The orchestrator already knows the migration plan and only needs your status.
```

> **Why summaries, not diffs:** the orchestrator can re-read any file from `$COPY_DIR` itself if it needs to. The subagent's job is to *do the work* and *report status* — not to ship the work product back through tokens.

#### Failure Handling

If a subagent reports `FAILED` or `PARTIAL`:

1. Read the subagent's blocker description and the verification output it returned.
2. If the failure is recoverable (e.g., specific file needs a tweak), make the fix in `$COPY_DIR` directly with `Edit`/`Write` — don't re-spawn the subagent for a one-line fix.
3. If the failure is structural (skill needs to re-run), spawn a new subagent with the same prompt plus a `Previous attempt failed because: <reason>. Address it and retry.` line.
4. Do NOT proceed to the next skill until the current step is `SUCCESS`. Subsequent skills assume the previous one's invariants hold (e.g., `sdk-replacement` assumes Jakarta migration is done).

### Step 3.1: Dispatch Foundation Skills

**Always run in this order — each is a separate subagent invocation:**

| # | Skill | Required? | Verification |
|---|-------|-----------|--------------|
| 1 | `jakarta-java25-migration` | Always | `mvn test-compile` (both main + test must compile) |
| 2 | `sdk-replacement` | Always | `mvn clean compile` |
| 3 | `dependency-compatibility` | Only if detected in Step 1.2 | `mvn clean compile` |

> **Why `mvn test-compile` for #1:** test code with hand-written servlet mocks is a common failure point after the Jakarta migration. The skill's Step 9 handles it, but the orchestrator must verify both main and test compile cleanly before moving on.

The subagent's prompt includes the verification command — its returned report contains the verification output. **Trust the worker's report.** Do NOT re-run the same `mvn` build inline after a SUCCESS; that's a token-burning duplicate. The orchestrator only re-verifies if the report status is FAILED/PARTIAL or the verification output it contains is ambiguous.

After SUCCESS, append one line to `.migration/orchestrator.log` and update `.migration/cf-migration-config.json` with `"foundation.<skill>": "done"`.

### Step 3.2: Dispatch Feature Skills

For each feature skill marked `[x]` in Step 1.4, dispatch a subagent in the order shown in the plan. Run them **sequentially**, not in parallel — feature skills can touch overlapping files (`pom.xml`, `web.xml`, `mtad.yaml` precursors) and parallel runs would race.

| If Detected | Subagent Skill | Post-Run Verification |
|-------------|----------------|----------------------|
| Approuter (web-facing) | `approuter-setup` | `ls approuter/package.json approuter/xs-app.json` |
| Authentication | `authentication-xsuaa` | `ls xs-security.json` + `mvn test-compile` |
| Persistence | `persistence-hana` | `mvn test-compile` |
| Destinations | `destinations` | `mvn test-compile` |
| On-Premise Connectivity | `connectivity-onpremise` | `mvn test-compile` |
| Mail | `mail-destinations` | `mvn test-compile` |
| Document Management | `document-management-sdm` | `mvn test-compile` |
| Keystore | `keystore-credstore` | `mvn test-compile` |
| TomEE/EJB | `tomee-runtime` | `mvn test-compile` |
| Monitoring | `monitoring-logging` | (no compile check needed) |

When dispatching, **inject the relevant cross-skill rules from Step 1.3** into the subagent prompt's "Cross-skill rules to honor" line. For example, if Apache POI is present, the `jakarta-java25-migration` subagent's prompt must mention it; if SAML+BASIC auth is in `web.xml`, the `authentication-xsuaa` subagent's prompt must call out the standard-vs-extended approuter decision.

### Step 3.3: Generate the Deployment Descriptor (run INLINE — not a subagent)

**MANDATORY — always last, always run, never skipped.**

> **This step is the single exception to the "every skill goes to a subagent" rule.** The orchestrator runs `mta-descriptor` **directly in its own context**, not via the `Agent` tool. Three reasons:
>
> 1. **`mtad.yaml` is THE deliverable** of the entire migration. The orchestrator already knows which feature skills ran in Step 3.2 and the cross-skill rules from Step 1.3 — that context is needed verbatim by the descriptor generator. A subagent has to be told all of it through a prompt that's hard to keep faithful, and we've observed subagents improvising the descriptor when the prompt is too brief.
> 2. **The skill body is needed for fidelity.** `mta-descriptor`'s rules (WAR filename must match `pom.xml`'s `<artifactId>`; SAPMachineJRE pin syntax; per-service `service:` / `service-plan:` values; credstore-service is always declared as `org.cloudfoundry.existing-service`) are precise and a subagent that doesn't load the SKILL.md will get them wrong.
> 3. **Single occurrence in the pipeline.** Unlike feature skills (which can run for any of 8 different scenarios), `mta-descriptor` is loaded exactly once per migration. The context cost is bounded and worth paying directly.

**How to run it inline:**

```
1. Read the mta-descriptor SKILL.md fully:
     Read ai-migration/neo-java-migration-skills/skills/mta-descriptor/SKILL.md
   (Or use the path the host provides for skill assets.)

2. Compile the inputs from prior phases:
   - The plan's "feature skills that ran in Step 3.2" → determines which
     services must be in the resources: block
   - The cross-skill rules table from Step 1.3 (e.g.
     `sap_java_buildpack_jakarta` → SAPMachineJRE pin syntax)
   - The migrated `pom.xml`'s `<artifactId>` → goes into the module's
     `path: target/<artifactId>.war`
   - The keystore-credstore rule (if that skill ran): credstore is
     org.cloudfoundry.existing-service named credstore-service

3. Follow the mta-descriptor skill's instructions step by step IN THIS
   context. Use Write/Edit to produce $COPY_DIR/mtad.yaml.

4. Verify inline:
   - Check the file exists: `ls -la $COPY_DIR/mtad.yaml`
   - Confirm the module's path: line matches what Maven actually writes —
     run `grep "Building war:" $COPY_DIR/build.log` and check that the
     filename in mtad.yaml's path: matches.
   - Confirm every service the feature skills emitted has a corresponding
     resource and a matching requires: entry in the module.
```

After the file is on disk, append a SUCCESS line to `.migration/orchestrator.log` (`phase=3 skill=mta-descriptor status=SUCCESS attempt=1`) and update `.migration/cf-migration-config.json` with `"deployment.mta-descriptor": "done"`.

**If mtad.yaml is missing or malformed after the inline run**, retry inline (re-read the skill, regenerate). Do not fall back to a subagent — the whole point of running this inline is that the orchestrator's context is the highest-fidelity place for this step. Max 2 retries (same cap as the global retry policy); after that, stop and surface the specific problem to the user with the file's current contents.

> **CRITICAL:** The migration is incomplete without `mtad.yaml`. If this file is missing, the application cannot be deployed to Cloud Foundry. Never skip this step or consider the migration done without it.

### Step 3.4: Context Hygiene Between Subagents

Between subagent dispatches, the orchestrator should:

1. **Not** re-read files the subagent touched unless its returned verification output indicates failure — trust the worker's report. Re-running the same `mvn` build inline after a SUCCESS is wasted tokens (see the "Concrete recommendations" / no-double-verification rule in the Orchestration Algorithm section).
2. Append one line to `.migration/orchestrator.log` after every dispatch outcome, using the **canonical orchestrator.log format** defined at the top of this skill (Orchestration Algorithm → orchestrator.log format). Append-only — never edit prior lines. The Resume protocol reads this log to determine where to pick up after context summarization.
3. Never load another skill's `SKILL.md` directly — that's the subagent's job. The orchestrator only knows the *plan*; the subagents know the *how*.
4. Update `.migration/cf-migration-config.json` after each SUCCESS with `"phase3.<skill>": "done"` so the resume protocol has a structured snapshot in addition to the log.

## Phase 4: Verification

### Step 4.0: Dispatch Verification to a Subagent

Final verification runs `mvn clean package` (long output), a recursive grep for stray Neo imports, and a structural file check. None of that output needs to live in the orchestrator unless something fails.

Invoke the `Agent` tool with `subagent_type: "general-purpose"`:

```
Agent(
  subagent_type: "general-purpose",
  description: "verify migration",
  prompt: <the prompt below>
)
```

Prompt:

```
You are running Phase 4 (Verification) of the Neo→CF migration orchestrator skill.

Working directory (operate ONLY here): <COPY_DIR>

Execute Steps 4.1, 4.2, 4.3, 4.4 of this skill exactly.

Return ONLY a structured report:

## Compilation (Step 4.1)
- result: PASS | FAIL
- if FAIL: last 30 lines of `mvn clean package` output

## Residual Neo imports (Step 4.2)
- result: CLEAN | DIRTY
- if DIRTY: each offending file:line + the import string

## Project structure (Step 4.3)
- mtad.yaml present: yes/no
- xs-security.json present: yes/no (only if auth was migrated)
- approuter/package.json + xs-app.json present: yes/no (only if auth was migrated)
- context.xml + resource_configuration.yml present: yes/no (only if persistence was migrated)

## Deployment readiness (Step 4.4)
- mtad.yaml first 50 lines: <paste>
- overall: READY | NOT READY — one-line reason if not ready

Hard limit: ≤ 120 lines unless mvn output must be cited on failure.
Your final message IS the return value — no preamble.
```

The orchestrator reads this report and either marks the migration `done` in `.migration/cf-migration-config.json` or dispatches a fix subagent for whatever broke.

### Step 4.1: Final Compilation Check

```bash
mvn clean package
```

### Step 4.2: Verify No Neo Imports Remain

```bash
# Should return no results (recursive from project root to handle multi-module)
grep -r "com.sap.cloud.account\|com.sap.core.connectivity\|com.sap.security.um\|com.sap.cloud.crypto" --include="*.java" .
```

### Step 4.3: Verify Project Structure

Ensure required files exist. **The migration MUST NOT be considered complete unless `mtad.yaml` exists:**

```bash
# MANDATORY check — fail if mtad.yaml is missing
if [ ! -f mtad.yaml ] && [ ! -f mta.yaml ]; then
  echo "ERROR: mtad.yaml is missing — migration is incomplete!"
  echo "Re-invoke: Use the mta-descriptor skill to generate deployment descriptors"
  exit 1
fi
```

```
project/
+-- pom.xml                          # Updated with CF dependencies
+-- mtad.yaml                        # NEW: MTA deployment descriptor
+-- xs-security.json                 # NEW: If authentication used
+-- approuter/                       # NEW: If authentication used
|   +-- package.json
|   +-- xs-app.json
+-- src/main/webapp/
    +-- META-INF/
    |   +-- context.xml              # NEW: If persistence used
    |   +-- sap_java_buildpack/
    |       +-- config/
    |           +-- resource_configuration.yml  # NEW: If persistence used
    +-- WEB-INF/
        +-- web.xml                  # Updated auth-method
```

### Step 4.4: Deployment Readiness Check

```bash
# Verify mtad.yaml syntax
cat mtad.yaml

# Check if all required files exist
ls -la mtad.yaml xs-security.json approuter/ 2>/dev/null
```

### Step 4.5: Lint `mtad.yaml` for HANA resource-level parameter pollution

A `type: com.sap.xs.hana-schema` resource has NO supported parameters at
the resource level — the reference scenarios use only `name` + `type`
(two lines). Anything else gets either rejected outright or silently
dropped:

- `service: hana` / `service-plan: hdi-shared` (the classic managed-service
  keys on the wrong type) → deploy hard-fails with:
  > `CF-UnprocessableEntity(10008): Invalid service plan. ... Ensure that
  > the service plan is visible in your current space ...`

- `schema-name:` → deploy continues (warning only) but the parameter is
  silently dropped, producing this noise in the deploy log:
  > `Parameter(s) "{<name>-hana=[schema-name]}" are not supported in the
  > specified scope, or referenced by any other entities. These
  > parameters are not processed and will be lost after the operation
  > completes.`

Subagents dispatched for `persistence-hana` or `tomee-runtime`, and the
orchestrator's own inline Step 3.3 (`mta-descriptor`), are the most common
offenders. The orchestrator must catch all three before declaring the
migration done.

```bash
# Flag any com.sap.xs.hana-schema block that has parameter-level keys
# (none are supported at the resource level — the deployer drops them).
python3 - <<'PY'
import re, sys, pathlib
p = pathlib.Path('mtad.yaml') if pathlib.Path('mtad.yaml').exists() else pathlib.Path('mta.yaml')
if not p.exists():
    sys.exit(0)
text = p.read_text()
# split into resource blocks at `- name:` boundaries
blocks = re.split(r'(?m)^(?=\s*-\s+name:)', text)
bad = []
for b in blocks:
    if 'com.sap.xs.hana-schema' not in b:
        continue
    for ln in b.splitlines():
        s = ln.strip()
        # The two classic anti-patterns (managed-service keys on hana-schema):
        if s.startswith('service:') and 'hana' in s:
            bad.append(ln.rstrip())
        elif s.startswith('service-plan:') and 'hdi-shared' in s:
            bad.append(ln.rstrip())
        # schema-name on the resource is also unsupported — MTA emits
        # `Parameter(s) "{<name>-hana=[schema-name]}" are not supported in
        # the specified scope ... will be lost after the operation completes`
        elif s.startswith('schema-name:'):
            bad.append(ln.rstrip())
if bad:
    print('HANA hana-schema resource has forbidden keys — strip these:')
    for ln in bad:
        print('  ', ln)
    sys.exit(1)
print('HANA resource: clean (no service:/service-plan:/schema-name: at resource level)')
PY
```

If the script exits non-zero, dispatch a fix subagent with prompt:

> In `mtad.yaml`, every resource with `type: com.sap.xs.hana-schema` must
> have NO `parameters:` block and NO `properties:` block at all. The
> reference scenarios use only `name` + `type` (two lines). Strip
> everything else inside the resource entry — `service: hana`,
> `service-plan: hdi-shared`, `schema-name:`, `hdi-service-name:`, any
> other keys. MTA's `com.sap.xs.hana-schema` resource type doesn't
> support resource-level parameters; the deployer drops them with the
> "not supported in the specified scope" warning. Re-run the lint and
> confirm clean.

## Output

At the end of migration, provide:

1. **Summary of changes made**
2. **List of files created/modified**
3. **Next steps for deployment:**

```bash
# Build the application
mvn clean package -DskipTests

# Login to Cloud Foundry (if not already)
cf login --sso

# Deploy
cf deploy . -f
```

4. **Reminder for subaccount-level steps** (if not already completed):
   - If subaccount platform migration has not been run: invoke `subaccount-migration-orchestrator` (trust + roles export) before deploying
   - After all apps are deployed: run `neo-destinations-keystores-migrator` to migrate destinations and keystores, then run `subaccount-roles-import` to assign role-templates and users to the role collections created by `authentication-xsuaa`
   - See the **Full Subaccount Migration Order** section above for the complete multi-app sequence

## Common Issues

### Issue: Compilation fails after jakarta migration
**Solution:** Check for libraries that haven't migrated to Jakarta (e.g., OpenCMIS). Add exclusions and use compatible versions.

### Issue: Multiple skills modify pom.xml incorrectly
**Solution:** Execute skills in order. Each skill should ADD to existing configuration, not replace.

### Issue: mtad.yaml missing required services
**Solution:** Re-run mta-descriptor skill after all feature skills are complete.

### Issue: Deploy fails immediately with `Error building MTA Archive: file path … not found`
**Symptom:** Step 3.3's deploy exits non-zero within seconds, before any service is touched:

> `FAILED`
> `Error retrieving MTA: Error building MTA Archive:`
> `file path scenarios/<scenario>/cf-ai-migrated/target/<X>.war not found`

The earlier `mvn clean package -DskipTests` step succeeded; its
`[INFO] Building war:` line shows a different filename than the
descriptor's `path:`.

**Cause:** `mtad.yaml`'s `path:` doesn't match what Maven writes. Either
the descriptor literally contains `target/${project.artifactId}.war`
(Maven property expressions are NOT evaluated by `cf deploy`), or it
hard-codes `target/ROOT.war` while the migrated `pom.xml` is configured
with `<warName>${project.artifactId}</warName>` (the current default in
this skill's pom templates).

**Fix:** Dispatch a fix subagent with:

> Read the `<artifactId>` value from `pom.xml`. Edit `mtad.yaml` so the
> Java backend module's `path:` is `target/<artifactId>.war` with the
> artifactId substituted literally — NOT `${project.artifactId}` and
> NOT `ROOT`. For example, if `pom.xml`'s artifactId is `connectivity`,
> write `path: target/connectivity.war`. Do not modify `pom.xml`. After
> the edit, re-run `cf deploy`.

This is the descriptor-follows-pom convention used throughout the
skills. Side effect: the app serves at `/<artifactId>` rather than `/`,
so integration tests and approuter destinations must include the
`/<artifactId>` URL prefix.

## Skill Reference

| Skill | Purpose | Detection Pattern |
|-------|---------|-------------------|
| jakarta-java25-migration | Java 25 + Jakarta EE 10 | javax.* imports, Java < 25 |
| sdk-replacement | SAP Cloud SDK | neo-java-web-api dependency |
| dependency-compatibility | Third-party lib fixes | Liquibase, Guice, POI, Flyway, etc. |
| approuter-setup | Application Router | webapp/ dir, web.xml, HTML content |
| authentication-xsuaa | XSUAA auth | FORM auth, security-constraint |
| persistence-hana | HANA database | DataSource resource-ref |
| destinations | Destination service | ConnectivityConfiguration |
| connectivity-onpremise | Cloud Connector | HC_OP_HTTP_PROXY_* |
| mail-destinations | Mail via destinations | mail.Session resource-ref |
| document-management-sdm | Document Management | EcmService resource-ref |
| keystore-credstore | Credential Store | KeyStoreService resource-ref |
| tomee-runtime | TomEE container | EJB annotations |
| monitoring-logging | Cloud Logging | Optional |
| mta-descriptor | Deployment descriptor | Always required |
| neo-destinations-keystores-migrator | Transfer subaccount-level and app-level destination configs and keystores from Neo to CF (platform data migration, not source code) | Subaccount-level, run in Phase 5 |
