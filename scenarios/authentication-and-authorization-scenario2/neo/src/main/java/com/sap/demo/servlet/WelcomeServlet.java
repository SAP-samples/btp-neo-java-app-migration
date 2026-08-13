package com.sap.demo.servlet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Public Welcome Servlet - accessible to all users without authentication
 */
public class WelcomeServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        
        PrintWriter out = response.getWriter();
        
        // Get context path for proper URL generation
        String contextPath = request.getContextPath();
        
        // Get user information if authenticated
        String username = request.getRemoteUser();
        boolean isAuthenticated = (username != null);
        boolean isAdmin = isAuthenticated && request.isUserInRole("Admin");
        boolean isUser = isAuthenticated && request.isUserInRole("User");
        
        out.println("<!DOCTYPE html>");
        out.println("<html lang='en'>");
        out.println("<head>");
        out.println("    <meta charset='UTF-8'>");
        out.println("    <meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        out.println("    <title>Welcome - SAP BTP Neo Demo</title>");
        out.println("    <link rel='stylesheet' href='" + contextPath + "/css/style.css'>");
        out.println("</head>");
        out.println("<body>");
        out.println("    <div class='container'>");
        out.println("        <header>");
        out.println("            <h1>SAP BTP Neo Authentication & Authorization Demo</h1>");
        out.println("            <p class='subtitle'>Declarative Security with Role-Based Access Control</p>");
        out.println("        </header>");
        
        out.println("        <main>");
        out.println("            <div class='welcome-box'>");
        out.println("                <h2>Welcome to the Public Area</h2>");
        
        if (isAuthenticated) {
            out.println("                <div class='user-info'>");
            out.println("                    <p><strong>Logged in as:</strong> " + username + "</p>");
            out.println("                    <p><strong>Roles:</strong> ");
            if (isAdmin) {
                out.println("                        <span class='badge badge-admin'>Admin</span>");
            }
            if (isUser) {
                out.println("                        <span class='badge badge-user'>User</span>");
            }
            out.println("                    </p>");
            out.println("                    <p style='margin-top: 15px;'><a href='" + contextPath + "/logout' class='btn btn-danger btn-sm'>Logout</a></p>");
            out.println("                </div>");
        } else {
            out.println("                <p>You are currently not authenticated. This is a public area accessible to everyone.</p>");
        }
        
        out.println("            </div>");
        
        out.println("            <div class='features'>");
        out.println("                <h3>Application Features</h3>");
        out.println("                <div class='feature-grid'>");
        
        // Public features
        out.println("                    <div class='feature-card public'>");
        out.println("                        <h4>🌐 Public Area</h4>");
        out.println("                        <p>Accessible to everyone without authentication</p>");
        out.println("                        <a href='" + contextPath + "/welcome' class='btn btn-primary'>Current Page</a>");
        out.println("                    </div>");
        
        // User area
        out.println("                    <div class='feature-card user'>");
        out.println("                        <h4>👤 User Area</h4>");
        out.println("                        <p>Requires User or Admin role</p>");
        if (isAuthenticated && (isUser || isAdmin)) {
            out.println("                        <a href='" + contextPath + "/user/dashboard' class='btn btn-success'>Access Dashboard</a>");
        } else {
            out.println("                        <a href='" + contextPath + "/user/dashboard' class='btn btn-secondary'>Login Required</a>");
        }
        out.println("                    </div>");
        
        // Admin area
        out.println("                    <div class='feature-card admin'>");
        out.println("                        <h4>🔐 Admin Area</h4>");
        out.println("                        <p>Requires Admin role only</p>");
        if (isAdmin) {
            out.println("                        <a href='" + contextPath + "/admin/console' class='btn btn-success'>Access Console</a>");
        } else {
            out.println("                        <a href='" + contextPath + "/admin/console' class='btn btn-secondary'>Admin Only</a>");
        }
        out.println("                    </div>");
        
        out.println("                </div>");
        out.println("            </div>");
        
        // REST API section
        out.println("            <div class='api-section'>");
        out.println("                <h3>REST API Endpoints</h3>");
        out.println("                <div class='api-grid'>");
        
        out.println("                    <div class='api-card'>");
        out.println("                        <h4>Public API</h4>");
        out.println("                        <code>GET /api/public/info</code>");
        out.println("                        <p>No authentication required</p>");
        out.println("                        <a href='" + contextPath + "/api/public/info' class='btn btn-sm' target='_blank'>Test API</a>");
        out.println("                    </div>");
        
        out.println("                    <div class='api-card'>");
        out.println("                        <h4>User API</h4>");
        out.println("                        <code>GET /api/user/data</code>");
        out.println("                        <p>Requires User or Admin role</p>");
        out.println("                        <a href='" + contextPath + "/api/user/data' class='btn btn-sm' target='_blank'>Test API</a>");
        out.println("                    </div>");
        
        out.println("                    <div class='api-card'>");
        out.println("                        <h4>Admin API</h4>");
        out.println("                        <code>GET /api/admin/operations</code>");
        out.println("                        <p>Requires Admin role</p>");
        out.println("                        <a href='" + contextPath + "/api/admin/operations' class='btn btn-sm' target='_blank'>Test API</a>");
        out.println("                    </div>");

        out.println("                </div>");
        out.println("            </div>");
        
        out.println("        </main>");
        
        out.println("        <footer>");
        out.println("            <p>&copy; 2026 SAP BTP Neo Demo Application</p>");
        out.println("        </footer>");
        out.println("    </div>");
        out.println("</body>");
        out.println("</html>");
    }
}