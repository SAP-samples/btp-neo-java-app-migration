package com.sap.demo.servlet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AdminServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");

        String username = request.getRemoteUser();
        String contextPath = request.getContextPath();

        PrintWriter out = response.getWriter();

        out.println("<!DOCTYPE html>");
        out.println("<html lang='en'>");
        out.println("<head>");
        out.println("    <meta charset='UTF-8'>");
        out.println("    <meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        out.println("    <title>Admin Console - SAP BTP Neo Demo</title>");
        out.println("    <link rel='stylesheet' href='" + contextPath + "/css/style.css'>");
        out.println("</head>");
        out.println("<body>");
        out.println("    <div class='container'>");
        out.println("        <header>");
        out.println("            <h1>🔐 Admin Console</h1>");
        out.println("            <p class='subtitle'>Protected Area - Admin Role Required</p>");
        out.println("        </header>");

        out.println("        <nav class='breadcrumb'>");
        out.println("            <a href='" + contextPath + "/welcome'>Home</a> > <span>Admin Console</span>");
        out.println("        </nav>");

        out.println("        <main>");

        out.println("            <div class='info-box admin-box'>");
        out.println("                <h2>✓ Administrator Access Granted</h2>");
        out.println("                <div class='user-details'>");
        out.println("                    <p><strong>Administrator:</strong> " + escapeHtml(username) + "</p>");
        out.println("                    <p><strong>Access Time:</strong> " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "</p>");
        out.println("                    <p><strong>Role:</strong> <span class='badge badge-admin'>Admin</span></p>");
        out.println("                    <p><strong>Access Level:</strong> Full System Access</p>");
        out.println("                </div>");
        out.println("            </div>");

        out.println("            <div class='dashboard-section'>");
        out.println("                <h3>Administrative Functions</h3>");
        out.println("                <div class='feature-grid'>");

        out.println("                    <div class='feature-card admin-card'>");
        out.println("                        <h4>👥 User Management</h4>");
        out.println("                        <p>Manage users, roles, and permissions</p>");
        out.println("                        <button class='btn btn-admin' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card admin-card'>");
        out.println("                        <h4>⚙️ System Configuration</h4>");
        out.println("                        <p>Configure application settings</p>");
        out.println("                        <button class='btn btn-admin' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card admin-card'>");
        out.println("                        <h4>📊 System Monitoring</h4>");
        out.println("                        <p>Monitor system health and performance</p>");
        out.println("                        <button class='btn btn-admin' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card admin-card'>");
        out.println("                        <h4>🔍 Audit Logs</h4>");
        out.println("                        <p>View system audit logs and activities</p>");
        out.println("                        <button class='btn btn-admin' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card admin-card'>");
        out.println("                        <h4>🔐 Security Settings</h4>");
        out.println("                        <p>Manage security policies and rules</p>");
        out.println("                        <button class='btn btn-admin' disabled>Coming Soon</button>");
        out.println("                    </div>");

        out.println("                    <div class='feature-card admin-card'>");
        out.println("                        <h4>🚀 Admin API</h4>");
        out.println("                        <p>Access administrative REST API</p>");
        out.println("                        <a href='" + contextPath + "/api/admin/operations' class='btn btn-admin' target='_blank'>View API</a>");
        out.println("                    </div>");

        out.println("                </div>");
        out.println("            </div>");

        out.println("            <div class='dashboard-section'>");
        out.println("                <h3>System Information</h3>");
        out.println("                <div class='system-info'>");
        out.println("                    <table class='info-table'>");
        out.println("                        <tr>");
        out.println("                            <td><strong>Server Info:</strong></td>");
        out.println("                            <td>" + request.getServletContext().getServerInfo() + "</td>");
        out.println("                        </tr>");
        out.println("                        <tr>");
        out.println("                            <td><strong>Servlet Version:</strong></td>");
        out.println("                            <td>" + request.getServletContext().getMajorVersion() + "." + request.getServletContext().getMinorVersion() + "</td>");
        out.println("                        </tr>");
        out.println("                        <tr>");
        out.println("                            <td><strong>Context Path:</strong></td>");
        out.println("                            <td>" + contextPath + "</td>");
        out.println("                        </tr>");
        out.println("                        <tr>");
        out.println("                            <td><strong>Session Timeout:</strong></td>");
        out.println("                            <td>" + request.getSession().getMaxInactiveInterval() / 60 + " minutes</td>");
        out.println("                        </tr>");
        out.println("                    </table>");
        out.println("                </div>");
        out.println("            </div>");

        out.println("            <div class='info-box warning'>");
        out.println("                <h3>⚠️ Security Notice</h3>");
        out.println("                <ul>");
        out.println("                    <li>This is a restricted administrative area</li>");
        out.println("                    <li>Access is limited to users with Admin role only</li>");
        out.println("                    <li>All administrative actions are logged</li>");
        out.println("                    <li>Unauthorized access attempts will be reported</li>");
        out.println("                </ul>");
        out.println("            </div>");

        out.println("            <div class='navigation-section'>");
        out.println("                <h3>Navigation</h3>");
        out.println("                <div class='nav-buttons'>");
        out.println("                    <a href='" + contextPath + "/welcome' class='btn btn-outline'>← Back to Home</a>");
        out.println("                    <a href='" + contextPath + "/user/dashboard' class='btn btn-primary'>User Dashboard</a>");
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
