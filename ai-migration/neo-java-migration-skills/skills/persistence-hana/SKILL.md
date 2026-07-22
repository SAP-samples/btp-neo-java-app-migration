---
name: persistence-hana
description: Invoke this skill to configure HANA Cloud database connectivity. Detects javax.sql.DataSource or jakarta.sql.DataSource resource-ref in web.xml, or @Resource DataSource in Java. Replaces Neo JNDI with CF HANA schema binding.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Persistence (Database)

Configure HANA Cloud database connectivity for Cloud Foundry.

## Purpose

Replace Neo's `javax.sql.DataSource` JNDI resource reference with Cloud Foundry's HANA schema binding using the SAP Java Buildpack's `TomcatDataSourceFactory`.

## Detection

This skill applies if any of these patterns are found:

### In web.xml
```xml
<resource-ref>
    <res-ref-name>jdbc/DefaultDB</res-ref-name>
    <res-type>javax.sql.DataSource</res-type>
</resource-ref>
```

### In Java source files
```java
import javax.sql.DataSource;
import javax.annotation.Resource;

@Resource(name = "jdbc/DefaultDB")
private DataSource dataSource;

// OR JNDI lookup
Context ctx = new InitialContext();
DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/DefaultDB");
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

Also required:
- HANA Cloud instance available in your subaccount

## Transformation Steps

### Step 1: Remove Resource Reference from web.xml

**Remove this from web.xml:**
```xml
<resource-ref>
    <res-ref-name>jdbc/DefaultDB</res-ref-name>
    <res-type>javax.sql.DataSource</res-type>
</resource-ref>
```

### Step 2: Create context.xml

Create `src/main/webapp/META-INF/context.xml` - see [assets/context.xml](assets/context.xml):

```xml
<?xml version='1.0' encoding='utf-8'?>
<Context>
    <Resource name="jdbc/DefaultDB"
              auth="Container"
              type="javax.sql.DataSource"
              factory="com.sap.xs.jdbc.datasource.tomcat.TomcatDataSourceFactory"
              service="${service_name_for_DefaultDB}"/>
</Context>
```

> **Note:** The `${service_name_for_DefaultDB}` placeholder is replaced at runtime via the `JBP_CONFIG_RESOURCE_CONFIGURATION` environment variable.

### Step 3: Create resource_configuration.yml

Create `src/main/webapp/META-INF/sap_java_buildpack/config/resource_configuration.yml` - see [assets/resource_configuration.yml](assets/resource_configuration.yml):

```yaml
---
tomcat/webapps/ROOT/META-INF/context.xml:
  service_name_for_DefaultDB: ${app-name}-hana
```

> **Note:** Replace `${app-name}-hana` with your actual HANA service instance name defined in mtad.yaml.

### Step 4: Update MTA Descriptor

Add HANA schema resource and configuration to `mtad.yaml`:

```yaml
_schema-version: "3.2"
ID: ${app-name}
version: 0.0.1

modules:
  - name: ${app-name}
    type: java.tomcat
    path: target/${app-name}.war
    parameters:
      buildpack: sap_java_buildpack_jakarta
      disk-quota: 512MB
      memory: 512MB
    properties:
      ENABLE_SECURITY_JAVA_API_V2: true
      SET_LOGGING_LEVEL: 'ROOT: INFO'
      # Resource configuration - MUST use YAML list format
      JBP_CONFIG_RESOURCE_CONFIGURATION:
        - tomcat/webapps/ROOT/META-INF/context.xml:
            service_name_for_DefaultDB: ${app-name}-hana
    requires:
      - name: ${app-name}-hana

resources:
  - name: ${app-name}-hana
    type: com.sap.xs.hana-schema
