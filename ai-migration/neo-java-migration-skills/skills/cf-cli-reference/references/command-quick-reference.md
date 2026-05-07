# CF CLI Quick Reference

All commands in one table, organized by group.

## Getting Started / Session

| Command | Purpose |
|---------|---------|
| `cf login -a <API> -u <email> -p <pass> -o <org> -s <space>` | Authenticate and set target |
| `cf login -a <API> --sso` | Browser SSO login |
| `cf login -a <API> --sso-passcode <code>` | Login with one-time passcode |
| `cf login -a <API> -u <user> --origin <idp>` | Login with custom IdP |
| `cf auth <CLIENT_ID> <CLIENT_SECRET> --client-credentials` | Non-interactive service account auth |
| `cf logout` | End session |
| `cf target` | Show current API, user, org, space |
| `cf target -o <ORG> -s <SPACE>` | Set org and space |
| `cf api` | Show current API endpoint |
| `cf api <URL>` | Set API endpoint |
| `cf oauth-token` | Print current OAuth token |
| `cf version` | Show CLI version |
| `cf help` | Show help |
| `cf help -a` | Show all commands |

## Apps

| Command | Purpose |
|---------|---------|
| `cf apps` / `cf a` | List apps in current space |
| `cf app <NAME>` | Show app details (state, memory, routes) |
| `cf app <NAME> --guid` | Print only the app GUID |
| `cf push <NAME> -f manifest.yml` | Push app from manifest |
| `cf push <NAME> -p <PATH> -m <MEM> -i <N>` | Push with flags |
| `cf push <NAME> --strategy rolling` | Rolling deployment |
| `cf push <NAME> --no-start` | Push but don't start |
| `cf start <NAME>` | Start a stopped app |
| `cf stop <NAME>` | Stop a running app |
| `cf restart <NAME>` | Stop and start (no restage) |
| `cf restart <NAME> --strategy rolling` | Rolling restart |
| `cf restage <NAME>` | Re-run buildpack (use after staging env var changes) |
| `cf scale <NAME> -i <N> -m <MEM> -k <DISK>` | Scale instances/memory/disk |
| `cf scale <NAME> -f` | Force restart after scale |
| `cf delete <NAME> -f -r` | Delete app and its routes |
| `cf rename <NAME> <NEW>` | Rename app |
| `cf restart-app-instance <NAME> <INDEX>` | Restart one instance |
| `cf cancel-deployment <NAME>` | Cancel rolling deployment |
| `cf logs <NAME>` | Tail live logs |
| `cf logs <NAME> --recent` | Dump recent logs |
| `cf events <NAME>` | Show recent events (crashes, scaling) |
| `cf env <NAME>` | Show environment variables + VCAP_SERVICES |
| `cf set-env <NAME> <VAR> <VALUE>` | Set environment variable |
| `cf unset-env <NAME> <VAR>` | Remove environment variable |
| `cf ssh <NAME>` | SSH into first app instance |
| `cf ssh <NAME> -i <N> -c "<CMD>"` | SSH to specific instance, run command |
| `cf ssh <NAME> -L <local>:<host>:<remote>` | Local port forwarding |
| `cf ssh-code` | Get one-time SSH passcode |
| `cf enable-ssh <NAME>` | Enable SSH for app |
| `cf disable-ssh <NAME>` | Disable SSH for app |
| `cf ssh-enabled <NAME>` | Check if SSH is enabled |
| `cf get-health-check <NAME>` | Show health check type |
| `cf set-health-check <NAME> http --endpoint /health` | Set HTTP health check |
| `cf create-app-manifest <NAME> -p manifest.yml` | Generate manifest from running app |
| `cf stacks` | List available stacks |
| `cf run-task <NAME> --command "<CMD>" --name <TASK>` | Run one-off task |
| `cf tasks <NAME>` | List tasks |
| `cf terminate-task <NAME> <ID>` | Kill a task |
| `cf droplets <NAME>` | List droplets |
| `cf revisions <NAME>` | List revisions (experimental) |
| `cf rollback <NAME> --version <N>` | Rollback to revision (experimental) |

## Services

