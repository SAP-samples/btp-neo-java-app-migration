---
name: neo-to-cf-migration-orchestrator
description: Invoke this skill to orchestrate complete Neo to Cloud Foundry migration. Analyzes the Neo app, creates a migration plan, and INVOKES other skills in correct order. Use when user says 'migrate Neo app', 'convert to CF', or 'Neo to Cloud Foundry migration'.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(curl *), Bash(python3 *), Bash(mvn *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *), Bash(find *), Bash(grep *)
---

# Neo to Cloud Foundry Migration Orchestrator

Orchestrates the complete migration of SAP BTP Neo Java applications to Cloud Foundry.

## Purpose

This skill coordinates the end-to-end migration process by:
1. Analyzing the Neo application to detect required transformations
2. Creating a migration plan with skills in the correct order
3. Executing each skill sequentially
4. Validating the migration at each step
5. Generating the final deployment descriptor

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

### CRITICAL: How to Invoke Skills

For each skill in the migration plan, you MUST **INVOKE** it by name using the skill system.

**DO NOT** manually perform the steps described in each skill. Instead, **CALL** the skill to ensure proper execution.

**Invocation Pattern:**
```
Use the [skill-name] skill
```

### Step 3.1: Invoke Foundation Skills

**ALWAYS invoke these first, in order:**

1. **INVOKE** the `jakarta-java25-migration` skill:
   ```
   Use the jakarta-java25-migration skill to migrate to Java 25 and Jakarta EE 10
   ```
   - Wait for skill completion
   - Verify both main and test code compile: `mvn test-compile`
   - If test compilation fails due to missing abstract methods in servlet mocks, the skill's Step 9 will handle it

2. **INVOKE** the `sdk-replacement` skill:
   ```
   Use the sdk-replacement skill to replace Neo SDK with SAP Cloud SDK
   ```
   - Wait for skill completion
   - Verify: `mvn clean compile`

3. **INVOKE** the `dependency-compatibility` skill (if detected):
   ```
   Use the dependency-compatibility skill to resolve third-party library compatibility issues
   ```
   - Wait for skill completion
   - Verify: `mvn clean compile`

### Step 3.2: Invoke Feature Skills

For each detected feature skill, **INVOKE** it in the order listed in the plan:

| If Detected | INVOKE Command |
|-------------|----------------|
| Approuter (web-facing) | `Use the approuter-setup skill` |
| Authentication | `Use the authentication-xsuaa skill` |
| Persistence | `Use the persistence-hana skill` |
| Destinations | `Use the destinations skill` |
| On-Premise Connectivity | `Use the connectivity-onpremise skill` |
| Mail | `Use the mail-destinations skill` |
| Document Management | `Use the document-management-sdm skill` |
| Keystore | `Use the keystore-credstore skill` |
| TomEE/EJB | `Use the tomee-runtime skill` |
| Monitoring | `Use the monitoring-logging skill` |

After each skill invocation, verify compilation:
```bash
mvn test-compile
```

> **Note:** Use `mvn test-compile` (not just `mvn compile`) to ensure both main and test code compile successfully. Test code with hand-written servlet mocks is a common failure point after the Jakarta migration.

### Step 3.3: Invoke Deployment Skill

**ALWAYS invoke last — this step is MANDATORY and must not be skipped:**

**INVOKE** the `mta-descriptor` skill:
```
Use the mta-descriptor skill to generate deployment descriptors
```

This will:
- Create mtad.yaml based on detected services
- Create xs-security.json if authentication is used
- Create approuter files if authentication is used

**After the skill completes, verify the file exists:**
```bash
if [ ! -f mtad.yaml ] && [ ! -f mta.yaml ]; then
  echo "CRITICAL ERROR: mtad.yaml was not created. Re-invoking mta-descriptor skill."
  # Re-invoke the skill
fi
ls -la mtad.yaml
cat mtad.yaml
```

> **CRITICAL:** The migration is incomplete without `mtad.yaml`. If this file is missing, the application cannot be deployed to Cloud Foundry. Never skip this step or consider the migration done without it.

## Phase 4: Verification

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