```

> **CRITICAL — do NOT add `service: hana` or `service-plan: hdi-shared`
> under a `com.sap.xs.hana-schema` resource.**
>
> ❌ Anti-pattern subagents sometimes emit (it breaks deploy):
> ```yaml
> - name: ${app-name}-hana
>   type: com.sap.xs.hana-schema
>   parameters:
>     service: hana              # ← WRONG for hana-schema
>     service-plan: hdi-shared   # ← WRONG for hana-schema
> ```
> Those two keys belong on the GENERIC
> `type: org.cloudfoundry.managed-service` (xsuaa, destination, credstore,
> sdm, …). The dedicated `com.sap.xs.hana-schema` type already implies the
> service and the plan — adding them makes the controller reject the
> request at deploy time with:
>
> > `CF-UnprocessableEntity(10008): Invalid service plan. ... Ensure that
> > the service plan is visible in your current space ...`
>
> ✅ Correct form:
> ```yaml
> - name: ${app-name}-hana
>   type: com.sap.xs.hana-schema
> ```

### Step 5: Java Code (No Changes Required)

The Java code using DataSource via JNDI lookup continues to work:

```java
import javax.sql.DataSource;
import javax.naming.Context;
import javax.naming.InitialContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class PersistenceServlet extends HttpServlet {

    private DataSource getDataSource() throws Exception {
        Context ctx = new InitialContext();
        return (DataSource) ctx.lookup("java:comp/env/jdbc/DefaultDB");
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try (Connection conn = getDataSource().getConnection()) {

            // Create table if not exists
            String createTable = "CREATE TABLE IF NOT EXISTS PERSONS " +
                "(ID INTEGER PRIMARY KEY, NAME VARCHAR(255))";
            try (PreparedStatement stmt = conn.prepareStatement(createTable)) {
                stmt.execute();
            }

            // Query data
            String query = "SELECT * FROM PERSONS";
            try (PreparedStatement stmt = conn.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {

                response.setContentType("application/json");
                response.getWriter().println("[");

                boolean first = true;
                while (rs.next()) {
                    if (!first) response.getWriter().println(",");
                    response.getWriter().printf("{\"id\": %d, \"name\": \"%s\"}",
                        rs.getInt("ID"), rs.getString("NAME"));
                    first = false;
                }

                response.getWriter().println("]");
            }

        } catch (Exception e) {
            throw new ServletException("Database error: " + e.getMessage(), e);
        }
    }
}
```

### Step 6: Alternative - Using @Resource Annotation

If using `@Resource` annotation, it continues to work:

```java
import javax.annotation.Resource;
import javax.sql.DataSource;

public class PersistenceServlet extends HttpServlet {

    @Resource(name = "jdbc/DefaultDB")
    private DataSource dataSource;

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try (Connection conn = dataSource.getConnection()) {
            // ... use connection
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }
}
```

## Directory Structure

After applying this skill, your project should have:

```
src/main/webapp/
├── META-INF/
│   ├── context.xml                           # DataSource configuration
│   └── sap_java_buildpack/
│       └── config/
│           └── resource_configuration.yml    # Service name mapping
└── WEB-INF/
    └── web.xml                               # (resource-ref removed)
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `context.xml` | src/main/webapp/META-INF/ | Defines JNDI DataSource |
| `resource_configuration.yml` | src/main/webapp/META-INF/sap_java_buildpack/config/ | Maps service name placeholder |

## CF Services

| Service | Type | Purpose |
|---------|------|---------|
| `${app-name}-hana` | com.sap.xs.hana-schema | HANA Cloud schema binding |

## Creating HANA Cloud Instance

If you don't have a HANA Cloud instance:

### Option 1: Via BTP Cockpit
1. Navigate to your subaccount
2. Go to Service Marketplace
3. Find "SAP HANA Cloud"
4. Create an instance
5. Create a schema service key

### Option 2: Via CF CLI
```bash
# List available plans
cf marketplace -s hana

# Create HANA schema
cf create-service hana schema ${app-name}-hana
```

## Verification

### 1. Verify Directory Structure
```bash
ls -la src/main/webapp/META-INF/
# Should show: context.xml

ls -la src/main/webapp/META-INF/sap_java_buildpack/config/
# Should show: resource_configuration.yml
```

### 2. Build and Deploy
```bash
mvn clean package
cf deploy . -f
```

### 3. Verify Service Binding
```bash
cf services
# Should show ${app-name}-hana bound to ${app-name}

cf env ${app-name} | grep -A 20 "hana"
# Should show HANA credentials
```

### 4. Test Database Connectivity
```bash
curl "https://${app-url}/persistence"
# Should return database query results
```

### 5. Check Logs
```bash
cf logs ${app-name} --recent | grep -i "datasource\|hana\|jdbc"
```

## Common Issues

### Issue: "Cannot create JDBC driver" error
**Cause:** HANA JDBC driver not available.
**Solution:** The SAP Java Buildpack includes HANA drivers. Ensure you're using `sap_java_buildpack_jakarta`.

### Issue: "Service not found" during deployment
**Cause:** HANA schema service doesn't exist.
**Solution:**
1. Check HANA Cloud entitlement in subaccount
2. Create the hana-schema service manually first:
   ```bash
   cf create-service hana schema ${app-name}-hana
   ```

### Issue: Connection refused
**Cause:** HANA Cloud instance not running or IP allowlist.
**Solution:**
1. Check HANA Cloud instance is started
2. Verify IP allowlist includes CF runtime IPs

### Issue: "Invalid object name" SQL error
**Cause:** Schema not set correctly or table doesn't exist.
**Solution:** HANA schema services automatically set the current schema. Verify table names are correct.

### Issue: JBP_CONFIG_RESOURCE_CONFIGURATION not working
**Cause:** YAML syntax error or wrong path.
**Solution:**
1. Validate YAML syntax
2. Ensure path matches: `tomcat/webapps/ROOT/META-INF/context.xml`
3. Check indentation in mtad.yaml

## Using JPA (Optional)

If your application uses JPA, update `persistence.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence xmlns="https://jakarta.ee/xml/ns/persistence"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence
                                 https://jakarta.ee/xml/ns/persistence/persistence_3_0.xsd"
             version="3.0">

    <persistence-unit name="default" transaction-type="RESOURCE_LOCAL">
        <non-jta-data-source>java:comp/env/jdbc/DefaultDB</non-jta-data-source>

        <properties>
            <property name="jakarta.persistence.schema-generation.database.action"
                      value="create"/>
            <property name="eclipselink.logging.level" value="INFO"/>
        </properties>
    </persistence-unit>
</persistence>
```

## Next Steps

After completing this skill, proceed to other applicable skills:
- [../document-management-sdm/SKILL.md](../document-management-sdm/SKILL.md) - Document storage
- [../keystore-credstore/SKILL.md](../keystore-credstore/SKILL.md) - Credential storage
