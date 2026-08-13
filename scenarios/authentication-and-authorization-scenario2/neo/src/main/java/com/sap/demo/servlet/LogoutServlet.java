package com.sap.demo.servlet;

import com.sap.security.auth.login.LoginContextFactory;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class LogoutServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // Recommended logout for SAP BTP Neo:
        // https://help.sap.com/docs/btp/sap-btp-neo-environment/logout
        // loginContext.logout() redirects the request to the identity provider,
        // which logs the user out and returns them to this servlet.
        if (request.getRemoteUser() != null) {
            try {
                LoginContext loginContext = LoginContextFactory.createLoginContext();
                loginContext.logout();
            } catch (LoginException e) {
                response.getWriter().println("Logout failed. Reason: " + e.getMessage());
                return;
            }
        } else {
            response.setStatus(302);
            response.setHeader("Location", request.getContextPath());
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }
}
