package com.sap.demo.api;

import com.sap.demo.util.JsonResponseUtil;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Public API Servlet - accessible to all users without authentication
 * Returns JSON formatted responses
 */
public class PublicApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // Prepare response data
        Map<String, Object> data = new HashMap<>();
        data.put("endpoint", "/api/public/info");
        data.put("description", "Public API endpoint - no authentication required");
        data.put("accessLevel", "public");
        data.put("version", "1.0.0");
        data.put("timestamp", System.currentTimeMillis());
        
        // Application information
        Map<String, Object> appInfo = new HashMap<>();
        appInfo.put("name", "SAP BTP Neo Authentication & Authorization Demo");
        appInfo.put("type", "Java Web Application");
        appInfo.put("framework", "Java EE Servlets");
        appInfo.put("platform", "SAP BTP Neo");
        data.put("application", appInfo);
        
        // Available endpoints
        Map<String, Object> endpoints = new HashMap<>();
        
        Map<String, String> publicEndpoint = new HashMap<>();
        publicEndpoint.put("path", "/api/public/info");
        publicEndpoint.put("method", "GET");
        publicEndpoint.put("authentication", "none");
        publicEndpoint.put("description", "Public information endpoint");
        endpoints.put("public", publicEndpoint);
        
        Map<String, String> userEndpoint = new HashMap<>();
        userEndpoint.put("path", "/api/user/data");
        userEndpoint.put("method", "GET");
        userEndpoint.put("authentication", "required");
        userEndpoint.put("roles", "User, Admin");
        userEndpoint.put("description", "User data endpoint");
        endpoints.put("user", userEndpoint);
        
        Map<String, String> adminEndpoint = new HashMap<>();
        adminEndpoint.put("path", "/api/admin/operations");
        adminEndpoint.put("method", "GET");
        adminEndpoint.put("authentication", "required");
        adminEndpoint.put("roles", "Admin");
        adminEndpoint.put("description", "Admin operations endpoint");
        endpoints.put("admin", adminEndpoint);
        
        data.put("endpoints", endpoints);
        
        // Security information
        Map<String, Object> security = new HashMap<>();
        security.put("authenticationMethod", "SAML2");
        security.put("authorizationModel", "Role-Based Access Control (RBAC)");
        security.put("declarativeSecurity", true);
        security.put("availableRoles", new String[]{"User", "Admin"});
        data.put("security", security);
        
        // Send JSON response
        JsonResponseUtil.sendSuccessResponse(response, "Public API information retrieved successfully", data);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        JsonResponseUtil.sendErrorResponse(response, "POST method not supported for this endpoint", 
                HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        JsonResponseUtil.sendErrorResponse(response, "PUT method not supported for this endpoint", 
                HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        JsonResponseUtil.sendErrorResponse(response, "DELETE method not supported for this endpoint", 
                HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }
}