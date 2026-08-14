# SAP BTP Neo - Declarative Authentication & Authorization Demo

A comprehensive Java web application demonstrating declarative authentication and authorization on SAP BTP Neo platform using role-based access control (RBAC).

## 📋 Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Project Structure](#project-structure)
- [Building the Application](#building-the-application)
- [Deployment](#deployment)
- [Configuration](#configuration)
- [Testing](#testing)
- [API Documentation](#api-documentation)
- [Security Model](#security-model)
- [Troubleshooting](#troubleshooting)

## 🎯 Overview

This application demonstrates how to implement declarative security in a Java EE web application deployed on SAP BTP Neo. It showcases:

- **Declarative Authentication**: FORM-based authentication configured in `web.xml`
- **Declarative Authorization**: Role-based access control using security constraints
- **Web UI**: Interactive pages for different user roles
- **REST APIs**: JSON-based endpoints with role-based protection

## ✨ Features

### Security Features
- ✅ FORM-based authentication
- ✅ Role-based access control (Admin, User)
- ✅ Declarative security constraints in `web.xml`
- ✅ Session management (30-minute timeout)
- ✅ Protected resources by role

### Application Features
- 🌐 Public welcome page (no authentication required)
- 👤 User dashboard (User or Admin role required)
- 🔐 Admin console (Admin role only)
- 🔌 REST API endpoints with JSON responses
- 🎨 Responsive web design

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    SAP BTP Neo Platform                  │
│  ┌───────────────────────────────────────────────────┐  │
│  │        SAP BTP Neo Identity Provider (SAML/IdP)    │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │              web.xml Security Constraints         │  │
│  │  • /admin/* → Admin role                          │  │
│  │  • /user/*  → User or Admin role                  │  │
│  │  • /api/admin/* → Admin role                      │  │
│  │  • /api/user/*  → User or Admin role              │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │              Application Servlets                 │  │
│  │  • WelcomeServlet (Public)                        │  │
│  │  • UserServlet (Protected)                        │  │
│  │  • AdminServlet (Protected)                       │  │
│  │  • REST API Servlets                              │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 📦 Prerequisites

- **Java Development Kit (JDK)**: 1.8 or higher
- **Apache Maven**: 3.6 or higher
- **SAP BTP Neo Account**: With appropriate permissions
- **SAP BTP Neo SDK**: For local testing (optional)
- **Git**: For version control

## 📁 Project Structure

```
neo-authn-authz-demo/
├── pom.xml                          # Maven configuration
├── README.md                        # This file
├── README.md                        # This file
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── sap/
        │           └── demo/
        │               ├── api/                    # REST API servlets
        │               │   ├── AdminApiServlet.java
        │               │   ├── PublicApiServlet.java
        │               │   └── UserApiServlet.java
        │               ├── servlet/                # Web UI servlets
        │               │   ├── AdminServlet.java
        │               │   ├── UserServlet.java
        │               │   └── WelcomeServlet.java
        │               └── util/                   # Utility classes
        │                   └── JsonResponseUtil.java
        └── webapp/
            ├── WEB-INF/
            │   └── web.xml                # Security configuration
            ├── css/
            │   └── style.css              # Stylesheet
            └── index.html                 # Landing page
```

## 🔨 Building the Application

### 1. Clone the Repository

```bash
git clone <repository-url>
cd neo-authn-authz-demo
```

### 2. Build with Maven

```bash
mvn clean package
```

This will create a WAR file in the `target/` directory: `neo-authn-authz-demo.war`

### 3. Verify Build

```bash
ls -l target/*.war
```

## 🚀 Deployment

### Option 1: Deploy via SAP BTP Neo Console

1. Log in to SAP BTP Neo Cockpit
2. Navigate to your subaccount
3. Go to **Applications** → **Java Applications**
4. Click **Deploy Application**
5. Upload the WAR file from `target/neo-authn-authz-demo.war`
6. Configure application settings:
   - **Application Name**: `neo-authn-authz-demo`
   - **Runtime**: Java Web Tomcat 8
   - **JVM Version**: JRE 8
7. Click **Deploy**

### Option 2: Deploy via Neo Console Client

```bash
# Set Neo SDK path
export NEO_SDK_HOME=/path/to/neo-sdk

# Deploy application
$NEO_SDK_HOME/tools/neo.sh deploy \
  --host hana.ondemand.com \
  --account <your-account> \
  --application neo-authn-authz-demo \
  --user <your-user> \
  --source target/neo-authn-authz-demo.war
```

### Option 3: Deploy via Maven Plugin

Add to `pom.xml` and run:

```bash
mvn clean package neo-java-web:deploy
```

## ⚙️ Configuration

### 1. Configure Roles in SAP BTP Neo

After deployment, configure roles in the SAP BTP Neo Cockpit:

1. Navigate to **Applications** → **Java Applications** → **neo-authn-authz-demo**
2. Go to **Security** → **Roles**
3. Create/verify roles:
   - **Admin**: Full administrative access
   - **User**: Standard user access

### 2. Assign Roles to Users

1. Go to **Security** → **Role Assignments**
2. Click **Assign**
3. Select users and assign appropriate roles:
   - Assign **Admin** role to administrators
   - Assign **User** role to regular users

### 3. Start the Application

1. In the application overview, click **Start**
2. Wait for the application to start (status: Started)
3. Note the application URL

## 🧪 Testing

### Access the Application

1. **Public Area** (No authentication required):
   ```
   https://<app-url>/
   https://<app-url>/welcome
   ```

2. **User Area** (User or Admin role required):
   ```
   https://<app-url>/user/dashboard
   ```

3. **Admin Area** (Admin role only):
   ```
   https://<app-url>/admin/console
   ```

### Test Scenarios

#### Scenario 1: Public Access
- Navigate to `/welcome`
- Should display welcome page without login
- No authentication required

#### Scenario 2: User Access
- Navigate to `/user/dashboard`
- Should redirect to login page
- Login with User role credentials
- Should display user dashboard

#### Scenario 3: Admin Access
- Navigate to `/admin/console`
- Should redirect to login page
- Login with Admin role credentials
- Should display admin console

#### Scenario 4: Unauthorized Access
- Login with User role
- Try to access `/admin/console`
- Should receive 403 Forbidden error

### Testing REST APIs

#### Public API (No authentication)
```bash
curl https://<app-url>/api/public/info
```

#### User API (Authentication required)
```bash
curl -u username:password https://<app-url>/api/user/data
```

#### Admin API (Admin role required)
```bash
curl -u admin:password https://<app-url>/api/admin/operations
```

## 📚 API Documentation

### Public Endpoints

#### GET /api/public/info
Returns public application information.

**Authentication**: None required

**Response**:
```json
{
  "status": "success",
  "message": "Public API information retrieved successfully",
  "timestamp": 1707677400000,
  "data": {
    "endpoint": "/api/public/info",
    "description": "Public API endpoint - no authentication required",
    "version": "1.0.0",
    "application": {
      "name": "SAP BTP Neo Authentication & Authorization Demo",
      "type": "Java Web Application"
    }
  }
}
```

### Protected Endpoints (User Role)

#### GET /api/user/data
Returns user-specific data and statistics.

**Authentication**: Required (User or Admin role)

**Response**:
```json
{
  "status": "success",
  "message": "User data retrieved successfully",
  "timestamp": 1707677400000,
  "data": {
    "user": {
      "username": "john.doe",
      "authenticated": true,
      "roles": {
        "User": true,
        "Admin": false
      }
    },
    "statistics": {
      "totalLogins": 42,
      "lastLoginDate": "2026-02-10"
    }
  }
}
```

### Protected Endpoints (Admin Role)

#### GET /api/admin/operations
Returns administrative operations and system statistics.

**Authentication**: Required (Admin role only)

**Response**:
```json
{
  "status": "success",
  "message": "Admin operations data retrieved successfully",
  "timestamp": 1707677400000,
  "data": {
    "administrator": {
      "username": "admin",
      "role": "Admin"
    },
    "systemStatistics": {
      "totalUsers": 156,
      "activeUsers": 42,
      "systemUptime": "15 days, 7 hours"
    }
  }
}
```

## 🔒 Security Model

### Authentication Method
- **Type**: SAML2 (SSO handled by the SAP BTP Neo platform)
- **Session Timeout**: 30 minutes

### Authorization Roles

| Role | Description | Access Level |
|------|-------------|--------------|
| **Admin** | Administrator with full access | All areas including admin console |
| **User** | Regular user with limited access | User dashboard and user APIs |
| **Public** | Unauthenticated users | Public pages only |

### Security Constraints

| URL Pattern | Required Role | HTTP Methods |
|-------------|---------------|--------------|
| `/admin/*` | Admin | GET, POST |
| `/user/*` | User, Admin | GET, POST |
| `/api/admin/*` | Admin | GET, POST, PUT, DELETE |
| `/api/user/*` | User, Admin | GET, POST |
| `/api/public/*` | None | GET |
| `/`, `/welcome` | None | GET |

## 🔧 Troubleshooting

### Issue: Login fails with valid credentials

**Solution**:
1. Verify user exists in SAP BTP Neo
2. Check role assignments in cockpit
3. Ensure application is started
4. Clear browser cache and cookies

### Issue: 403 Forbidden error

**Solution**:
1. Verify user has required role
2. Check security constraints in `web.xml`
3. Review role assignments in cockpit

### Issue: Application won't start

**Solution**:
1. Check application logs in cockpit
2. Verify WAR file is valid
3. Ensure sufficient resources allocated
4. Check for port conflicts

### Issue: REST API returns 401 Unauthorized

**Solution**:
1. Ensure authentication credentials are provided
2. Verify user has required role
3. Check if session has expired
4. Use correct authentication header format

## 📝 Development Notes

### Adding New Protected Resources

1. Add servlet/resource to application
2. Update `web.xml` with security constraint:
```xml
<security-constraint>
    <web-resource-collection>
        <web-resource-name>New Resource</web-resource-name>
        <url-pattern>/new-resource/*</url-pattern>
    </web-resource-collection>
    <auth-constraint>
        <role-name>RequiredRole</role-name>
    </auth-constraint>
</security-constraint>
```

### Adding New Roles

1. Define role in `web.xml`:
```xml
<security-role>
    <role-name>NewRole</role-name>
</security-role>
```

2. Create role in SAP BTP Neo cockpit
3. Assign role to users

## 📄 License

This is a demonstration application for educational purposes.

## 👥 Support

For issues or questions:
1. Check the troubleshooting section
2. Review SAP BTP Neo documentation
3. Contact your SAP administrator

## 🔗 Useful Links

- [SAP BTP Neo Documentation](https://help.sap.com/viewer/product/SAP_CP_NEO)
- [Java EE Security](https://docs.oracle.com/javaee/7/tutorial/security-intro.htm)
- [Maven Documentation](https://maven.apache.org/guides/)

---

**Version**: 1.0.0  
**Last Updated**: 2026-02-11