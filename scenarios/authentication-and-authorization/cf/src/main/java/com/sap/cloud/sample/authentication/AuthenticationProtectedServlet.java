package com.sap.cloud.sample.authentication;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.sap.cloud.security.token.SecurityContext;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet allowing access only to authenticated users.
 */
public class AuthenticationProtectedServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationProtectedServlet.class);

    /** {@inheritDoc} */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            LOGGER.info("Trying to acccess protected area");
            Token token = SecurityContext.getToken();

            if(token == null) {
                LOGGER.error("Access token is null");
                throw new ServletException("Access token is null");
            }
            // Show name of logged in user
            response.getWriter().println("<p>Welcome " + getUserAttributes(token) + "</p>");
            LOGGER.info(token.getPrincipal().getName() + " accessed protected area");

            // Render link to logout
            response.getWriter().println("<p><a href=\"/logout\">Logout Now</a></p>");
        } catch (Exception e) {
            // Login operation failed
            response.getWriter().println("Protected operation failed with reason: " + e.getMessage());
            LOGGER.error("Protected operation failed", e);
        }
    }

    /**
     * Get name and e-mail user attributes and return them as condensed string.
     */
    private String getUserAttributes(Token token) {
         // Extract and return username and e-mail address if present
        String firstName = token.getClaimAsString(TokenClaims.GIVEN_NAME);
        String lastName = token.getClaimAsString(TokenClaims.FAMILY_NAME);
        String eMail = token.getClaimAsString(TokenClaims.EMAIL);
        return (firstName != null && lastName != null ? firstName + " " + lastName + " [" + token.getPrincipal().getName() + "]"
                : token.getPrincipal().getName()) + (eMail != null ? " (" + eMail + ")" : "");
    }
}
