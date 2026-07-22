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
import org.apache.http.client.methods.HttpDelete;
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
 * SDM identifier rules — the two endpoints take DIFFERENT identifiers:
 *
 * - CMIS browser binding (`SessionParameter.REPOSITORY_ID`): uses the repository NAME.
 *   The REST response has a `cmisRepositoryId` field, but that is the root folder ID, not
 *   the CMIS repository id. Verify by hitting `GET {ecmServiceUrl}/browser` — the response
 *   is a map keyed by repository names.
 *
 * - REST DELETE (`DELETE /rest/v2/repositories/{X}`): uses the SDM internal UUID — the `id`
 *   field returned by `GET /rest/v2/repositories/` (e.g. `f3023e03-81da-4be4-...`).
 *   Passing the repository name in this path produces HTTP 500 with body
 *   `{"message":"Repository with id:&lt;name&gt; is invalid. Please enter a valid repository ID."}`.
 *
 * That is why this class exposes two lookup methods:
 *   - `getRepositoryId(name)`  — returns the name itself if the repo exists; for CMIS sessions.
 *   - `getRepositoryUuid(name)` — returns the SDM internal UUID; for REST DELETE.
 *
 * Other notes:
 * - The browser binding URL is `{ecmServiceUrl}/browser`.
 * - Repository creation uses REST API v2 at `{ecmServiceUrl}/rest/v2/repositories/`.
 * - GET returns `repoAndConnectionInfos` as a single object when there is only one
 *   repository, or as an array otherwise. Both lookups use `JsonNode.findValues("repository")`
 *   to traverse the tree, which works for both shapes.
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
     *
     * The request body must use a nested format: `{"repository": {"name": ..., ...}}`.
     *
     * **Why we pre-check existence:** SDM's POST is idempotent at the HTTP level — it
     * returns `201 Created` even when a repository with the same name already exists,
     * so we cannot detect the conflict from the HTTP status alone. The Neo `EcmService`,
     * by contrast, threw on duplicate creation. To preserve the original semantics
     * (so existing integration tests that expect HTTP 412 Precondition Failed still
     * pass), this method does a `repositoryExists` check first and throws
     * {@link RepositoryAlreadyExistsException} when it sees one.
     */
    public String createRepository(String repositoryName) throws RepositoryAlreadyExistsException {
        if (repositoryExists(repositoryName)) {
            throw new RepositoryAlreadyExistsException(
                "Repository already exists: " + repositoryName);
        }

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

            if (statusCode != HttpStatus.SC_CREATED && statusCode != HttpStatus.SC_OK) {
                log.error("Failed to create repository. Status: {}, Response: {}",
                    statusCode, responseBody);
                throw new RuntimeException(
                    "Failed to create repository: " + statusCode + " - " + responseBody);
            }

            // SDM returns the created repository as a FLAT JSON document — fields like
            // `id`, `name`, `cmisRepositoryId`, `repositoryType` sit at the top level,
            // NOT wrapped in a `{"repository": {...}}` envelope. (The request body uses
            // the wrapper; the response does not. Don't symmetrize the two.)
            JsonNode created = objectMapper.readTree(responseBody);
            String name = created.path("name").asText(null);
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
     * Delete a repository via SDM REST API v2.
     *
     * The path segment is the SDM internal UUID — the `id` field from
     * `GET /rest/v2/repositories/` — NOT the repository name. SDM rejects the name
     * with HTTP 500 `{"message":"Repository with id:<name> is invalid..."}`. This is
     * the opposite identifier rule from the CMIS session, which uses the name.
     */
    public void deleteRepository(String repositoryName) throws CmisObjectNotFoundException {
        // Resolve UUID before issuing DELETE — also serves as the existence check,
        // throwing CmisObjectNotFoundException if the repo isn't there.
        String repoUuid = getRepositoryUuid(repositoryName);

        String repoUrl = normalizeUrl(
            bindingAccessor.getEcmServiceUrl(),
            "/rest/v2/repositories/" + repoUuid);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpDelete request = new HttpDelete(repoUrl);

            String token = bindingAccessor.getAuthorizationToken();
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (statusCode == HttpStatus.SC_NOT_FOUND) {
                throw new CmisObjectNotFoundException("Repository not found: " + repositoryName);
            }

            if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_NO_CONTENT) {
                log.error("Failed to delete repository. Status: {}, Response: {}",
                    statusCode, responseBody);
                throw new RuntimeException(
                    "Failed to delete repository: " + statusCode + " - " + responseBody);
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to delete repository", e);
        }
    }

    /**
     * Check if repository exists. Walks every `name` field anywhere in the
     * `repoAndConnectionInfos` subtree, which means it works whether SDM returns
     * `repoAndConnectionInfos` as a single object or as an array.
     */
    public boolean repositoryExists(String repositoryName) {
        JsonNode repoAndConn = getRepoAndConnectionInfos();
        if (repoAndConn == null || repoAndConn.isMissingNode() || repoAndConn.isNull()) {
            return false;
        }
        return repoAndConn.findValuesAsText("name").contains(repositoryName);
    }

    /**
     * Look up the CMIS repository identifier for a given repository name.
     *
     * For SDM's CMIS browser binding, the repository identifier IS the repository name —
     * so this method just verifies the repo exists and echoes the name back. (We keep it
     * as a method, not a no-op at the call site, to keep the CMIS-vs-REST identifier
     * distinction explicit at every call.)
     */
    public String getRepositoryId(String repositoryName) throws CmisObjectNotFoundException {
        if (!repositoryExists(repositoryName)) {
            throw new CmisObjectNotFoundException("Repository not found: " + repositoryName);
        }
        return repositoryName;
    }

    /**
     * Look up the SDM internal UUID for a given repository name. Required for
     * `DELETE /rest/v2/repositories/{uuid}` — SDM rejects the name in that path.
     *
     * Uses `findValues("repository")` to traverse the response tree without caring
     * whether `repoAndConnectionInfos` is a single object or an array.
     */
    public String getRepositoryUuid(String repositoryName) throws CmisObjectNotFoundException {
        JsonNode repoAndConn = getRepoAndConnectionInfos();
        if (repoAndConn != null && !repoAndConn.isMissingNode() && !repoAndConn.isNull()) {
            for (JsonNode repository : repoAndConn.findValues("repository")) {
                if (repositoryName.equals(repository.path("name").asText())) {
                    String uuid = repository.path("id").asText(null);
                    if (uuid != null && !uuid.isEmpty()) {
                        return uuid;
                    }
                }
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

    /**
     * Return just the `repoAndConnectionInfos` subtree from `GET /rest/v2/repositories/`.
     * Callers don't care about the wrapper. Returns the missing-node sentinel if the field
     * is absent — both `repositoryExists` and `getRepositoryUuid` handle that.
     */
    private JsonNode getRepoAndConnectionInfos() {
        return getRepositoriesInfo().path("repoAndConnectionInfos");
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
