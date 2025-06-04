# Monitoring Applications

## Table of Contents

- [Prerequisites](#prerequisites)
- [Application Logs](#application-logs)
  - [Overview](#overview)
  - [Dynamic Reconfiguration of the Application Loggers](#dynamic-reconfiguration-of-the-application-loggers)
- [Application Metrics](#application-metrics)
  <!---- [Resource Consumption Metrics](#resource-consumption-metrics)-->
  - [Custom JMX-Based Metrics](#custom-jmx-based-metrics)
  - [Availability Check Metrics](#availability-check-metrics)
  - [JMX Console](#jmx-console)
  - [Profiling](#profiling)
- [Additional Scenarios](#additional-scenarios)

## Prerequisites
- Ensure that you have set up a `Telemetry backend`. Neo-compatible observability for the Cloud Foundry environment relies on the [SAP Cloud Logging service | SAP Help Portal](https://help.sap.com/docs/cloud-logging) as a managed telemetry storage solution.

  You should create an instance of the SAP Cloud Logging service in advance with the respective service configuration:
    - For metric ingestion, ensure that the OTLP ingestion protocol is enabled beforehand. See [Ingest via OpenTelemetry API Endpoint | SAP Help Portal](https://help.sap.com/docs/cloud-logging/cloud-logging/ingest-via-opentelemetry-api-endpoint).
- Once you have properly configured and created the SAP Cloud Logging service instance, you should perform the respective binding operations described in [Ingest from Cloud Foundry Runtime | SAP Help Portal](https://help.sap.com/docs/cloud-logging/cloud-logging/ingest-via-cloud-foundry-runtime).

  In summary, here is the list of commands you need to run:
  1. To find SAP Cloud Logging service instance, list the service instances:
      ```
      cf services
      ```
  2. Create a service key without binding it to any applications:
      ```
      cf create-service-key <service_instance_name> <service_key_name>
      ```
  3. For syslog logs ingestion, extract ingest-endpoint, ingest-username, and ingest-password:
      ```
      cf service-key <service_instance_name> <service_key_name>
      ```
  4. As described in [Ingest via OpenTelemetry API Endpoint | SAP Help Portal](https://help.sap.com/docs/cloud-logging/cloud-logging/ingest-via-opentelemetry-api-endpoint), extract the OTLP ingestion url and credentials:
      ```
      cf service-key <service_instance_name> <service_key_name> \
      | tail -n +2 \
      | jq '.credentials | {"ingest-otlp-endpoint":."ingest-otlp-endpoint", "ingest-otlp-cert":."ingest-otlp-cert", "ingest-otlp-key":."ingest-otlp-key", "server-ca":."server-ca"}' \
      > ups.json
      ```
  5. Create a user-provided service using the following template containing the values from the previous steps:
      ```
      cf create-user-provided-service <service_instance_name> -p ups.json -l https://<ingest-username>:<ingest-password>@<ingest-endpoint>/cfsyslog -t "Cloud Logging"
      ```
  6. Bind the user-provided service to the application:
      ```
      cf bind-service <app_name> <service_instance_name>
      ```
  7. Restage the application once the binding is ready:
      ```
      cf restage <app_name>
      ```

## Application Logs
### Overview
Once properly configured, the generated application log entries are streamed into the SAP Cloud Logging service instance.
The types of logs transferred to the SAP Cloud Logging service are `application/trace` and `http access`.

Log entries are labeled with the respective attributes depending on their origin (organization, space, application name, etc.). This way, end users can analyze application logs while querying the configured Telemetry backend.
An example combination of log query attributes:
```
@timestamp                    # timestamp of the log entry
msg                           # log message
app_name                      # application's name
organization_name             # application's organization name
space_name                    # application's space name

type                          # notes the type of the log entry
                              # respective log types are "log" and "request"
                              # "log" - stands for application/trace logs
                              # "request" - stands for http access logs
```
The ingested logs can be analyzed in the SAP Cloud Logging service based on the `logs-cfsyslog-*` index pattern.

### Dynamic Reconfiguration of the Application Loggers
The troubleshooting of an issue is aided by the possibility to change the severity of the application loggers dynamically. Restarting the application is not required, thus no state is lost.

To change the default loggers configuration of your application, you need to interact with the operations of a specific Logging MBean (ObjectName - `com.sap.js:name=Logging,type=Logging`) exposed by the application.

The MBean provides the following capabilities:
- Operation: `listLoggerNames` - lists currently available/loaded logger names
- Operation: `listLoggerNamesAndLevels` - lists currently available/loaded logger names with their respective severity levels
- Operation: `getLoggerLevel` - gets the current severity of a logger
- Operation: `setLoggerLevel` - sets the severity of a logger

To change the severity of the logger or loggers, you need to:
1. Open an SSH tunnel to the application's MBean server:
```
cf ssh <app_name> -N -T -L 8502:127.0.0.1:8502
```
2. Open the [jConsole | openjdk.org](https://openjdk.org/tools/svc/jconsole/) locally and connect to the MBean server using "Remote Process" connection at `localhost:8502`.
3. Interact with the `com.sap.js:name=Logging,type=Logging` MBean to change the severity of the desired loggers.

[Back to Table of Contents](#table-of-contents)

## Application Metrics
<!---### Resource Consumption Metrics
TBD - It should be documented once SAP Cloud logging service supports container metrics.-->

### Custom JMX-Based Metrics
You can collect JMX-based metrics make specific insights for an application available later for analysis.

You can evaluate and collect custom JMX-based metrics with the help of [OpenTelemetry Java Agent | opentelemetry.io](https://opentelemetry.io/docs/zero-code/java/agent/), and the [JMX Metric Insight | github.com](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/jmx-metrics/javaagent) part of it, so you need to get familiar with its configuration options.

To make the collection of custom JMX-based metric possible, provide the `JMX_CHECKS_CONFIG_FILE` environment variable with the path to the custom JMX checks configuration file.
Prepare in advance the configuration file containing the list of JMX checks and metrics that you want to collect.

Here is an example of the custom JMX checks configuration file:
```
---
rules:
  - bean: java.lang:type=Threading
    mapping:
      ThreadCount:
        metric: custom.jvm.thread.count
        type: updowncounter
        desc: The current number of threads
        unit: "threads
```

Extracted metrics are labeled with the origin attributes to make the analysis easier. A distinguishing attribute of JMX-based metrics is the service exposing those metrics:
```
@timestamp                                   # timestamp of the log entry
name                                         # metric name
resource.attributes.sap@cf@app_name          # application's name
resource.attributes.sap@cf@org_name          # application's organization name
resource.attributes.sap@cf@space_name        # application's space name
```
You can analyze the ingested metrics in the SAP Cloud Logging service based on the `metrics-otel-v1-*` index pattern.

### Availability Check Metrics
You can track the availability of an application with a custom http-based monitor.

To do so, you should implement and define the http endpoint/uri and check whether the application is functional.
<!--A user? Who's implementing and defining the endpoint? The reader of this document, right?-->

In the Cloud Foundry environment, there is no alternative to the availability check feature of the Neo environment. A potential mitigation for the lack of an availability check service is to:

1. Deploy [OpenTelemetry Collector | opentelemetry.io](https://opentelemetry.io/docs/collector/) an application in your Cloud Foundry space and configure it properly.

2. Configure the application properly to scrape the availability data (potentially with [synthetic testing | opentelemetry.io](https://opentelemetry.io/blog/2023/synthetic-testing/)) in form of a metric, and ingest the data in the SAP Cloud Logging service instance.

### JMX Console
To gain more insights into an application, you can connect to the MBean server of the JVM running the Java application via JMXConsole.
To do so:
1. Open an SSH tunnel to the application's MBean server:
    ```
    cf ssh <app_name> -N -T -L 8502:127.0.0.1:8502
    ```

2. Open the [jConsole | openjdk.org](https://openjdk.org/tools/svc/jconsole/) locally and connect to the MBean server using "Remote Process" connection at `localhost:8502`.

3. Interact with the MBeans exposed by the application.

### Profiling
For troubleshooting purposes, you can profile your application and understand its performance characteristics and resource utilization.

To profile the application:

1. Open an SSH tunnel to the application's MBean server:
    ```
    cf ssh <app_name> -N -T -L 8502:127.0.0.1:8502
    ```

2. Use any profiling remote JMX/RMI connection cable Java profiling tool. For example, you can use [jConsole | openjdk.org](https://openjdk.org/tools/svc/jconsole/) and [VisualVM | visualvm.github.io](https://visualvm.github.io/) to analyze heap/memory usage and thread/CPU utilization.

[Back to Table of Contents](#table-of-contents)

## [Additional Scenarios](../../README.md#7-additional-scenarios)
