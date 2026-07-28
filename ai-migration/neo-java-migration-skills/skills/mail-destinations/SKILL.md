---
name: mail-destinations
description: Invoke this skill to configure mail sessions via Destination service. Detects javax.mail.Session or jakarta.mail.Session resource-ref in web.xml, or mail session JNDI lookups. Replaces Neo JNDI mail with manual session creation.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Mail Session

Configure mail sessions via the Destination service.

## Purpose

Replace Neo's `javax.mail.Session` JNDI resource with manual session creation using mail server configuration from the Destination service.

## Detection

This skill applies if any of these patterns are found:

### In web.xml
```xml
<resource-ref>
    <res-ref-name>mail/Session</res-ref-name>
    <res-type>javax.mail.Session</res-type>
</resource-ref>
```

### In Java source files
```java
import javax.mail.Session;
import javax.annotation.Resource;

@Resource(name = "mail/Session")
private Session mailSession;

// OR JNDI lookup
Session session = (Session) ctx.lookup("java:comp/env/mail/Session");
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

2. **destinations** - `Use the destinations skill`
   - Configures Destination service
   - REQUIRED before this skill

3. **connectivity-onpremise** - `Use the connectivity-onpremise skill`
   - Required for on-premise mail servers (if applicable)

## Transformation Steps

### Step 1: Remove Resource Reference from web.xml

**Remove this from web.xml:**
```xml
<resource-ref>
    <res-ref-name>mail/Session</res-ref-name>
    <res-type>javax.mail.Session</res-type>
</resource-ref>
```

### Step 2: Add Mail Dependencies

Add to `pom.xml`:

```xml
<properties>
    <jakarta-mail-version>2.0.1</jakarta-mail-version>
    <jakarta-activation-version>2.0.1</jakarta-activation-version>
</properties>

<dependencies>
    <!-- Jakarta Mail API -->
    <dependency>
        <groupId>com.sun.mail</groupId>
        <artifactId>jakarta.mail</artifactId>
        <version>${jakarta-mail-version}</version>
    </dependency>

    <!-- Jakarta Activation (required for mail) -->
    <dependency>
        <groupId>com.sun.activation</groupId>
        <artifactId>jakarta.activation</artifactId>
        <version>${jakarta-activation-version}</version>
    </dependency>
</dependencies>
```

### Step 3: Copy Mail Session Helper Classes

Copy the mail session helper classes from [assets/session/](assets/session/) to your project's source directory. These classes handle mail session creation from destination configuration.

**Required files:**
- `MailSession.java` - Main session factory
- `MailAuthenticator.java` - Password authentication
- `MailPropertiesHandler.java` - Extracts mail properties from destination
- `OnPremiseSMTPProvider.java` - Custom SMTP provider for on-premise
- `OnPremiseSMTPTransport.java` - SMTP transport via Cloud Connector
- `ConnectivitySocks5ProxySocket.java` - SOCKS5 proxy for on-premise connectivity

Copy these to `src/main/java/com/example/document/` (the assets all declare `package com.example.document;`).

> **Note on the package**: `com.example.document` is a placeholder. Rename to your project's package
> (e.g. `com.acme.mail`) when you copy the assets in, and update the imports below to match.

**Alternative: Simple MailSessionFactory**

For simpler scenarios (internet mail only), you can create a minimal `MailSessionFactory.java`:

```java
package com.example.document;

import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

import java.util.Optional;
import java.util.Properties;

public class MailSessionFactory {

    /**
     * Create mail session from destination configuration
     */
    public static Session createSession(String destinationName) {
        Destination destination = DestinationAccessor.getDestination(destinationName);

        // Get mail server properties from destination
        String host = getProperty(destination, "mail.smtp.host")
            .orElseGet(() -> extractHost(destination));
        String port = getProperty(destination, "mail.smtp.port")
            .orElse("587");
        String user = getProperty(destination, "mail.user")
            .orElse(getProperty(destination, "User").orElse(null));
        String password = getProperty(destination, "mail.password")
            .orElse(getProperty(destination, "Password").orElse(null));

        boolean auth = getProperty(destination, "mail.smtp.auth")
            .map(Boolean::parseBoolean)
            .orElse(user != null);
        boolean starttls = getProperty(destination, "mail.smtp.starttls.enable")
            .map(Boolean::parseBoolean)
            .orElse(true);

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));

        // Store user/password in session properties for transport.connect() usage
        if (user != null) props.put("mail.smtp.user", user);
        if (password != null) props.put("mail.smtp.password", password);

        // Add any additional properties from destination
        addOptionalProperty(props, destination, "mail.smtp.ssl.enable");
        addOptionalProperty(props, destination, "mail.smtp.ssl.trust");
        addOptionalProperty(props, destination, "mail.smtp.ssl.checkserveridentity");
        addOptionalProperty(props, destination, "mail.smtp.connectiontimeout");
        addOptionalProperty(props, destination, "mail.smtp.timeout");
        addOptionalProperty(props, destination, "mail.smtp.from");
        addOptionalProperty(props, destination, "mail.from");

        if (auth && user != null && password != null) {
            final String finalUser = user;
            final String finalPassword = password;

            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(finalUser, finalPassword);
                }
            });
        } else {
            return Session.getInstance(props);
        }
    }

    private static String extractHost(Destination destination) {
        // Try to extract host from URL property
        return getProperty(destination, "URL")
            .map(url -> {
                try {
                    return new java.net.URL(url).getHost();
                } catch (Exception e) {
                    return url; // Return as-is if not a valid URL
                }
            })
            .orElseThrow(() -> new RuntimeException("Mail host not configured"));
    }

    private static Optional<String> getProperty(Destination destination, String key) {
        return destination.get(key).toJavaOptional()
            .map(Object::toString);
    }

    private static void addOptionalProperty(Properties props, Destination destination, String key) {
        getProperty(destination, key).ifPresent(value -> props.put(key, value));
    }
}
```

### Step 4: Update Mail Sending Code

**Before (Neo):**
```java
import javax.annotation.Resource;
import javax.mail.*;
import javax.mail.internet.*;
import javax.servlet.http.*;

