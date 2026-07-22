---
name: neo-to-cf-migration-orchestrator
description: Invoke this skill to orchestrate complete Neo to Cloud Foundry migration. Analyzes the Neo app, creates a migration plan, and dispatches each migration scenario (Jakarta, SDK, auth, persistence, destinations, etc.) to a separate subagent so the orchestrator's context stays lean across the full 5–14 step pipeline. Use when user says 'migrate Neo app', 'convert to CF', or 'Neo to Cloud Foundry migration'.
disable-model-invocation: false
allowed-tools: Task, Agent, Read, Edit, Write, Grep, Glob, Bash(curl *), Bash(python3 *), Bash(mvn *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *), Bash(find *), Bash(grep *)
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

### Why subagents

Running each migration step inline would pull every child skill's `SKILL.md` (often hundreds of lines plus reference files) into the orchestrator's context. Across a full migration that's tens of thousands of tokens of skill bodies the orchestrator never needs to read — it only needs to know which skill to run next and whether the previous one succeeded. Each subagent handles one skill in a fresh context and returns a short status report; the orchestrator stays focused on planning, sequencing, verification, and recovery.

The same logic extends to every read- or build-heavy phase:

| Phase | Inline or subagent? | Why |
|-------|---------------------|-----|
| Phase 0 — copy | Inline | Just `cp -r`, trivial. |
| Phase 1 — analysis | **Subagent** | Runs ~13 detection sweeps across the whole repo; raw command output never needs to live in the orchestrator. The subagent returns the filled-in detection summary table only. |
| Phase 2 — planning | Inline | The orchestrator already has the detection summary; it just renders the plan and asks the user. No new file reads. |
| Phase 3 — execution | **One subagent per skill** | The original motivation — see the dispatch pattern below. |
| Phase 4 — verification | **Subagent** | `mvn clean package` output, recursive grep, file listings. The subagent returns pass/fail + last 10 lines on failure. |

Inline phases stay short and decision-shaped. Subagent phases stay self-contained: read whatever they need, return a small structured report.

See the per-phase dispatch prompts in **Phase 1.0**, **Phase 3** (Step 3.1–3.3), and **Phase 4.0**.

## Artifact versions — resolve, don't invent

Never write an artifact version you remember from training data. SAP BOMs (`cf-tomcat-bom`, `sdk-modules-bom`, `cf-tomee-bom`, …) release frequently, and a number that doesn't exist in the registry breaks the BOM import with `Non-resolvable import POM`, which cascades into `'dependencies.dependency.version' is missing` for every dependency the BOM manages.

When a child skill needs a version (most commonly `sdk-replacement` Step 3), the skill prescribes a lookup against the SAP Artifactory with Maven Central as a fallback. The subagent must run that lookup and write back the literal string the registry returns. If the lookup fails, the subagent must stop and report — do **not** ask another subagent to "just pick a recent one," and do not patch a version into the descriptor yourself.

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

### Step 1.0: Dispatch Analysis to a Subagent

Phase 1 runs ~13 detection sweeps over every `pom.xml`, `web.xml`, and Java source tree in the project. Streaming all that grep/find output through the orchestrator is exactly the context cost subagents are designed to avoid.

Spawn a `general-purpose` subagent with this prompt:

```
You are running Phase 1 (Analysis) of the Neo→CF migration orchestrator skill.

Working directory (operate ONLY here): <COPY_DIR>

Read this skill's Phase 1 (Steps 1.1, 1.2, 1.3, 1.4) and execute it exactly.
Run every detection command listed under Step 1.2 — do not skip any.
Apply the cross-skill rules from Step 1.3.

Return ONLY a structured Markdown report with these sections:

## Project layout
- flat | multi-module
- pom.xml / web.xml / src/main/java locations
- Current Java version (cite pom.xml:line)

## Detection results
For EACH skill in Step 1.2: `<skill>`: REQUIRED | NOT NEEDED — evidence (file:line or "empty").
Cover all of: jakarta-java25-migration, sdk-replacement, dependency-compatibility,
approuter-setup, authentication-xsuaa, persistence-hana, destinations,
connectivity-onpremise, mail-destinations, document-management-sdm,
keystore-credstore, tomee-runtime, monitoring-logging.

## Cross-skill rules triggered (Step 1.3)
One bullet per row that fires, with evidence.

## Detection summary table
Reproduce the Step 1.4 ASCII table with [x]/[ ] filled in.

Hard limit: ≤ 250 lines. Cite as `path:line`. Do not paste file contents.
Your final message IS the return value — no preamble.
```

The orchestrator keeps only this report. The raw grep/find output never enters its context. The subagent's report is what feeds Phase 2 planning.

### Step 1.1: Locate Project Files