| Command | Purpose |
|---------|---------|
| `cf marketplace` | List available service offerings and plans |
| `cf marketplace -e <OFFERING>` | Filter marketplace by offering |
| `cf services` / `cf s` | List service instances in current space |
| `cf service <INSTANCE>` | Show instance details and bindings |
| `cf create-service <OFFERING> <PLAN> <NAME>` | Create service instance |
| `cf create-service <OFFERING> <PLAN> <NAME> -c '{"k":"v"}' -w` | Create with config, wait |
| `cf update-service <INSTANCE> -p <NEW_PLAN>` | Change service plan |
| `cf upgrade-service <INSTANCE> -f` | Upgrade to latest plan version |
| `cf delete-service <INSTANCE> -f -w` | Delete service instance |
| `cf rename-service <INSTANCE> <NEW>` | Rename service instance |
| `cf bind-service <APP> <INSTANCE>` | Bind service to app |
| `cf bind-service <APP> <INSTANCE> -c '{"k":"v"}'` | Bind with parameters |
| `cf unbind-service <APP> <INSTANCE>` | Unbind service from app |
| `cf service-keys <INSTANCE>` | List service keys |
| `cf service-key <INSTANCE> <KEY>` | Show service key credentials |
| `cf create-service-key <INSTANCE> <KEY>` | Create service key |
| `cf delete-service-key <INSTANCE> <KEY> -f` | Delete service key |
| `cf create-user-provided-service <NAME> -p '{"k":"v"}'` | Create CUPS |
| `cf update-user-provided-service <NAME> -p '{"k":"v"}'` | Update CUPS |
| `cf share-service <INSTANCE> -s <SPACE> -o <ORG>` | Share service to another space |
| `cf unshare-service <INSTANCE> -s <SPACE> -o <ORG> -f` | Unshare |
| `cf bind-route-service <DOMAIN> <INSTANCE> -n <HOSTNAME>` | Bind route service |

## Routes and Domains

| Command | Purpose |
|---------|---------|
| `cf domains` | List domains |
| `cf create-private-domain <ORG> <DOMAIN>` | Create org-scoped domain |
| `cf delete-private-domain <DOMAIN> -f` | Delete private domain |
| `cf create-shared-domain <DOMAIN>` | Create platform-wide domain |
| `cf create-shared-domain <DOMAIN> --internal` | Create internal domain |
| `cf delete-shared-domain <DOMAIN> -f` | Delete shared domain |
| `cf routes` | List routes in current space |
| `cf routes --orglevel` | List routes across all spaces in org |
| `cf route <HOSTNAME> <DOMAIN>` | Show route details |
| `cf check-route <HOSTNAME> <DOMAIN>` | Check if route exists |
| `cf create-route <DOMAIN> -n <HOSTNAME> --path <PATH>` | Create unmapped route |
| `cf map-route <APP> <DOMAIN> -n <HOSTNAME>` | Map route to app |
| `cf map-route <APP> <DOMAIN> -n <HOSTNAME> --path <PATH>` | Map with path |
| `cf unmap-route <APP> <DOMAIN> -n <HOSTNAME>` | Unmap route from app |
| `cf delete-route <DOMAIN> -n <HOSTNAME> -f` | Delete route |
| `cf delete-orphaned-routes -f` | Delete routes not mapped to any app |

## Orgs and Spaces

| Command | Purpose |
|---------|---------|
| `cf orgs` | List orgs |
| `cf org <ORG>` | Show org details |
| `cf create-org <ORG>` | Create org |
| `cf delete-org <ORG> -f` | Delete org |
| `cf rename-org <ORG> <NEW>` | Rename org |
| `cf spaces` | List spaces in current org |
| `cf space <SPACE>` | Show space details |
| `cf space <SPACE> --guid` | Print space GUID |
| `cf create-space <SPACE> -o <ORG>` | Create space |
| `cf delete-space <SPACE> -f` | Delete space |
| `cf rename-space <SPACE> <NEW>` | Rename space |
| `cf allow-space-ssh <SPACE>` | Allow SSH in space |
| `cf apply-manifest -f manifest.yml` | Apply manifest to space |

## Network Policies

| Command | Purpose |
|---------|---------|
| `cf network-policies` | List container-to-container network policies |
| `cf add-network-policy <SRC> --destination-app <DST> --protocol tcp --port 8080` | Allow internal traffic |
| `cf remove-network-policy <SRC> --destination-app <DST> --protocol tcp --port 8080` | Remove policy |

## Admin: Users and Roles

| Command | Purpose |
|---------|---------|
| `cf org-users <ORG>` | List users with org roles |
| `cf set-org-role <USER> <ORG> OrgManager` | Grant org role |
| `cf unset-org-role <USER> <ORG> OrgManager` | Revoke org role |
| `cf space-users <ORG> <SPACE>` | List users with space roles |
| `cf set-space-role <USER> <ORG> <SPACE> SpaceDeveloper` | Grant space role |
| `cf unset-space-role <USER> <ORG> <SPACE> SpaceDeveloper` | Revoke space role |
| `cf create-user <USER> --origin <IDP>` | Create platform user |
| `cf delete-user <USER> -f` | Delete user |

**Org roles:** `OrgManager`, `OrgAuditor`, `BillingManager`
**Space roles:** `SpaceManager`, `SpaceDeveloper`, `SpaceAuditor`, `SpaceSupporter`

## Admin: Quotas

| Command | Purpose |
|---------|---------|
| `cf org-quotas` | List org quotas |
| `cf org-quota <NAME>` | Show org quota details |
| `cf create-org-quota <NAME> -m 10G -r 100 -s 50 -a -1` | Create org quota |
| `cf update-org-quota <NAME> -m 20G` | Update org quota |
| `cf set-org-quota <ORG> <QUOTA>` | Apply quota to org |
| `cf delete-org-quota <NAME> -f` | Delete org quota |
| `cf space-quotas` | List space quotas |
| `cf create-space-quota <NAME> -m 4G -r 20` | Create space quota |
| `cf set-space-quota <SPACE> <QUOTA>` | Apply quota to space |
| `cf unset-space-quota <SPACE> <QUOTA>` | Remove quota from space |
| `cf delete-space-quota <NAME> -f` | Delete space quota |

