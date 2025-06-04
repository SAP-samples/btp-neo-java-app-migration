package com.sap.cloud.sample.keystore.servlet;

import com.sap.cloud.sample.credstore.client.CredStoreClient;
import com.sap.cloud.sample.credstore.client.CredStoreResponse;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;

public class KeyStoreServlet extends HttpServlet {

    private static final String PARAM_ALIAS = "alias";
    private static final String PARAM_NAMESPACE = "namespace";

    private final CredStoreClient credStoreClient;

    private static final Logger logger = LoggerFactory.getLogger(KeyStoreServlet.class);

    public KeyStoreServlet() throws GeneralSecurityException, IOException {
        this.credStoreClient = new CredStoreClient();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");

        String alias = request.getParameter(PARAM_ALIAS);
        String namespace = request.getParameter(PARAM_NAMESPACE);

        if (namespace == null || namespace.isBlank()) {
            logger.warn("Namespace is required!");
            sendErrorResponse(response, "Namespace is required!", HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        if (alias == null || alias.isBlank()) {
            handleRetrieveCredentials(response, namespace);
        } else {
            handleRetrieveCredential(response, alias, namespace);
        }
    }

    private void handleRetrieveCredentials(HttpServletResponse response, String namespace) throws IOException {
        CredStoreResponse credStoreResponse = credStoreClient.retrieveCredentials(namespace);
        if (credStoreResponse.isSuccess()) {
            sendSuccessResponse(response, "Successfully retrieved credentials.");
        } else {
            sendErrorResponse(response, credStoreResponse.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void handleRetrieveCredential(HttpServletResponse response, String alias, String namespace) throws IOException {
        CredStoreResponse credStoreResponse = credStoreClient.retrieveCredential(alias, namespace);
        if (credStoreResponse.isSuccess()) {
            sendSuccessResponse(response, "Successfully retrieved credential.");
        } else {
            sendErrorResponse(response, credStoreResponse.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void sendSuccessResponse(HttpServletResponse response, String message) throws IOException {
        try (PrintWriter out = response.getWriter()) {
            out.println(message);
        }
    }

    private void sendErrorResponse(HttpServletResponse response, String errorMessage, int status) throws IOException {
        response.setStatus(status);
        try (PrintWriter out = response.getWriter()) {
            out.println(errorMessage);
        }
    }
}