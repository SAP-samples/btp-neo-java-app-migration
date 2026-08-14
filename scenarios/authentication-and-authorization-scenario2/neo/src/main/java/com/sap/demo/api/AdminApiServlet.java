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
import java.util.ArrayList;
import java.util.List;

/**
 * Admin API Servlet - accessible only to users with Admin role
 * Protected by declarative security constraints in web.xml
 * Returns JSON formatted responses
 */
public class AdminApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // Get authenticated user information
        String username = request.getRemoteUser();

        // Prepare response data
        Map<String, Object> data = new HashMap<>();
        data.put("endpoint", "/api/admin/operations");
        data.put("description", "Admin operations API endpoint - Admin role required");
        data.put("accessLevel", "restricted");
        data.put("requiredRoles", new String[]{"Admin"});
        data.put("timestamp", System.currentTimeMillis());
        
        // Administrator information
        Map<String, Object> adminInfo = new HashMap<>();
        adminInfo.put("username", username);
        adminInfo.put("role", "Admin");
        adminInfo.put("authenticated", true);
        adminInfo.put("accessTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        data.put("administrator", adminInfo);
        
        // System statistics (mock data)
        Map<String, Object> systemStats = new HashMap<>();
        systemStats.put("totalUsers", 156);
        systemStats.put("activeUsers", 42);
        systemStats.put("totalSessions", 38);
        systemStats.put("systemUptime", "15 days, 7 hours");
        systemStats.put("cpuUsage", "23%");
        systemStats.put("memoryUsage", "512 MB / 2 GB");
        systemStats.put("diskUsage", "45%");
        data.put("systemStatistics", systemStats);
        
        // User roles distribution (mock data)
        Map<String, Integer> rolesDistribution = new HashMap<>();
        rolesDistribution.put("Admin", 5);
        rolesDistribution.put("User", 151);
        data.put("rolesDistribution", rolesDistribution);
        
        // Recent activities (mock data)
        List<Map<String, String>> recentActivities = new ArrayList<>();
        
        Map<String, String> activity1 = new HashMap<>();
        activity1.put("timestamp", "2026-02-11 19:15:30");
        activity1.put("user", "john.doe");
        activity1.put("action", "Login");
        activity1.put("status", "Success");
        recentActivities.add(activity1);
        
        Map<String, String> activity2 = new HashMap<>();
        activity2.put("timestamp", "2026-02-11 19:10:15");
        activity2.put("user", "jane.smith");
        activity2.put("action", "Access User Dashboard");
        activity2.put("status", "Success");
        recentActivities.add(activity2);
        
        Map<String, String> activity3 = new HashMap<>();
        activity3.put("timestamp", "2026-02-11 19:05:42");
        activity3.put("user", "admin");
        activity3.put("action", "Access Admin Console");
        activity3.put("status", "Success");
        recentActivities.add(activity3);
        
        Map<String, String> activity4 = new HashMap<>();
        activity4.put("timestamp", "2026-02-11 18:58:20");
        activity4.put("user", "guest");
        activity4.put("action", "Failed Login Attempt");
        activity4.put("status", "Failed");
        recentActivities.add(activity4);
        
        data.put("recentActivities", recentActivities);
        
        // Available admin operations
        List<Map<String, String>> operations = new ArrayList<>();
        
        Map<String, String> op1 = new HashMap<>();
        op1.put("operation", "User Management");
        op1.put("endpoint", "/api/admin/users");
        op1.put("methods", "GET, POST, PUT, DELETE");
        op1.put("description", "Manage user accounts and permissions");
        operations.add(op1);

        data.put("availableOperations", operations);
        
        // System health
        Map<String, Object> systemHealth = new HashMap<>();
        systemHealth.put("status", "healthy");
        systemHealth.put("database", "connected");
        systemHealth.put("authentication", "operational");
        systemHealth.put("lastHealthCheck", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        data.put("systemHealth", systemHealth);
        
        // Send JSON response
        JsonResponseUtil.sendSuccessResponse(response, "Admin operations data retrieved successfully", data);
    }
}