package com.sap.cloud.sample.passstore;

import com.sap.cloud.sample.credstore.client.CredStoreClient;
import com.sap.cloud.sample.credstore.client.CredStoreResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;

public class PasswordStorageServlet extends HttpServlet {

    public static final String ALIAS = "alias";
    public static final String NAMESPACE = "namespace";

    private final CredStoreClient credStoreClient;

    public PasswordStorageServlet() throws GeneralSecurityException, IOException {
        this.credStoreClient = new CredStoreClient();
    }

    @Override
    public void destroy() {
        // Release the mTLS private key when the servlet is taken out of service.
        // The client holds the key for its whole lifetime (the SSLContext reuses
        // it across requests), so it is destroyed here rather than per request.
        credStoreClient.close();
    }


    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String alias = request.getParameter(ALIAS);
        String namespace = request.getParameter(NAMESPACE);

        PrintWriter writer = response.getWriter();

        if (StringUtils.isBlank(alias) || StringUtils.isBlank(namespace)) {
            writer.println("Alias and namespace must be provided as path parameters.");
            writer.flush();
            return;
        }

        CredStoreResponse credStoreResponse = credStoreClient.retrievePassword(alias, namespace);

        if (credStoreResponse.isSuccess()) {
            writer.println("Password retrieved successfully.");
            writer.flush();
            return;
        }

        writer.println("Failed to retrieve password.");
        writer.flush();
    }

}