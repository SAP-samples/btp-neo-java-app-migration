package com.example.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for SAP Document Management Service (SDM) using CMIS protocol.
 *
 * Important notes about SDM's CMIS browser binding:
 * - The CMIS repositoryId is the repository NAME (not the cmisRepositoryId field from the REST API)
 * - The browser binding URL is {ecmServiceUrl}/browser
 * - Repository creation uses the REST API v2 at {ecmServiceUrl}/rest/v2/repositories/
 * - The REST API returns repoAndConnectionInfos as a single object (not array) when there is only one repository
 */
public class DocumentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceClient.class);
    private static final String BROWSER = "browser";
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ServiceBindingAccessor bindingAccessor = new ServiceBindingAccessor();

    /**
     * Get CMIS session for a repository.
     * Uses the repository name as the CMIS repositoryId (this is how SDM's browser binding works).
     */
    public Session getSession(String repositoryName) throws CmisObjectNotFoundException {
        if (!repositoryExists(repositoryName)) {
            throw new CmisObjectNotFoundException("Repository not found: " + repositoryName);
        }

        String repoId = getRepositoryId(repositoryName);

        SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
        Map<String, String> params = new HashMap<>();

        params.put(SessionParameter.BINDING_TYPE, BROWSER);
        params.put(SessionParameter.BROWSER_URL,
            normalizeUrl(bindingAccessor.getEcmServiceUrl(), "/" + BROWSER));
        params.put(SessionParameter.OAUTH_ACCESS_TOKEN,
            bindingAccessor.getAuthorizationToken());
        params.put(SessionParameter.REPOSITORY_ID, repoId);

        params.put(SessionParameter.AUTH_HTTP_BASIC, FALSE);
        params.put(SessionParameter.AUTH_SOAP_USERNAMETOKEN, FALSE);
        params.put(SessionParameter.AUTH_OAUTH_BEARER, TRUE);
        params.put(SessionParameter.COMPRESSION, TRUE);
        params.put(SessionParameter.COOKIES, TRUE);
        params.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        params.put(SessionParameter.READ_TIMEOUT, "600000");

        return sessionFactory.createSession(params);
    }

    /**
     * Create a new repository via SDM REST API v2.
     * The request body must use a nested format: {"repository": {"name": ..., ...}}
     * Handles 409 Conflict (repository already exists) gracefully.
     */
    public String createRepository(String repositoryName) {
        String repoUrl = bindingAccessor.getEcmServiceUrl() + "/rest/v2/repositories/";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(repoUrl);

            String token = bindingAccessor.getAuthorizationToken();
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            // SDM REST API v2 requires nested repository object
            ObjectNode repoNode = objectMapper.createObjectNode();
            repoNode.put("name", repositoryName);
            repoNode.put("displayName", repositoryName);
            repoNode.put("description", "Repository: " + repositoryName);
            repoNode.put("repositoryType", "internal");
            repoNode.put("repositoryCategory", "GCM");
            repoNode.put("isVirusScanEnabled", true);
            repoNode.put("skipVirusScanForLargeFile", true);
            repoNode.putArray("hashAlgorithms").add("SHA-256");
            repoNode.put("externalId", repositoryName);

            ObjectNode body = objectMapper.createObjectNode();
            body.set("repository", repoNode);

            request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));

            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (statusCode == HttpStatus.SC_CONFLICT) {
                log.info("Repository already exists: {}", repositoryName);
                return getRepositoryId(repositoryName);
            }

            if (statusCode != HttpStatus.SC_CREATED && statusCode != HttpStatus.SC_OK) {
                log.error("Failed to create repository. Status: {}, Response: {}",
                    statusCode, responseBody);
                throw new RuntimeException(
                    "Failed to create repository: " + statusCode + " - " + responseBody);
            }

            JsonNode created = objectMapper.readTree(responseBody);
            String name = created.path("repository").path("name").asText(null);
            if (name == null || name.isEmpty()) {
                throw new RuntimeException(
                    "Repository created but response missing repository name: " + responseBody);
            }
            return name;

        } catch (IOException e) {
            throw new RuntimeException("Failed to create repository", e);
        }
    }

    /**
     * Check if repository exists
     */
    public boolean repositoryExists(String repositoryName) {
        try {
            getRepositoryId(repositoryName);
            return true;
        } catch (CmisObjectNotFoundException e) {
            return false;
        }
    }

    /**
     * Get CMIS repository ID by name.
     * Important: SDM's CMIS browser binding uses the repository NAME as the repositoryId,
     * NOT the cmisRepositoryId field from the REST API (which is actually the root folder ID).
     * Also handles the case where repoAndConnectionInfos is a single object (not array)
     * when there is only one repository.
     */
    public String getRepositoryId(String repositoryName) throws CmisObjectNotFoundException {
        JsonNode repoInfo = getRepositoriesInfo();
        JsonNode repoAndConn = repoInfo.path("repoAndConnectionInfos");

        if (repoAndConn.isArray()) {
            // Multiple repositories: array of {repository: {...}, connection: {...}}
            for (JsonNode repo : repoAndConn) {
                JsonNode repository = repo.path("repository");
                if (repositoryName.equals(repository.path("name").asText())) {
                    return repositoryName;
                }
            }
        } else if (repoAndConn.isObject()) {
            // Single repository: single object {repository: {...}, connection: {...}}
            JsonNode repository = repoAndConn.path("repository");
            if (repositoryName.equals(repository.path("name").asText())) {
                return repositoryName;
            }
        }

        throw new CmisObjectNotFoundException("Repository not found: " + repositoryName);
    }

    /**
     * Ensure base URL and path are joined with exactly one slash.
     */
    private static String normalizeUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        }
        if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private JsonNode getRepositoriesInfo() {
        String repoUrl = bindingAccessor.getEcmServiceUrl() + "/rest/v2/repositories/";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(repoUrl);

            String token = bindingAccessor.getAuthorizationToken();
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            HttpResponse response = httpClient.execute(request);

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw new IOException("Failed to get repositories: " +
                    response.getStatusLine().getStatusCode());
            }

            String responseBody = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining());

            return objectMapper.readTree(responseBody);

        } catch (IOException e) {
            throw new RuntimeException("Failed to get repositories", e);
        }
    }
}
