# SAP BTP Neo to Cloud Foundry Migration Plugin

## Overview

Comprehensive Claude Code plugin for migrating SAP BTP Neo Java applications to Cloud Foundry. Contains 12 modular, AI-powered migration skills that handle everything from Java 25/Jakarta EE upgrades to authentication, database connectivity, and service integration.

## What's Included

### 🎭 Intelligent Orchestrator
- Analyzes your Neo application automatically
- Detects required CF services
- Executes migration skills in correct dependency order
- Generates MTA deployment descriptors
- Validates each migration step

### 🏗️ Foundation Migration
- **Java 25 & Jakarta EE 10** - Automated upgrade using OpenRewrite
- **SDK Replacement** - Neo Java Web API → SAP Cloud SDK

### 🔐 Security & Authentication
- **XSUAA** - OAuth 2.0 authentication with Application Router
- **Credential Store** - Secure secrets management

### 🔌 Connectivity
- **Destinations** - External HTTP connections
- **Cloud Connector** - On-premise system access

### 💾 Data & Storage
- **HANA Cloud** - Database binding and configuration
- **Document Management** - ECM to SDM migration with CMIS

### 📧 Communication
- **Mail Service** - Email configuration via destinations

### 📊 Operations
- **Cloud Logging** - Centralized logs and custom metrics

### ⚙️ Runtime
- **TomEE** - EJB application container support

## Key Features

✅ **12 Modular Skills** - Use individually or orchestrated together
✅ **Agent Skills Standard** - Follows agentskills.io specification
✅ **Progressive Disclosure** - Efficient context usage
✅ **Automatic Detection** - Identifies required services from code
✅ **Dependency Management** - Proper execution ordering
✅ **Template Library** - Pre-configured POM, MTA, security descriptors
✅ **Code Helpers** - Java classes for SDM, Credential Store
✅ **CF Services** - Provisions XSUAA, destinations, HANA, and more

## Use Cases

### Full Migration
```
Use the neo-to-cf-orchestrator skill to migrate my Neo application
```
Automatically handles complete end-to-end migration with dependency resolution.

### Targeted Migration
```
Use the authentication-xsuaa skill to add XSUAA authentication
```
Apply individual skills for specific migration needs.

## Technical Details

- **Compatible with**: Java 8/11/17, Neo Java Web API, javax.* packages
- **Migrates to**: Java 25, Jakarta EE 10, SAP Cloud SDK, CF buildpacks
- **CF Services**: xsuaa, destination, connectivity, hana-schema, sdm, credstore, cloud-logging
- **Tools**: Maven, OpenRewrite, CF CLI, MultiApps plugin

## Prerequisites

- Java 25+
- Maven 3.6+
- Cloud Foundry CLI with MultiApps plugin
- SAP BTP Cloud Foundry environment access

## Installation

```bash
git clone <repo-url> ~/.claude/plugins/neo-migration-skills
```

Restart Claude Code to load the plugin.

## Documentation

- Comprehensive skill catalog with detection patterns
- Step-by-step transformation guides
- Before/after code examples
- Troubleshooting guides
- CF service configuration templates

## Support

- GitHub Issues for bug reports
- GitHub Discussions for questions
- SAP BTP documentation references
- Active maintenance and updates

## License

Apache-2.0

---

**Transform your Neo applications to Cloud Foundry with AI-powered migration skills.**

☁️ Modern · 🚀 Fast · ✅ Reliable
