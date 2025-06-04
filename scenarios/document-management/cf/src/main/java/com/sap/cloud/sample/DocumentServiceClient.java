package com.sap.cloud.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sap.cloud.sample.exception.RepositoryAlreadyExistsException;
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
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class DocumentServiceClient {

    // CMIS Session parameter values
    private static final String BROWSER = "browser";
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String LANGUAGE_EN = "en";
    private static final String CONNECTION_TIMEOUT = Integer.toString(30_000);
    private static final String READ_TIMEOUT = Integer.toString(600_000);
    public static final String SECRET_KEY = "Espm@1234567890";

    // Repository info properties
    private static final String REPOSITORIES_ENDPOINT = "rest/v2/repositories/";
    private static final String REPO_AND_CONNECTION_INFOS = "repoAndConnectionInfos";
    private static final String REPOSITORY = "repository";
    private static final String ID = "id";
    private static final String NAME = "name";
    private final ServiceBindingAccessor serviceBindingAccessor = new ServiceBindingAccessor();

    public Session getSession(String uniqueName) throws CmisObjectNotFoundException {
        return createCMISSession(uniqueName);
    }

    public String createRepository(String uniqueName) throws RepositoryAlreadyExistsException {
        return createNewRepository(uniqueName);
    }

    public void deleteRepository(String uniqueName) throws CmisObjectNotFoundException {
        if (!repositoryExists(uniqueName)) {
            throw new CmisObjectNotFoundException("Repository with name " + uniqueName + " not found.");
        }

        String repoId = getRepositoryId(uniqueName);
        String repoURL = serviceBindingAccessor.getEcmServiceUrl() + REPOSITORIES_ENDPOINT;
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpDelete request = new HttpDelete(repoURL + repoId);
            String token = serviceBindingAccessor.getAuthorizationToken();
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                throw new RuntimeException("Failed to delete repository, HTTP code: " + statusCode);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private Session createCMISSession(String uniqueName) throws CmisObjectNotFoundException {

        if (!repositoryExists(uniqueName)) {
            throw new CmisObjectNotFoundException("Repository with name " + uniqueName + " not found.");
        }

        String repoId = getRepositoryId(uniqueName);

        SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
        Map<String, String> parameterMap = new HashMap<>();

        parameterMap.put(SessionParameter.BINDING_TYPE, BROWSER);
        parameterMap.put(SessionParameter.BROWSER_URL, serviceBindingAccessor.getEcmServiceUrl() + BROWSER);
        parameterMap.put(SessionParameter.OAUTH_ACCESS_TOKEN, serviceBindingAccessor.getAuthorizationToken());
        parameterMap.put(SessionParameter.REPOSITORY_ID, repoId);

        parameterMap.put(SessionParameter.AUTH_HTTP_BASIC, FALSE);
        parameterMap.put(SessionParameter.AUTH_SOAP_USERNAMETOKEN, FALSE);
        parameterMap.put(SessionParameter.AUTH_OAUTH_BEARER, TRUE);
        parameterMap.put(SessionParameter.COMPRESSION, TRUE);
        parameterMap.put(SessionParameter.CLIENT_COMPRESSION, FALSE);
        parameterMap.put(SessionParameter.COOKIES, TRUE);
        parameterMap.put(SessionParameter.LOCALE_ISO639_LANGUAGE, LANGUAGE_EN);
        parameterMap.put(SessionParameter.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
        parameterMap.put(SessionParameter.READ_TIMEOUT, READ_TIMEOUT);

        return sessionFactory.createSession(parameterMap);
    }

    private String getRepositoryId(String uniqueName) {
        JsonNode repositoriesInfo = getRepositoryInfo(uniqueName);
        Optional<JsonNode> matchingRepository = streams(
                repositoriesInfo.findValues("repository"))
                .filter(repository -> uniqueName.equals(repository.path(NAME).asText()))
                .findFirst();

        return matchingRepository
                .map(repo -> repo.path(ID).asText())
                .orElseThrow(
                        () -> new CmisObjectNotFoundException("Repository with name " + uniqueName + " not found."));

    }

    public static Stream<JsonNode> streams(List<JsonNode> nodes) {
        return nodes.stream();
    }

    private JsonNode getRepositoryInfo(String uniqueName) {

        String repoURL = serviceBindingAccessor.getEcmServiceUrl() + REPOSITORIES_ENDPOINT;
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpGet request = new HttpGet(repoURL);
            String token = serviceBindingAccessor.getAuthorizationToken();
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

            HttpResponse response = httpClient.execute(request);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                throw new IOException("Failed to get repository info, HTTP code: " + statusCode);
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(sb.toString()).get(REPO_AND_CONNECTION_INFOS);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String createNewRepository(String uniqueName) throws RepositoryAlreadyExistsException {
        if (repositoryExists(uniqueName)) {
            throw new RepositoryAlreadyExistsException("Repository with name: " + uniqueName + " already exists.");
        }
        String id;
        String repoURL = serviceBindingAccessor.getEcmServiceUrl() + REPOSITORIES_ENDPOINT;
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpPost request = new HttpPost(repoURL);
            String token = serviceBindingAccessor.getAuthorizationToken();
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            StringEntity payload = new StringEntity(setRepositoryPayload(uniqueName));
            request.setEntity(payload);

            // An error is thrown if no entitlement is configured for document management
            // service, repository option
            HttpResponse response = httpClient.execute(request);
            if (response.getStatusLine().getStatusCode() == 500) {
                throw new RuntimeException("No entitlement is configured for "
                        + "Document Management Service, Repository Option. Entitlements is required to create a repository.");
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
            Optional<String> responseBody = Optional.of(br.readLine());

            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(responseBody.get());
            id = jsonNode.get(ID).asText();

            return id;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean repositoryExists(String uniqueName) {
        JsonNode repositoryInfo = getRepositoryInfo(uniqueName);
        if (repositoryInfo == null || repositoryInfo.isEmpty()) {
            return false;
        }
        return repositoryInfo.findValuesAsText(NAME).contains(uniqueName);
    }

    private String setRepositoryPayload(String uniqueName) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rootNode = mapper.createObjectNode();

        ObjectNode leafNode = mapper.createObjectNode();
        leafNode.put("displayName", uniqueName);
        leafNode.put("description", "Repository with name" + uniqueName);
        leafNode.put("repositoryType", "internal");
        leafNode.put("isVirusScanEnabled", "true");
        leafNode.put("skipVirusScanForLargeFile", "false");
        leafNode.put("hashAlgorithms", "SHA-256");

        rootNode.set(REPOSITORY, leafNode);

        return rootNode.toString();
    }

}
