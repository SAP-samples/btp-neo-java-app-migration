---
name: monitoring-logging
description: Invoke this skill to set up SAP Cloud Logging for application monitoring. Use when application needs centralized logging, custom metrics, or health checks. Replaces Neo built-in monitoring with Cloud Logging service.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Monitoring and Logging

Set up SAP Cloud Logging for application monitoring in Cloud Foundry.

## Purpose

Replace Neo's built-in monitoring and availability checks with SAP Cloud Logging service for centralized logging, custom metrics, and application observability.

## Detection

This skill applies if any of these patterns are found:

- Application uses Neo monitoring dashboards
- Availability checks configured in Neo
- Custom JMX metrics exposed
- Performance monitoring requirements

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

Also required:
- Cloud Logging service entitlement

## Transformation Steps

### Step 1: Update MTA Descriptor

Add Cloud Logging service and OpenTelemetry configuration:

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
      SET_LOGGING_LEVEL: 'ROOT: INFO, com.example: DEBUG'
      # OpenTelemetry configuration
      OTEL_JAVAAGENT_ENABLED: true
      OTEL_SERVICE_NAME: ${app-name}
      OTEL_TRACES_EXPORTER: otlp
      OTEL_METRICS_EXPORTER: otlp
      OTEL_LOGS_EXPORTER: otlp
    requires:
      - name: ${app-name}-cls

resources:
  - name: ${app-name}-cls
    type: org.cloudfoundry.managed-service
    parameters:
      service: cloud-logging
      service-plan: standard
```

### Step 2: Configure Logging in Application

Create or update `src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!-- Console appender for Cloud Foundry log collection -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- JSON format for structured logging (recommended) -->
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>correlationId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>

    <!-- Application-specific logging -->
    <logger name="com.example" level="DEBUG" />

    <!-- SAP libraries -->
    <logger name="com.sap.cloud.sdk" level="WARN" />

</configuration>
```

Add logstash encoder dependency to `pom.xml`:

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### Step 3: Implement Structured Logging

Create a logging utility:

```java
package com.example.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class AppLogger {

    private final Logger logger;

    public AppLogger(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
    }

    /**
     * Log with correlation ID for request tracing
     */
    public void logWithCorrelation(String correlationId, String message, Object... args) {
        try {
            MDC.put("correlationId", correlationId);
            logger.info(message, args);
        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Log with user context
     */
    public void logWithUser(String userId, String message, Object... args) {
        try {
            MDC.put("userId", userId);
            logger.info(message, args);
        } finally {
            MDC.remove("userId");
        }
    }

    /**
     * Log business event with structured data
     */
    public void logBusinessEvent(String eventType, String entityId, String action) {
        try {
            MDC.put("eventType", eventType);
            MDC.put("entityId", entityId);
            MDC.put("action", action);
            logger.info("Business event: {} on {} - {}", eventType, entityId, action);
        } finally {
            MDC.clear();
        }
    }

    // Standard logging methods
    public void info(String message, Object... args) {
        logger.info(message, args);
    }

    public void error(String message, Throwable t) {
        logger.error(message, t);
    }

    public void debug(String message, Object... args) {
        logger.debug(message, args);
    }
}
```

### Step 4: Implement Health Check Endpoint

Replace Neo availability checks with a health endpoint:

```java
package com.example.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;

@WebServlet("/health")
public class HealthCheckServlet extends HttpServlet {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Inject or lookup DataSource if database is used
    private DataSource dataSource;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        ObjectNode health = objectMapper.createObjectNode();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());

        // Check database connectivity
        ObjectNode database = objectMapper.createObjectNode();
        try {
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection()) {
                    database.put("status", "UP");
                }
            } else {
                database.put("status", "N/A");
            }
        } catch (Exception e) {
            database.put("status", "DOWN");
            database.put("error", e.getMessage());
            health.put("status", "DOWN");
        }
        health.set("database", database);

        // Check memory
        ObjectNode memory = objectMapper.createObjectNode();
        Runtime runtime = Runtime.getRuntime();
        memory.put("total", runtime.totalMemory());
        memory.put("free", runtime.freeMemory());
        memory.put("used", runtime.totalMemory() - runtime.freeMemory());
        memory.put("max", runtime.maxMemory());
        health.set("memory", memory);

        resp.setContentType("application/json");
        resp.setStatus(health.get("status").asText().equals("UP") ? 200 : 503);
        objectMapper.writeValue(resp.getOutputStream(), health);
    }
}
```

### Step 5: Access Logs in Cloud Logging

#### View Application Logs
1. Open Cloud Logging dashboard (from BTP Cockpit)
2. Navigate to Discover
3. Use index pattern: `logs-cfsyslog-*`
4. Filter by `cf.app_name: ${app-name}`

#### View Metrics
1. Navigate to Metrics Explorer
2. Use index pattern: `metrics-otel-v1-*`
3. Query custom metrics: `app.requests.total`

#### Create Dashboard
1. Navigate to Dashboards
2. Create visualizations for:
   - Request rate
   - Error rate
   - Response time percentiles
   - JVM memory usage

## Cloud Logging Query Examples

### Error Logs
```
cf.app_name: "my-app" AND level: "ERROR"
```

### Slow Requests
```
cf.app_name: "my-app" AND http.response_time > 1000
```

### User Activities
```
cf.app_name: "my-app" AND userId: *
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `logback.xml` | src/main/resources/ | Logging configuration |

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `cloud-logging` | standard | Centralized logging and monitoring |

## Verification

### 1. Check Service Binding
```bash
cf services
# Should show cloud-logging service bound
```

### 2. Generate Test Logs
```bash
curl "https://${app-url}/test"
# Should generate log entries
```

### 3. View Logs
```bash
cf logs ${app-name} --recent
```

### 4. Check Health Endpoint
```bash
curl "https://${app-url}/health"
# Should return JSON health status
```

## Common Issues

### Issue: Logs not appearing in Cloud Logging
**Cause:** Service not bound or wrong index pattern.
**Solution:**
1. Verify service binding
2. Wait a few minutes for log ingestion
3. Check index pattern in Cloud Logging

### Issue: Metrics not collected
**Cause:** OTEL agent not enabled.
**Solution:** Set `OTEL_JAVAAGENT_ENABLED: true` in mtad.yaml.

### Issue: High log volume
**Solution:** Adjust log levels:
```yaml
properties:
  SET_LOGGING_LEVEL: 'ROOT: WARN, com.example: INFO'
```

## Neo to CF Monitoring Comparison

| Neo Feature | CF Equivalent |
|-------------|---------------|
| Application logs | Cloud Logging / `cf logs` |
| Availability checks | Health endpoint + external monitoring |
| JMX metrics | OpenTelemetry + Cloud Logging metrics |
| Performance tracing | Distributed tracing with OTEL |
| Alerting | Cloud Logging alerting rules |

## Next Steps

After completing this skill, your monitoring setup is complete. Consider:
- Setting up alerting rules in Cloud Logging
- Creating custom dashboards
- Implementing distributed tracing for microservices