First, identify the project structure:

```bash
# Find pom.xml (Maven project root)
find . -name "pom.xml" -type f | head -5

# Find web.xml
find . -name "web.xml" -type f | head -5

# Find Java source files
find . -name "*.java" -type f | head -20
```

### Step 1.2: Detect Required Skills

Scan the application for detection patterns. For each skill, run the detection commands and check if its patterns are present.

> **CRITICAL — Detection Rules:**
> 1. If a detection command returns **ANY output at all**, the skill is **REQUIRED** — mark it `[x]`
> 2. If **ALL** detection commands for a skill return **empty output**, the skill is **not needed** — mark it `[ ]`
> 3. **Multi-module projects:** Many Neo apps have submodules (e.g., `neo/`, `cf/`, `common/`). A match in ANY submodule counts as detected. Do NOT discount matches found in `neo/` subdirectories — those are the Neo patterns that need migration.
> 4. **Pre-existing CF code:** Some projects may already have partial CF implementations alongside Neo code. This does NOT suppress detection. If the Neo-side pattern exists, the skill must run to validate and complete the migration.

#### Prerequisite: Discover Project Layout

Multi-module projects may have web.xml and Java sources in non-standard paths. Run these first to discover the layout:

```bash
# Find all web.xml files (may be in submodules)
find . -name "web.xml" -path "*/WEB-INF/*" -type f

# Find all Java source roots
find . -path "*/src/main/java" -type d

# Find all pom.xml files (multi-module check)
find . -name "pom.xml" -type f -not -path "*/target/*"
```

Store the discovered paths — all subsequent detection commands use `find`-based or project-root-relative (`./`) scans to handle both flat and multi-module layouts.

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
| [ ] monitoring-logging (optional)                        |
| [x] mta-descriptor *                                     |
+---------------------------------------------------------+
| Replace [?] with [x] if detection returned output,       |
| or [ ] if detection returned nothing.                    |
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

### CRITICAL: Dispatch Each Skill to a Subagent

The migration plan covers 5–14 skills, each with hundreds of lines of instructions and reference material. If the orchestrator invokes them inline (loading each `SKILL.md` into its own context), it exhausts the context window before the migration is half done.

**To stay context-efficient, dispatch each skill to a subagent via the `Agent` tool.** Each subagent gets a fresh context, reads only the one skill it needs, executes it against `$COPY_DIR`, and returns a short summary. The orchestrator keeps only the summary — not the skill body — and uses the saved `.migration/cf-migration-config.json` plus filesystem checks to track progress.

#### Subagent Dispatch Pattern

For every skill in the plan, spawn a `general-purpose` subagent with a prompt of this shape:

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

After each subagent returns SUCCESS, the orchestrator runs the verification command itself once more (cheap insurance) and updates `.migration/cf-migration-config.json` with `"foundation.<skill>": "done"`.

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

### Step 3.3: Dispatch the Deployment Skill

**MANDATORY — always last, always run, never skipped.**

Dispatch a subagent for `mta-descriptor`:

```
... (standard subagent prompt) ...
Skill to apply: mta-descriptor
Detected context: <list every feature skill that ran in Step 3.2 — the descriptor must reference all their services>
Verification: ls -la mtad.yaml && cat mtad.yaml | head -50
```

After the subagent returns, the orchestrator verifies the file landed:

```bash
if [ ! -f mtad.yaml ] && [ ! -f mta.yaml ]; then
  echo "CRITICAL ERROR: mtad.yaml was not created. Re-dispatching mta-descriptor subagent."
  # spawn a new subagent with: "Previous attempt failed: mtad.yaml not created. Generate it now."
fi
ls -la mtad.yaml
cat mtad.yaml
```

> **CRITICAL:** The migration is incomplete without `mtad.yaml`. If this file is missing, the application cannot be deployed to Cloud Foundry. Never skip this step or consider the migration done without it.

### Step 3.4: Context Hygiene Between Subagents

Between subagent dispatches, the orchestrator should:

1. **Not** re-read files the subagent touched unless verification failed — trust the subagent's summary plus the cheap verification command.
2. Keep its own running log to `.migration/orchestrator.log` (one line per skill: `<timestamp> <skill> <status>`) so it can recover if its own context is summarized mid-migration.
3. Never load another skill's `SKILL.md` directly — that's the subagent's job. The orchestrator only knows the *plan*; the subagents know the *how*.

## Phase 4: Verification

### Step 4.0: Dispatch Verification to a Subagent

Final verification runs `mvn clean package` (long output), a recursive grep for stray Neo imports, and a structural file check. None of that output needs to live in the orchestrator unless something fails.

Spawn a `general-purpose` subagent with this prompt:

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
