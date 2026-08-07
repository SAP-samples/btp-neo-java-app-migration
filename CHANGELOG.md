# Changelog

All notable changes to the **sap-btp-neo-migration** plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-08-05

### Added
- **Orchestration algorithms** in the migration orchestrator for smarter end-to-end app migration sequencing.
- **Java 25 deep-reflection handling** as a dedicated step in the `jakarta-java25-migration` skill.
- **OpenRewrite side-effect cleanup** for `pom.xml` after migration, ensuring the build stays correct post-transformation.
- **Runtime dependency detection and troubleshooting** for `DESTINATION`-related runtime dependencies.
- **Inline JSON/HTTP library detection** before dependency generation, so generated dependencies match what the app actually uses.
- **Repository deletion via the SDM REST API** in the Document Management Service skill.
- **Credential Store free-plan support** in the keystore/credential-store skill.
- **Subagent usage guidance and skill improvements** across the migration skills.

### Changed
- Migration scenarios now include explicit paths for **keystore** and **storing-passwords** flows.
- Standardised the WAR path placeholder to `<artifactId>.war` across all skills for consistency.
- Removed non-existent flags from the `cf deploy` documentation.

### Fixed
- Corrected ATSS findings in the `subaccount-trust-import` skill.
- Fixed `btp delete api-credential` syntax in `subaccount-trust-import`.
- Fixed approuter detection and an invalid `xs-app.json` target.
- Fixed the `mta-deploy` primary flow: replaced the broken `mbt build` step with `cf deploy`.
- Added `TokenClaims` mapping and removed dangling imports in `authentication-xsuaa`.
- Corrected `SAPMachineJDK` → `SAPMachineJRE` in the `authentication-xsuaa` skill.
- Resolved a memory-size contradiction in the `mta-descriptor` templates.
- Removed the `-k` (insecure) flag from all `curl` calls in `neo-destinations-keystores-migrator`.
- Fixed BTP CLI syntax errors in the `subaccount-roles-import` skill.

## [1.0.1] - 2026-06-19

### Added
- **Repository deletion via the SDM REST API** in the Document Management Service skill.

## [1.0.0] - 2026-05-08

### Added
- Initial release of the **sap-btp-neo-migration** plugin: a set of skills for migrating SAP BTP applications and subaccount configuration from the Neo environment to Cloud Foundry.
- **Orchestrators** for end-to-end app migration and complete subaccount configuration migration.
- Application migration skills: Jakarta EE, XSUAA authentication and Application Router, destinations, on-premise connectivity, HANA persistence, Document Management (SDM), credential store / keystores, TomEE runtime, and MTA deployment descriptor generation.
- Subaccount configuration export and import skills for trust (IdP/SAML), destinations, and roles.
- **neo-destinations-keystores-migrator** for direct in-memory migration of destinations and keystores from Neo to CF.