public class MailServlet extends HttpServlet {

    @Resource(name = "mail/Session")
    private Session mailSession;

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String to = request.getParameter("to");
        String subject = request.getParameter("subject");
        String body = request.getParameter("body");

        try {
            Message message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress("sender@example.com"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setText(body);

            Transport.send(message);

            response.getWriter().println("Email sent successfully");

        } catch (MessagingException e) {
            throw new ServletException("Failed to send email", e);
        }
    }
}
```

**After (Cloud Foundry):**
```java
import com.example.document.MailSessionFactory;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.servlet.http.*;

public class MailServlet extends HttpServlet {

    private static final String MAIL_DESTINATION = "mail-destination";

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String to = request.getParameter("to");
        String subject = request.getParameter("subject");
        String body = request.getParameter("body");

        try {
            // Create session from destination
            Session mailSession = MailSessionFactory.createSession(MAIL_DESTINATION);

            Message message = new MimeMessage(mailSession);

            // Use mail.smtp.from from destination instead of hardcoded address
            String from = mailSession.getProperty("mail.smtp.from");
            if (from == null) from = mailSession.getProperty("mail.from");
            message.setFrom(new InternetAddress(from != null ? from : "sender@example.com"));

            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setText(body);

            // Important: use Transport instance with explicit credentials.
            // Transport.send(message) does NOT pass credentials from session properties.
            Transport transport = mailSession.getTransport();
            String user = mailSession.getProperty("mail.smtp.user");
            String password = mailSession.getProperty("mail.smtp.password");
            try {
                if (user != null) {
                    transport.connect(
                        mailSession.getProperty("mail.smtp.host"),
                        Integer.parseInt(mailSession.getProperty("mail.smtp.port")),
                        user, password);
                } else {
                    transport.connect();
                }
                transport.sendMessage(message, message.getAllRecipients());
            } finally {
                transport.close();
            }

            response.getWriter().println("Email sent successfully");

        } catch (MessagingException e) {
            throw new ServletException("Failed to send email", e);
        }
    }
}
```

> **Important:** Do not use `Transport.send(message)` — it creates a new transport internally and does NOT pass user/password from session properties. Always use `transport.connect(host, port, user, password)` explicitly.

### Step 5: Create Mail Destination

#### Option A: Internet Mail Server

Create destination in BTP Cockpit:

| Property | Value |
|----------|-------|
| Name | `mail-destination` |
| Type | HTTP |
| URL | `smtp://smtp.example.com` |
| Proxy Type | Internet |
| Authentication | BasicAuthentication |
| User | `your-email@example.com` |
| Password | `your-password` |

**Additional Properties:**
| Property | Value |
|----------|-------|
| `mail.smtp.host` | `smtp.example.com` |
| `mail.smtp.port` | `587` |
| `mail.smtp.auth` | `true` |
| `mail.smtp.starttls.enable` | `true` |
| `mail.smtp.from` | `sender@example.com` |
| `mail.smtp.ssl.checkserveridentity` | `true` |

> **Important:** Use `mail.smtp.from` to set the sender address. Do NOT hardcode sender addresses like `noreply@mail.hana.ondemand.com` in your code — these Neo-era addresses won't work on CF. The `mail.smtp.from` address must be verified with your SMTP provider.

#### Option B: On-Premise Mail Server

For on-premise mail servers via Cloud Connector:

| Property | Value |
|----------|-------|
| Name | `mail-destination` |
| Type | HTTP |
| URL | `smtp://mail-virtual-host:25` |
| Proxy Type | **OnPremise** |
| Authentication | NoAuthentication (or as needed) |

**Cloud Connector Configuration:**
1. Add TCP resource mapping:
   - **Protocol:** TCP
   - **Internal Host:** `mail.internal.company.com`
   - **Internal Port:** `25`
   - **Virtual Host:** `mail-virtual-host`
   - **Virtual Port:** `25`

### Step 6: Update MTA Descriptor

