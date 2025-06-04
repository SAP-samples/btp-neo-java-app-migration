package com.sap.cloud.sample.authentication;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet implementing logout for SAP BTP Neo Environment.
 */
public class AuthenticationLogoutServlet extends HttpServlet {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationLogoutServlet.class);

    private LogoutService logoutService = new LogoutService();

    /** {@inheritDoc} */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            // Check if a user is logged in
            if (request.getUserPrincipal() != null) {
                // Logout user
                this.logoutService.logout(request, response);

                // Confirm logout
                response.getWriter().println(
                        "<p>User with id " + request.getUserPrincipal().getName() + " logged out</p>");
            }

            // Show information that no user is logged in
            response.getWriter().println("<p>No user logged in anymore</p>");

            // Render link to protected area
            response.getWriter().println("<p><a href=\"/protected\">Access Protected Area Again</a></p>");
        } catch (Exception e) {
            // Logout operation failed
            response.getWriter().println("Logout operation failed with reason: " + e.getMessage());
            LOGGER.error("Logout operation failed", e);
        }
    }
}
