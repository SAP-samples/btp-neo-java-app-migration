package com.sap.demo.servlet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class UserServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");

        String username = request.getRemoteUser();
        boolean isAdmin = request.isUserInRole("Admin");
        boolean isUser = request.isUserInRole("User");
        String contextPath = request.getContextPath();

        PrintWriter out = response.getWriter();

        out.println("<!DOCTYPE html>");
        out.println("<html lang='en'>");
        out.println("<head>");
        out.println("    <meta charset='UTF-8'>");
        out.println("    <meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        out.println("    <title>User Dashboard - SAP BTP Neo Demo</title>");
        out.println("    <link rel='stylesheet' href='" + contextPath + "/css/style.css'>");
        out.println("</head>");
        out.println("<body>");
        out.println("    <div class='container'>");
        out.println("        <header>");
        out.println("            <h1>User Dashboard</h1>");
        out.println("            <p class='subtitle'>Protected Area - User Role Required</p>");
        out.println("        </header>");

        out.println("        <nav class='breadcrumb'>");
        out.println("            <a href='" + contextPath + "/welcome'>Home</a> > <span>User Dashboard</span>");
        out.println("        </nav>");

        out.println("        <main>");

        out.println("            <div class='info-box success'>");
        out.println("                <h2>✓ Authentication Successful</h2>");
        out.println("                <div class='user-details'>");
        out.println("                    <p><strong>Username:</strong> " + escapeHtml(username) + "</p>");
        out.println("                    <p><strong>Access Time:</strong> " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "</p>");
        out.println("                    <p><strong>Your Roles:</strong> ");
        if (isAdmin) {
            out.println("                        <span class='badge badge-admin'>Admin</span>");
        }
        if (isUser) {
            out.println("                        <span class='badge badge-user'>User</span>");
        }
        out.println("                    </p>");
        out.println("                </div>");
        out.println("            </div>");

        out.println("            <div class='dashboard-section'>");
        out.println("                <h3>User Features</h3>");
        out.println("                <div class='feature-grid'>");

        out.println("                    <div class='feature-card'>");
        out.println("                        <h4>📊 View Data</h4>");
        out.println("                        <p>Access your personal data and statistics</p>");
        out.println("                        <a href='" + contextPath + "/api/user/data' class='btn btn-primary' target='_blank'>View Data API</a>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card'>");
        out.println("                        <h4>📝 User Profile</h4>");
        out.println("                        <p>Manage your profile settings</p>");
        out.println("                        <button class='btn btn-secondary' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card'>");
        out.println("                        <h4>📈 Reports</h4>");
        out.println("                        <p>Generate and view your reports</p>");
        out.println("                        <button class='btn btn-secondary' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                </div>");
        out.println("            </div>");

        out.println("            <div class='info-box info'>");
        out.println("                <h3>🔒 Security Information</h3>");
        out.println("                <ul>");
        out.println("                    <li>This page is protected by declarative security constraints</li>");
        out.println("                    <li>Access requires User or Admin role</li>");
        out.println("                    <li>Authentication method: SAML2</li>");
        out.println("                    <li>Session timeout: 30 minutes</li>");
        out.println("                </ul>");
        out.println("            </div>");

        out.println("            <div class='navigation-section'>");
        out.println("                <h3>Navigation</h3>");
        out.println("                <div class='nav-buttons'>");
        out.println("                    <a href='" + contextPath + "/welcome' class='btn btn-outline'>← Back to Home</a>");

        if (isAdmin) {
            out.println("                    <a href='" + contextPath + "/admin/console' class='btn btn-primary'>Admin Console →</a>");
        }

        out.println("                    <a href='" + contextPath + "/logout' class='btn btn-danger'>Logout</a>");
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

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }
}