> **Note:** Space quotas cannot override org-level route limits. If the org quota has 0 routes (SUBSCRIPTION_QUOTA), assign CF Runtime in BTP Cockpit.

## Admin: Service Brokers

| Command | Purpose |
|---------|---------|
| `cf service-brokers` | List registered brokers |
| `cf create-service-broker <NAME> <USER> <PASS> <URL>` | Register broker |
| `cf update-service-broker <NAME> <USER> <PASS> <URL>` | Update broker credentials |
| `cf delete-service-broker <NAME> -f` | Deregister broker |
| `cf service-access` | Show service/plan visibility |
| `cf enable-service-access <OFFERING> -p <PLAN> -o <ORG>` | Enable plan for org |
| `cf disable-service-access <OFFERING> -p <PLAN> -o <ORG>` | Disable plan |

## Admin: Buildpacks

| Command | Purpose |
|---------|---------|
| `cf buildpacks` | List buildpacks |
| `cf create-buildpack <NAME> <PATH> <POSITION>` | Add buildpack |
| `cf update-buildpack <NAME> -p <PATH> --enable` | Update buildpack |
| `cf delete-buildpack <NAME> -f` | Delete buildpack |

## Admin: Security Groups

| Command | Purpose |
|---------|---------|
| `cf security-groups` | List security groups |
| `cf create-security-group <NAME> rules.json` | Create security group |
| `cf update-security-group <NAME> rules.json` | Update rules |
| `cf delete-security-group <NAME> -f` | Delete security group |
| `cf bind-staging-security-group <NAME>` | Apply to staging lifecycle |
| `cf bind-running-security-group <NAME>` | Apply to running lifecycle |
| `cf bind-security-group <NAME> <ORG> <SPACE> --lifecycle running` | Bind to space |

## MultiApps Plugin (MTA)

| Command | Purpose |
|---------|---------|
| `cf mtas` | List all MTAs in current space |
| `cf mta <MTA_ID>` | Show MTA status and modules |
| `cf mta-ops` | List active MTA operations |
| `cf deploy . -f` | Deploy MTA from current directory (reads mtad.yaml) |
| `cf deploy <FILE>.mtar -f` | Deploy from MTAR archive |
| `cf deploy . -f -e extensions.yaml` | Deploy with extension descriptor |
| `cf deploy . -f --no-start` | Deploy without starting apps |
| `cf deploy . -f --delete-services` | Deploy and delete removed services |
| `cf deploy . -f --strategy blue-green` | Blue-green deployment |
| `cf deploy -i <OP_ID> -a abort` | Abort stuck operation |
| `cf deploy -i <OP_ID> -a retry` | Retry failed operation |
| `cf deploy -i <OP_ID> -a resume` | Resume paused operation |
| `cf undeploy <MTA_ID> -f` | Remove MTA |
| `cf undeploy <MTA_ID> -f --delete-services` | Remove MTA and its services |
| `cf bg-deploy . -f` | Blue-green deploy |
| `cf download-mta-op-logs <OP_ID>` / `cf dmol <OP_ID>` | Download operation logs |
| `cf purge-mta-config` | Clean stale MTA config |

## Utilities

| Command | Purpose |
|---------|---------|
| `cf curl "/v3/apps"` | Raw JSON API call |
| `cf curl "/v3/apps" -X GET` | GET request |
| `cf curl "/v3/apps/<guid>/processes" -X GET` | App process details |
| `cf oauth-token` | Print OAuth token (for use in curl) |
| `cf plugins` | List installed plugins |
| `cf install-plugin multiapps` | Install MultiApps plugin |
| `cf install-plugin <NAME_OR_URL> -f` | Install/update plugin |
| `cf uninstall-plugin <NAME>` | Remove plugin |
| `cf config --color false` | Disable output colors |

## Key Flags Reference

| Flag | Applies To | Meaning |
|------|-----------|---------|
| `-f` | delete, scale, deploy, undeploy, etc. | Force — skip confirmation prompt |
| `-r` | delete | Also delete mapped routes |
| `-w` | create/update/delete service | Wait for async operation |
| `--recent` | logs | Dump buffered logs instead of streaming |
| `--guid` | app, space, org | Print only the GUID |
| `--strategy rolling` | push, restart | Rolling deployment |
| `--no-start` | push, deploy | Don't start after deployment |
| `-i` | deploy | Interact with existing MTA operation |
| `-a abort/retry/resume` | deploy | Action for existing operation |
| `--client-credentials` | auth | Service account (non-interactive) |
| `--origin <IDP>` | set-org-role, set-space-role, create-user | User's IdP origin |
