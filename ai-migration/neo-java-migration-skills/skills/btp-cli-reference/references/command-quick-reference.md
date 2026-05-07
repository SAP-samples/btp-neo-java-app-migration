# BTP CLI Quick Reference

All commands in one table. Use `btp --format json <command>` for scripting.

## Accounts Group

| Command | Purpose |
|---------|---------|
| `btp get accounts/global-account` | Show current global account details |
| `btp update accounts/global-account --display-name <name>` | Rename global account |
| `btp list accounts/subaccount` | List all subaccounts |
| `btp get accounts/subaccount <id>` | Show subaccount details |
| `btp create accounts/subaccount --display-name <n> --region <r> --subdomain <s>` | Create subaccount |
| `btp update accounts/subaccount <id> --display-name <name>` | Rename subaccount |
| `btp delete accounts/subaccount <id>` | Delete subaccount |
| `btp move accounts/subaccount <id> --to-directory <dir-id>` | Move subaccount to directory |
| `btp list accounts/directory` | List all directories |
| `btp get accounts/directory <id>` | Show directory details |
| `btp create accounts/directory --display-name <name>` | Create directory |
| `btp update accounts/directory <id> --display-name <name>` | Rename directory |
| `btp delete accounts/directory <id>` | Delete directory |
| `btp enable accounts/directory <id> --for entitlements` | Enable directory for entitlement management |
| `btp enable accounts/directory <id> --for authorizations` | Enable directory for authorization management |
| `btp list accounts/environment-instance` | List CF orgs / environment instances |
| `btp get accounts/environment-instance <id>` | Show CF org details |
| `btp create accounts/environment-instance --environment cloudfoundry --service cloudfoundry --plan standard --subaccount <id> --display-name <n> --parameters '{"instance_name":"<org>"}'` | Provision CF org |
| `btp update accounts/environment-instance <id> --subaccount <id> --parameters '{"memory":<MB>}'` | Update CF org parameters |
| `btp delete accounts/environment-instance <id>` | Delete CF org |
| `btp list accounts/entitlement` | List available and assigned entitlements |
| `btp assign accounts/entitlement --service-name <svc> --plan-name <plan> --subaccount <id> --amount <n>` | Assign entitlement quota to subaccount |
| `btp assign accounts/entitlement --service-name APPLICATION_RUNTIME --plan-name MEMORY --subaccount <id> --amount <MB>` | Assign CF Runtime memory (fixes quota) |
| `btp list accounts/subscription` | List SaaS subscriptions |
| `btp subscribe accounts/subaccount --app-name <app> --plan <plan>` | Subscribe to SaaS app |
| `btp unsubscribe accounts/subaccount --app-name <app>` | Unsubscribe from SaaS app |
| `btp list accounts/resource-provider` | List resource providers (AWS, Azure, GCP) |
| `btp create accounts/resource-provider --provider <p> --technical-name <k>` | Register resource provider |
| `btp delete accounts/resource-provider <provider>/<key>` | Remove resource provider |
| `btp list accounts/label` | List labels on current subaccount |
| `btp set accounts/label --key <k> --value <v>` | Set label |
| `btp delete accounts/label --key <k>` | Delete label |
| `btp list accounts/custom-property` | List custom properties |
| `btp set accounts/custom-property --key <k> --value <v>` | Set custom property |

## Security Group

