package com.example.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;

public class CredStoreClient implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(CredStoreClient.class);

    private static final String KEY = "key";
    private static final String KEYS = "keys";
    private static final String PASSWORD = "password";

    private static final String CREDENTIALS_TYPE = "credentials";
    private static final String CREDENTIAL_TYPE = "credential";

    private final HttpClient httpClient;
    private final String credentialStoreUrl;
    private final SSLContextProvider sslContextProvider;

    public CredStoreClient() throws GeneralSecurityException, IOException {
        ServiceCredentials credentials = new ServiceCredentialsAccessor().getCredentials();
        credentialStoreUrl = credentials.getUrl();

        sslContextProvider = new SSLContextProvider();
        SSLContext sslContext = sslContextProvider.setupSSLContext(credentials);

        httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }

    public CredStoreResponse retrieveCredentials(String namespace) {
        String requestUrl = String.format("%s/%s", credentialStoreUrl, KEYS);
        return sendRequest(requestUrl, namespace, CREDENTIALS_TYPE);
    }

    public CredStoreResponse retrieveCredential(String alias, String namespace) {
        logger.info("Retrieving credential for alias: [{}] from namespace: [{}]", alias, namespace);
        String requestUrl = String.format("%s/%s?name=%s", credentialStoreUrl, KEY, alias);
        return sendRequest(requestUrl, namespace, CREDENTIAL_TYPE);
    }

    public CredStoreResponse retrievePassword(String alias, String namespace) {
        logger.info("Retrieving password for namespace: [{}]", namespace);
        String requestUrl = String.format("%s/%s?name=%s", credentialStoreUrl, PASSWORD, alias);
        return sendRequest(requestUrl, namespace, PASSWORD);
    }

    private CredStoreResponse sendRequest(String requestUrl, String namespace, String type) {
        try {
            HttpRequest request = CredStoreRequestBuilder.buildRequest(requestUrl, namespace);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                logger.info("Successfully retrieved {} for namespace: [{}]", type, namespace);
                return new CredStoreResponse(true, "Successfully retrieved " + type);
            } else {
                logger.warn("Failed to retrieve {}. HTTP Status: {}, Response: {}", type, response.statusCode(), response.body());
                return new CredStoreResponse(false, "Error retrieving " + type + ". HTTP Status: " + response.statusCode());
            }
        } catch (URISyntaxException | IOException | InterruptedException e) {
            logger.error("Error retrieving {}: {}", type, e.getMessage(), e);
            return new CredStoreResponse(false, "Failed to retrieve " + type + ". " + e.getMessage());
        }
        // NOTE: do NOT destroy the private key here. The SSLContext built in the
        // constructor retains this key for the whole lifetime of the client
        // (the servlet is a singleton, so the client and its SSLContext are
        // reused across every request). Destroying the key after the first
        // request breaks the mTLS handshake of every subsequent request to the
        // credential store — an intermittent 500 on whichever endpoint is called
        // second. Key material is released when the client is closed; see close().
    }

    /**
     * Releases the mTLS private key held by this client's SSLContext. Call once
     * the client is no longer needed (e.g. from {@code HttpServlet.destroy()}).
     * After close() the client must not be used for further requests.
     */
    @Override
    public void close() {
        sslContextProvider.destroyPrivateKey();
        logger.debug("Destroyed private key on client close.");
    }
}
