package com.example.credstore.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Client for SAP Credential Store using mTLS authentication
 */
public class CredStoreClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String NAMESPACE_HEADER = "sapcp-credstore-namespace";

    private final HttpClient httpClient;
    private final String credentialStoreUrl;

    public CredStoreClient() throws Exception {
        // Read service binding and set up SSL context
        // Implementation simplified - see full code in skill documentation
        this.credentialStoreUrl = "https://credstore-api.cfapps.sap.hana.ondemand.com";
        this.httpClient = HttpClient.newBuilder()
            .sslContext(SSLContext.getDefault())
            .build();
    }

    /**
     * Retrieve a password by name
     */
    public String getPassword(String name, String namespace) throws Exception {
        String url = String.format("%s/password?name=%s", credentialStoreUrl, name);
        JsonNode response = sendRequest(url, namespace);
        return response.path("value").asText();
    }

    /**
     * Retrieve a key (certificate/private key pair)
     */
    public KeyCredential getKey(String alias, String namespace) throws Exception {
        String url = String.format("%s/key?name=%s", credentialStoreUrl, alias);
        JsonNode response = sendRequest(url, namespace);

        return new KeyCredential(
            response.path("name").asText(),
            response.path("value").asText(),
            response.path("metadata").path("certificate").asText()
        );
    }

    private JsonNode sendRequest(String url, String namespace) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(NAMESPACE_HEADER, namespace)
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Credential Store request failed: " +
                response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    public static class KeyCredential {
        private final String name;
        private final String encryptedPrivateKey;
        private final String certificate;

        public KeyCredential(String name, String encryptedPrivateKey, String certificate) {
            this.name = name;
            this.encryptedPrivateKey = encryptedPrivateKey;
            this.certificate = certificate;
        }

        public String getName() { return name; }
        public String getEncryptedPrivateKey() { return encryptedPrivateKey; }
        public String getCertificate() { return certificate; }
    }
}