| Command | Purpose |
|---------|---------|
| `btp list security/role-collection` | List all role collections |
| `btp get security/role-collection "<name>"` | Show role collection details (roles + users) |
| `btp create security/role-collection "<name>" --description "<desc>"` | Create role collection |
| `btp update security/role-collection "<name>" --description "<desc>"` | Update description |
| `btp delete security/role-collection "<name>"` | Delete role collection |
| `btp add security/role --role-collection "<rc>" --role-name "<r>" --app-id "<appid>" --role-template "<tmpl>"` | Add XSUAA role-template to role collection |
| `btp remove security/role --role-collection "<rc>" --role-name "<r>" --app-id "<appid>" --role-template "<tmpl>"` | Remove role-template from role collection |
| `btp list security/user` | List users in subaccount |
| `btp get security/user <email>` | Show user details |
| `btp create security/user <email> [--of-idp <origin>]` | Add user to subaccount |
| `btp delete security/user <email> [--of-idp <origin>]` | Remove user from subaccount |
| `btp assign security/role-collection "<rc>" --to-user <email> [--of-idp <origin>]` | Assign role collection to user |
| `btp unassign security/role-collection "<rc>" --from-user <email> [--of-idp <origin>]` | Remove role collection from user |
| `btp assign security/role-collection "<rc>" --to-group <group> [--of-idp <origin>]` | Assign role collection to group |
| `btp unassign security/role-collection "<rc>" --from-group <group> [--of-idp <origin>]` | Remove role collection from group |
| `btp list security/app` | List XSUAA applications (deployed MTA apps) |
| `btp get security/app <appid>` | Show app scopes and role-templates |
| `btp list security/trust` | List trust configurations (IdPs) |
| `btp get security/trust <origin>` | Show trust details |
| `btp create security/trust --idp <ias-host>` | Create OIDC trust with IAS tenant |
| `btp update security/trust <origin> --description "<desc>"` | Update trust description |
| `btp delete security/trust <origin>` | Delete trust configuration |
| `btp migrate security/trust "<origin>"` | Convert SAML trust to OIDC (IAS only) |
| `btp list security/setting` | List security settings |
| `btp get security/setting <key>` | Get security setting value |
| `btp set security/setting <key>=<value>` | Set security setting |
| `btp unset security/setting <key>` | Remove security setting |

## Services Group

| Command | Purpose |
|---------|---------|
| `btp list services/offering` | List available service offerings |
| `btp get services/offering <id>` | Show service offering details |
| `btp list services/plan` | List available service plans |
| `btp get services/plan <id>` | Show service plan details |
| `btp list services/instance` | List service instances |
| `btp get services/instance <id>` | Show service instance details |
| `btp create services/instance --offering-name <svc> --plan-name <plan> --display-name <name>` | Create service instance |
| `btp update services/instance <id> --display-name <name>` | Update service instance |
| `btp delete services/instance <id>` | Delete service instance |
| `btp list services/binding` | List service bindings (service keys) |
| `btp get services/binding <id>` | Show binding details and credentials |
| `btp create services/binding --instance-id <id> --display-name <name>` | Create service binding (service key) |
| `btp delete services/binding <id>` | Delete service binding |
| `btp list services/broker` | List service brokers |
| `btp get services/broker <id>` | Show broker details |
| `btp create services/broker --name <n> --url <url> --user <u> --password <p>` | Register broker |
| `btp update services/broker <id> --url <url>` | Update broker URL |
| `btp delete services/broker <id>` | Deregister broker |
| `btp list services/platform` | List service manager platforms |
| `btp get services/platform <id>` | Show platform details |
| `btp register services/platform --name <n> --type <t>` | Register platform |
| `btp unregister services/platform <id>` | Unregister platform |

## Connectivity Group (Experimental)

| Command | Purpose |
|---------|---------|
| `btp list connectivity/destination` | List destinations (experimental) |
| `btp get connectivity/destination <name>` | Get destination details (experimental) |

> **Note:** Use the Destination Service REST API for production automation — these commands are experimental.

## Session / Config Commands

| Command | Purpose |
|---------|---------|
| `btp login --sso` | Browser SSO login |
| `btp login --user <email> --password <pass>` | Password login |
| `btp login --sso --idp <origin>` | Login via custom IdP |
| `btp logout` | End current session |
| `btp target` | Show current targets |
| `btp target --subaccount <id>` | Set default subaccount |
| `btp target --reset` | Clear all targets |
| `btp --version` | Show CLI version |
| `btp set config --format json` | Set JSON as default output |
| `btp set config --format text` | Set text as default output |
| `btp --format json <command>` | Override output format for one command |
| `btp help` | Show help |
| `btp help <command>` | Show help for specific command |

## Key Flags (apply to most commands)

| Flag | Meaning |
|------|---------|
| `--subaccount <id>` | Target a specific subaccount (overrides targeted subaccount) |
| `--global-account <id>` | Target a specific global account |
| `--directory <id>` | Target a specific directory |
| `--of-idp <origin>` | Specify IdP origin for user commands (use instead of `--origin`) |
| `--format json` | JSON output (place right after `btp`) |
| `--help` | Show help for a specific command |
