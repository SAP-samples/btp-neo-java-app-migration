# SAP BTP Neo to Cloud Foundry Migration Skills

A collection of AI agent skills for migrating SAP BTP Neo Java applications and subaccount configuration to Cloud Foundry, following the [Agent Skills](https://agentskills.io) open standard.

> **Disclaimer:** The quality and accuracy of the migration results depend on the complexity of your application and the AI tool you use. Always review the generated output, test thoroughly, and expect to make manual adjustments — especially for complex authentication flows, custom configurations, and subaccount-level settings.
>
> **Important:** It is the sole responsibility of the customer to select an appropriate AI/LLM model and provider that meets their organizational, regulatory, and contractual requirements. Customers must ensure compliance with all applicable data protection regulations (including GDPR), industry-specific standards, and internal company policies before transmitting any data — including source code, configuration files, or personal data — to an AI service. SAP does not control and is not responsible for the data processing practices of third-party AI providers. Customers should evaluate whether their chosen model and provider offer adequate safeguards for data residency, confidentiality, and lawful processing.

---

## Setup for the most common AI tools

### Claude Code

Claude Code has full native support for the plugin format used by this project.

#### Install

Register the remote marketplace and install the plugin:

```bash
claude plugin marketplace add https://github.com/SAP-samples/btp-neo-java-app-migration.git
claude plugin install sap-btp-neo-migration@sap-btp-neo-migration-tools
```

Then start Claude Code from your Neo app directory — the plugin loads automatically:

```bash
cd /path/to/your-neo-app
claude
```

#### Verify

```
/skills
```

You should see every skill shipped by the `sap-btp-neo-migration` plugin listed.

#### Update

```bash
claude plugin update sap-btp-neo-migration@sap-btp-neo-migration-tools
```

---

### GitHub Copilot (VS Code)

VS Code natively recognizes the Agent Skills `SKILL.md` format. Copy the skill directories from `ai-migration/neo-java-migration-skills/skills/` into `.github/skills/` in your project. Copilot will discover the skills automatically and invoke them based on the `description` in each skill's frontmatter. You can also invoke them explicitly by name in Copilot Chat (Agent mode):

```
Use the neo-to-cf-migration-orchestrator skill to migrate my Neo application
```

For personal (user-level) skills, copy to `~/.copilot/skills/` instead.

#### Limitations

- Automatic skill chaining (dependency orchestration) is not built into Copilot — the orchestrator skill will guide you through the steps, but Copilot will not invoke dependent skills automatically
- The `allowed-tools` frontmatter field is ignored by Copilot

---

### Cursor

Cursor natively supports the Agent Skills `SKILL.md` format. Copy the skill directories from `ai-migration/neo-java-migration-skills/skills/` into `.cursor/skills/` in your project. Cursor discovers them automatically at startup and invokes them based on the `description` in each skill's frontmatter — no explicit mention required.

For personal (user-level) skills, copy to `~/.cursor/skills/` instead.

#### Use

Skills are invoked automatically when Cursor's agent determines they are relevant. You can also invoke them explicitly with a slash command:

```
/authentication-xsuaa Set up XSUAA authentication for my application
```

```
/neo-to-cf-migration-orchestrator Analyze and migrate this Neo project
```

#### Commit skills to your project

To share the skills with your team, commit the `.cursor/skills/` directory:

```bash
git add .cursor/skills/
git commit -m "Add Neo to CF migration skills for Cursor"
```

#### Limitations

- Automatic skill chaining (dependency orchestration) is not built into Cursor — the orchestrator skill will guide you through the steps, but Cursor will not invoke dependent skills automatically
- The `allowed-tools` frontmatter field is ignored by Cursor

---

## Quick Start

### Full Migration (Recommended)

Use the orchestrator for guided end-to-end migration:

```
Use the neo-to-cf-migration-orchestrator skill to analyze and migrate my Neo Java application
```

The orchestrator will:
1. 🔍 Analyze your Neo project structure
2. 🎯 Detect required CF services
3. 📊 Determine skill dependencies
4. ⚙️ Execute skills in correct order
5. 📦 Generate MTA deployment descriptors
6. ✅ Validate each step

### Individual Skills

Apply specific skills as needed:

```
Use the authentication-xsuaa skill to set up XSUAA authentication
```

```
Use the persistence-hana skill to configure HANA Cloud database
```

```
Use the jakarta-java25-migration skill to upgrade to Java 25
```

## Skills Catalog

### 🎭 Orchestrator

- **[neo-to-cf-migration-orchestrator](skills/neo-to-cf-migration-orchestrator/SKILL.md)** - Complete end-to-end migration orchestration

### 🏗️ Foundation Skills (Required)

- **[jakarta-java25-migration](skills/jakarta-java25-migration/SKILL.md)** - Migrate to Java 25 & Jakarta EE 10
- **[sdk-replacement](skills/sdk-replacement/SKILL.md)** - Replace Neo SDK with SAP Cloud SDK

### 🔐 Security Skills

- **[authentication-xsuaa](skills/authentication-xsuaa/SKILL.md)** - XSUAA authentication & Application Router
- **[keystore-credstore](skills/keystore-credstore/SKILL.md)** - Credential Store for secrets

### 🔌 Connectivity Skills

- **[destinations](skills/destinations/SKILL.md)** - Destination service for external connections
- **[connectivity-onpremise](skills/connectivity-onpremise/SKILL.md)** - On-premise connectivity via Cloud Connector

### 📦 Subaccount Migration Skills

- **[neo-destinations-keystores-migrator](skills/neo-destinations-keystores-migrator/SKILL.md)** - End-to-end migration of destinations & keystores from Neo to CF

### 💾 Data Skills

- **[persistence-hana](skills/persistence-hana/SKILL.md)** - HANA Cloud database binding
- **[document-management-sdm](skills/document-management-sdm/SKILL.md)** - SAP Document Management Service

### 📧 Communication Skills

- **[mail-destinations](skills/mail-destinations/SKILL.md)** - Mail session configuration

### 📊 Operations Skills

- **[monitoring-logging](skills/monitoring-logging/SKILL.md)** - SAP Cloud Logging & metrics

### ⚙️ Runtime Skills

- **[tomee-runtime](skills/tomee-runtime/SKILL.md)** - TomEE container for EJB applications

## Plugin Structure

```
.claude-plugin/
├── marketplace.json            # Marketplace manifest
├── plugin.json                 # Plugin manifest

ai-migration/
├── neo-java-migration-skills/
    ├── skills/                 # All migration skills
    │   ├── approuter-setup/
    │   ├── authentication-xsuaa/
    │   ├── btp-cli-reference/
    │   ├── cf-cli-reference/
    │   ├── connectivity-onpremise/
    │   ├── dependency-compatibility/
    │   ├── destinations/
    │   ├── document-management-sdm/
    │   ├── jakarta-java25-migration/
    │   ├── keystore-credstore/
    │   ├── mail-destinations/
    │   ├── monitoring-logging/
    │   ├── mta-descriptor/
    │   ├── neo-destinations-keystores-migrator/
    │   ├── neo-to-cf-migration-orchestrator/
    │   ├── persistence-hana/
    │   ├── sdk-replacement/
    │   ├── subaccount-migration-orchestrator/
    │   ├── subaccount-roles-export/
    │   ├── subaccount-roles-import/
    │   ├── subaccount-trust-export/
    │   ├── subaccount-trust-import/
    │   ├── tomee-runtime/
    │
    ├── marketplace/
        ├── description.md
```

## Skill Discovery

Once installed, Claude Code automatically discovers every skill in the plugin via its SKILL.md frontmatter metadata. Skills can be invoked by:

- **Name**: "authentication-xsuaa"
- **Keywords**: "XSUAA", "authentication", "OAuth"
- **Category**: "security skills"
- **Description**: Natural language matching

## Dependency Management

Skills declare dependencies in their frontmatter:

```yaml
metadata:
  dependencies: ["sdk-replacement"]
  cf-services: ["xsuaa", "destination"]
```

The orchestrator automatically:
- Resolves dependency chains
- Executes skills in topological order
- Validates prerequisites before execution
- Provisions CF services as needed

## Progressive Disclosure

Skills follow efficient context usage patterns:

1. **Metadata (~100 tokens)** - Loaded at startup
   - Name, description, category
   - Dependencies and CF services
   - When to use detection keywords

2. **Instructions (<5000 tokens)** - Loaded when activated
   - Detection patterns
   - Transformation steps
   - Verification procedures

3. **Resources (on-demand)** - Loaded only when needed
   - `scripts/` - Executable helpers
   - `references/` - Detailed docs
   - `assets/` - Configuration templates

## Prerequisites

Before starting migration, ensure you have:

- ✅ Java 25+ installed
- ✅ Maven 3.6+
- ✅ Cloud Foundry CLI with MultiApps plugin
- ✅ Access to SAP BTP Cloud Foundry environment
- ✅ CF space with required service entitlements

## Examples

### Example 1: Full Migration

```
I have a Neo Java application that uses FORM authentication,
connects to HANA database, and makes HTTP calls to external services.
Migrate it to Cloud Foundry.

> Use the neo-to-cf-migration-orchestrator skill...

✓ Detected: Java 8, javax.* imports
✓ Detected: FORM authentication in web.xml
✓ Detected: HANA DataSource
✓ Detected: DestinationFactory usage

Applying skills in order:
1. jakarta-java25-migration ✓
2. sdk-replacement ✓
3. authentication-xsuaa ✓
4. persistence-hana ✓
5. destinations ✓

Migration complete! Generated MTA descriptor with 5 CF services.
```

### Example 2: Single Skill

```
Set up XSUAA authentication for my Cloud Foundry application

> Use the authentication-xsuaa skill...

Creating xs-security.json...
Creating Application Router...
Updating web.xml with XSUAA auth-method...
Generated MTA descriptor with xsuaa service.
```

## CF Services

Skills provision these Cloud Foundry services:

| Service | Plan | Used By Skills |
|---------|------|----------------|
| xsuaa | application | authentication-xsuaa |
| destination | lite | authentication-xsuaa, destinations, connectivity-onpremise, mail-destinations |
| connectivity | lite | connectivity-onpremise |
| hana-schema | - | persistence-hana |
| sdm | standard | document-management-sdm |
| credstore | standard | keystore-credstore |
| cloud-logging | standard | monitoring-logging |

## Architecture

### Agent Skills Standard

All skills follow the [agentskills.io specification](https://agentskills.io/specification):

- ✅ YAML frontmatter with metadata
- ✅ Standardized SKILL.md structure
- ✅ Progressive disclosure
- ✅ Self-contained with assets/scripts/references
- ✅ Clear dependency chains

### Claude Code Integration

Plugin integrates with Claude Code via:

- ✅ `plugin.json` manifest
- ✅ Automatic skill discovery
- ✅ Native tool integration (Bash, Read, Edit, Write)
- ✅ Context-aware execution
- ✅ Multi-step workflow orchestration

## Documentation

- **[MIGRATION-GUIDE.md](docs/MIGRATION-GUIDE.md)** - Migration from standalone to plugin
- **[CHANGELOG.md](CHANGELOG.md)** - Version history
- **[examples/](docs/examples/)** - Usage examples

## Related Resources

- [SAP BTP Documentation](https://help.sap.com/docs/btp)
- [SAP Cloud SDK](https://sap.github.io/cloud-sdk/)
- [Cloud Foundry CLI](https://docs.cloudfoundry.org/cf-cli/)
- [Agent Skills Specification](https://agentskills.io/specification)
- [Claude Code Documentation](https://claude.ai/code)

## Version History

### 1.0.0 (Current)

- Initial Claude Code plugin release
- 22 modular migration skills
- Agent Skills standard compliance
- Full orchestration support
- Progressive disclosure architecture
- Distributed assets and templates

See [CHANGELOG.md](CHANGELOG.md) for complete history.

## Contributing

Contributions welcome! When adding skills:

1. Follow [Agent Skills specification](https://agentskills.io/specification)
2. Include proper YAML frontmatter
3. Keep SKILL.md under 500 lines
4. Add assets, scripts, references as needed
5. Update plugin.json skills array
6. Update this README
7. Add entry to CHANGELOG.md

## Support

- **Issues**: [GitHub Issues](https://github.com/SAP-samples/btp-neo-java-app-migration/issues)
- **SAP BTP Docs**: [Migration Guide](https://help.sap.com/docs/btp/sap-business-technology-platform/migrating-from-neo-environment-to-cloud-foundry-environment)

## License

Apache-2.0 - See [LICENSE](LICENSE) for details.

## Authors

**SAP BTP Migration Team**

- Plugin architecture and skills development
- Testing and validation
- Documentation

## Acknowledgments

- SAP BTP team for migration guidance
- Agent Skills community for specification
- Claude Code team for plugin framework
- OpenRewrite for Java/Jakarta migration recipes

---

**Ready to migrate?** Install the plugin and run:

```
Use the neo-to-cf-migration-orchestrator skill to migrate my Neo application
```

☁️ **Happy migrating!**
