package com.sap.cloud.sample.keystore;

import java.io.IOException;
import java.security.KeyStore;
import java.util.Enumeration;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sap.cloud.crypto.keystore.api.KeyStoreService;

public class KeyStoreServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(KeyStoreServlet.class);

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String method = request.getParameter("method");

        try {
            if (method == null || method.equals("getKeyStore") == false) {
                logger.error("KeyStore service method [{}] is invalid.", method);
                ErrorUtil.printErrorToResponse(response, "KeyStore service method [" + method + "] is invalid.", HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            KeyStoreService keyStoreService = lookupKeyStoreService();

            KeyStore keyStore = keyStoreService.getKeyStore(request.getParameter("keyStoreName"), toChars(request.getParameter("password")));

            Enumeration<String> entries = keyStore.aliases();
            StringBuilder responseBuilder = new StringBuilder();

            while (entries.hasMoreElements()) {
                responseBuilder.append(entries.nextElement());
                responseBuilder.append(",");
            }

            response.getWriter().write(responseBuilder.substring(0, responseBuilder.length() - 1));
        } catch (Exception e) {
            logger.error("Internal server error while executing method [{}]", method, e);
            ErrorUtil.printErrorToResponse(response, e, "Internal server error while executing method [" + method + "]", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private KeyStoreService lookupKeyStoreService() throws IOException {
        try {
            Context context = new InitialContext();
            return (KeyStoreService) context.lookup("java:comp/env/KeyStoreService");
        } catch (NamingException e) {
            throw new IOException("Failed to lookup keyStore service", e);
        }
    }

    private char[] toChars(String value) {
        return (value != null) ? value.toCharArray() : null;
    }
}