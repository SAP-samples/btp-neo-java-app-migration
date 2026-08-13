package com.sap.demo.api;

import com.sap.demo.util.JsonResponseUtil;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * User API Servlet - accessible to authenticated users with User or Admin role
 * Protected by declarative security constraints in web.xml
 * Returns JSON formatted responses
 */
public class UserApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // Get authenticated user information
        String username = request.getRemoteUser();
        boolean isAdmin = request.isUserInRole("Admin");
        boolean isUser = request.isUserInRole("User");
        
        // Prepare response data
        Map<String, Object> data = new HashMap<>();
        data.put("endpoint", "/api/user/data");
        data.put("description", "User data API endpoint - authentication required");
        data.put("accessLevel", "protected");
        data.put("requiredeitherOfRoles", new String[]{"User", "Admin"});
        data.put("timestamp", System.currentTimeMillis());
        
        // User information
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("username", username);
        userInfo.put("authenticated", true);
        userInfo.put("loginTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        
        // User roles
        Map<String, Boolean> roles = new HashMap<>();
        roles.put("User", isUser);
        roles.put("Admin", isAdmin);
        userInfo.put("roles", roles);
        
        data.put("user", userInfo);
        
        // User statistics (mock data)
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalLogins", 42);
        statistics.put("lastLoginDate", "2026-02-10");
        statistics.put("accountCreated", "2026-01-15");
        statistics.put("profileCompleteness", 85);
        data.put("statistics", statistics);
        
        // User preferences (mock data)
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("language", "en");
        preferences.put("timezone", "UTC+2");
        preferences.put("theme", "light");
        preferences.put("notifications", true);
        data.put("preferences", preferences);
        
        // Available actions for this user
        Map<String, Object> actions = new HashMap<>();
        actions.put("viewProfile", true);
        actions.put("editProfile", true);
        actions.put("viewReports", true);
        actions.put("exportData", true);
        
        if (isAdmin) {
            actions.put("accessAdminConsole", true);
            actions.put("manageUsers", true);
            actions.put("viewSystemLogs", true);
        } else {
            actions.put("accessAdminConsole", false);
            actions.put("manageUsers", false);
            actions.put("viewSystemLogs", false);
        }
        
        data.put("availableActions", actions);
        
        // Send JSON response
        JsonResponseUtil.sendSuccessResponse(response, "User data retrieved successfully", data);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String username = request.getRemoteUser();
        
        Map<String, Object> data = new HashMap<>();
        data.put("message", "User data update endpoint");
        data.put("username", username);
        data.put("note", "This is a demo endpoint. Actual update functionality would be implemented here.");
        
        JsonResponseUtil.sendSuccessResponse(response, "POST request received", data);
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