```yaml
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
    requires:
      - name: ${app-name}-destination
      - name: ${app-name}-connectivity  # Only needed for on-premise

resources:
  - name: ${app-name}-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite

  - name: ${app-name}-connectivity
    type: org.cloudfoundry.managed-service
    parameters:
      service: connectivity
      service-plan: lite
```

## Configuration Files

No new configuration files required. Mail configuration is stored in destinations.

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `destination` | lite | Store mail server configuration |
| `connectivity` | lite | Required for on-premise mail servers |

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Verify Destination
In BTP Cockpit, test the destination connection.

### 3. Send Test Email
```bash
curl -X POST "https://${app-url}/mail" \
  -d "to=test@example.com" \
  -d "subject=Test Email" \
  -d "body=Hello from Cloud Foundry!"
```

### 4. Check Logs
```bash
cf logs ${app-name} --recent | grep -i mail
```

## Common Issues

### Common runtime errors → root cause

| Runtime error | Root cause | Fix |
|---|---|---|
| `DestinationNotFoundException` for mail destination | Missing `connectivity-destination-service` runtime dep | Add to `pom.xml` with `<scope>runtime</scope>` |
| `MessagingException: Could not connect to SMTP host` | Destination URL or port wrong, or mail service not reachable from CF | Verify destination properties in BTP Cockpit; check that the SMTP port is open |
| `AuthenticationFailedException` | Credentials not set in destination, or `Transport.connect()` not passed explicit user/password | See issue below — pass credentials explicitly |

### Issue: "failed to connect, no password specified?" with `transport.connect()`
**Cause:** `Transport.connect()` without arguments does not read credentials from the mail session properties. The destination service provides credentials as `mail.user` and `mail.password` (with `mail.` prefix), but `transport.connect()` only reads `mail.smtp.user` from session properties and never reads any password property automatically.
**Solution:** Extract user/password from the destination properties and pass them explicitly:
```java
String user = destProperties.getOrDefault("User", destProperties.get("mail.user"));
String password = destProperties.getOrDefault("Password", destProperties.get("mail.password"));
if (user != null && !user.isBlank()) {
    String host = session.getProperty("mail.smtp.host");
    int port = Integer.parseInt(session.getProperty("mail.smtp.port"));
    transport.connect(host, port, user, password);
} else {
    transport.connect();
}
```

### Issue: "535 5.7.139 Authentication unsuccessful, basic authentication is disabled" (Office 365)
**Cause:** Microsoft has disabled basic authentication (username + password) for SMTP on Office 365 / Outlook.com accounts by default.
**Solution:**
- Enable SMTP AUTH for the specific mailbox: Microsoft 365 Admin Center > Users > select user > Mail > "Manage email apps" > enable "Authenticated SMTP"
- Or via PowerShell: `Set-CASMailbox -Identity "user@domain.com" -SmtpClientAuthenticationDisabled $false`
- Alternative: Switch to a mail provider that supports basic auth (e.g., AWS SES, SendGrid), or implement OAuth2 XOAUTH2 authentication

### Issue: "554 Message rejected: Email address is not verified" (hardcoded From address)
**Cause:** Neo applications often hardcode a sender address like `noreply@mail.hana.ondemand.com` in the message builder. After migration, the SMTP service rejects this unverified address.
**Solution:** Use `mail.smtp.from` from the destination configuration instead of a hardcoded address:
```java
String fromAddress = mailSession.getProperty("mail.smtp.from");
if (fromAddress == null || fromAddress.isBlank()) {
    fromAddress = DEFAULT_MAIL_FROM; // fallback
}
message.setFrom(new InternetAddress(fromAddress));
```
Make sure to configure `mail.smtp.from` in the BTP destination with a verified sender address.

### Issue: Destination properties have `mail.*` prefix
**Cause:** The SAP Cloud SDK returns destination properties with their original keys. Mail destinations in BTP Cockpit use `mail.user` and `mail.password` (with `mail.` prefix), not the SDK's typical `User`/`Password` keys.
**Solution:** When extracting credentials, check both key formats:
```java
String user = destProperties.getOrDefault("User", destProperties.get("mail.user"));
String password = destProperties.getOrDefault("Password", destProperties.get("mail.password"));
```

### Issue: Authentication failed
**Cause:** Invalid credentials or app password required.
**Solution:**
- For Gmail/O365: Use app-specific passwords
- Verify credentials in destination

### Issue: Connection timed out
**Cause:** Port blocked or wrong server.
**Solution:**
- Verify mail server host and port
- Check if port 587/465/25 is allowed

### Issue: SSL/TLS errors
**Solution:** Add destination properties:
```
mail.smtp.ssl.trust=*
mail.smtp.ssl.enable=true
```

### Issue: On-premise mail not reachable
**Cause:** Cloud Connector not configured for TCP.
**Solution:** Add TCP protocol mapping in Cloud Connector.

## Next Steps

After completing this skill, proceed to other applicable skills:
- [../keystore-credstore/SKILL.md](../keystore-credstore/SKILL.md) - Store mail credentials securely
- [../monitoring-logging/SKILL.md](../monitoring-logging/SKILL.md) - Monitor mail operations
