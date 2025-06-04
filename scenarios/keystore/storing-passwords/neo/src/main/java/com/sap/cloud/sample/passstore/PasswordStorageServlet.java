package com.sap.cloud.sample.passstore;

import java.io.IOException;
import java.io.PrintWriter;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sap.cloud.security.password.PasswordStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PasswordStorageServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(PasswordStorageServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        PasswordStorage passwordStorage = lookupPasswordStorage();
        passwordStorage.getPassword(request.getParameter("alias"));

        PrintWriter writer = response.getWriter();
        writer.println("Password retrieved successfully.");
        writer.flush();
    }

    private PasswordStorage lookupPasswordStorage() {
        try {
            InitialContext ctx = new InitialContext();
            return (PasswordStorage) ctx.lookup("java:comp/env/PasswordStorage");
        } catch (NamingException exception) {
            logger.error("PasswordStorage API lookup failed.", exception);
            throw new RuntimeException("Failed to lookup PasswordStorage API.", exception);
        }
    }

}