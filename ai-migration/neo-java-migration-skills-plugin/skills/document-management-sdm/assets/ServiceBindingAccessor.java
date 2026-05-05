package com.example.document;

import com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ServiceBindingAccessor {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private String cachedToken;
    private long tokenExpiry;

    /**
     * Get SDM service binding
     */
    public ServiceBinding getSDMBinding() {
        List<ServiceBinding> bindings = DefaultServiceBindingAccessor.getInstance()
            .getServiceBindings();

        return bindings.stream()
            .filter(b -> "sdm".equalsIgnoreCase(b.getServiceName().orElse(null)))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("SDM service not bound"));
    }

    /**
     * Get ECM service URL from SDM binding.
     * Handles both String and Map formats for the ecmservice endpoint:
     * - String: "https://api-sdm-di.cfapps.eu11.hana.ondemand.com"
     * - Map: {"timeout": 900000, "url": "https://api-sdm-di.cfapps.eu11.hana.ondemand.com/"}
     * Returns the URL without trailing slash.
     */
    public String getEcmServiceUrl() {
        ServiceBinding binding = getSDMBinding();
        Map<String, Object> credentials = binding.getCredentials();

        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) credentials.get("endpoints");
        Object ecmservice = endpoints.get("ecmservice");

        String url;
        if (ecmservice instanceof String) {
            url = (String) ecmservice;
        } else if (ecmservice instanceof Map) {
            // CF SDM binding may return ecmservice as a nested map: {"url": "https://...", "timeout": ...}
            @SuppressWarnings("unchecked")
            Map<String, Object> ecmMap = (Map<String, Object>) ecmservice;
            url = (String) ecmMap.get("url");
        } else {
            throw new RuntimeException(
                "Unexpected ecmservice endpoint type: " + (ecmservice == null ? "null" : ecmservice.getClass()));
        }

        if (url == null || url.isBlank()) {
            throw new RuntimeException("ECM service URL is missing from SDM binding credentials");
        }

        // Strip trailing slash to avoid double-slash in URL construction
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Get OAuth token for SDM access
     */
    public String getAuthorizationToken() {
        // Check cached token
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken;
        }

        ServiceBinding binding = getSDMBinding();
        Map<String, Object> credentials = binding.getCredentials();

        @SuppressWarnings("unchecked")
        Map<String, Object> uaa = (Map<String, Object>) credentials.get("uaa");

        String tokenUrl = uaa.get("url") + "/oauth/token";
        String clientId = (String) uaa.get("clientid");
        String clientSecret = (String) uaa.get("clientsecret");

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(tokenUrl);

            // Basic auth header
            String auth = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes());
            post.setHeader("Authorization", "Basic " + auth);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");

            // Request body
            post.setEntity(new StringEntity("grant_type=client_credentials"));

            HttpResponse response = httpClient.execute(post);
            String responseBody = EntityUtils.toString(response.getEntity());

            JsonNode json = objectMapper.readTree(responseBody);
            cachedToken = json.get("access_token").asText();

            // Set expiry (with 60 second buffer)
            int expiresIn = json.get("expires_in").asInt();
            tokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000L;

            return cachedToken;

        } catch (IOException e) {
            throw new RuntimeException("Failed to get OAuth token", e);
        }
    }
}
