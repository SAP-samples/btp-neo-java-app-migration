package com.example.application2.ecm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class EcmManager {

  // CMIS Session parameter values
  private static final String BROWSER = "browser";
  private static final String TRUE = "true";
  private static final String FALSE = "false";
  private static final String LANGUAGE_EN = "en";
  private static final String CONNECTION_TIMEOUT = Integer.toString(30_000);
  private static final String READ_TIMEOUT = Integer.toString(600_000);

  // VCAP Services environment nodes
  private static final String VCAP_SERVICES = "VCAP_SERVICES";
  private static final String SDM = "sdm";
  private static final String CREDENTIALS = "credentials";
  private static final String ENDPOINTS = "endpoints";
  private static final String ECM_SERVICE = "ecmservice";
  private static final String URL = "url";
  private static final String UAA = "uaa";
  private static final String ACCESS_TOKEN = "access_token";
  private static final String TOKEN_ENDPOINT = "/oauth/token";
  private static final String CLIENT_ID = "clientid";
  private static final String CLIENT_SECRET = "clientsecret";
  private static final String AUTHORIZATION = "Authorization";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String GRANT_TYPE = "client_credentials";

  // repository infos properties
  private static final String REPOSITORIES_ENDPOINT = "rest/v2/repositories/";
  private static final String REPO_AND_CONNECTION_INFOS = "repoAndConnectionInfos";
  private static final String REPOSITORY = "repository";
  private static final String ID = "id";


  private static EcmManager instance;
  private volatile Session openCmisSession;

  private EcmManager() {}

  public static synchronized EcmManager getInstance() {
    if (instance == null) {
      instance = new EcmManager();
    }

    return instance;
  }


  public Session getSession() {
    if (openCmisSession == null) {
      synchronized (EcmManager.class) {
        if (openCmisSession == null) {
          openCmisSession = createCMISSession();
        }
      }
    } else {
      // stub to avoid CmisUnauthorizedException (session expires because of cookies)
      synchronized (EcmManager.class) {
        try {
          openCmisSession.getRootFolder();
        } catch (CmisUnauthorizedException e) {
          openCmisSession = createCMISSession();
        }
      }
    }

    return openCmisSession;
  }


  private Session createCMISSession() {
    SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
    Map<String, String> parameterMap = new HashMap<>();

    parameterMap.put(SessionParameter.BINDING_TYPE, BROWSER);
    parameterMap.put(SessionParameter.BROWSER_URL, getEcmServiceUrl() + BROWSER);
    parameterMap.put(SessionParameter.OAUTH_ACCESS_TOKEN, getAuthorizationToken());

    parameterMap.put(SessionParameter.REPOSITORY_ID, getRepositoryId());

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

  private String getEcmServiceUrl() {
    Optional<String> ecmServiceUrl;

    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode vcap = mapper.readTree(System.getenv(VCAP_SERVICES));
      JsonNode credentials = vcap.get(SDM).get(0).get(CREDENTIALS);
      ecmServiceUrl = Optional.of(credentials.get(ENDPOINTS).get(ECM_SERVICE).get(URL).asText());
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Can't retrieve ecm url from VCAP environment variables", e);
    }

    return ecmServiceUrl.get();
  }

  private String getAuthorizationToken() throws UnsupportedOperationException {
    Optional<String> tokenContent;
    Map<String, String> map = getSDMCredentials();
    final String tokenURL = map.get(URL) + TOKEN_ENDPOINT;

    String base64Credentials = Base64.getEncoder().encodeToString((map.get(CLIENT_ID) + ":" + map.get(CLIENT_SECRET)).getBytes());

    try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
      HttpPost httpPost = new HttpPost(tokenURL);
      httpPost.addHeader(AUTHORIZATION, "Basic " + base64Credentials);
      httpPost.addHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");

      StringEntity input = new StringEntity("grant_type=" + GRANT_TYPE);
      httpPost.setEntity(input);

      HttpResponse response = httpClient.execute(httpPost);
      BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
      tokenContent = Optional.of(br.readLine());

      ObjectMapper mapper = new ObjectMapper();
      TypeReference<Map<String, String>> typeRef = new TypeReference<Map<String, String>>() {};
      HashMap<String, String> hashMap = (HashMap<String, String>) mapper.readValue(tokenContent.get(), typeRef);
      return hashMap.get(ACCESS_TOKEN);
    } catch (IOException e) {
      throw new RuntimeException("Can't get authorization token");
    }
  }

  private Map<String, String> getSDMCredentials() {
    try {
      Map<String, String> map = new HashMap<>();

      ObjectMapper mapper = new ObjectMapper();
      JsonNode vcap = mapper.readTree(System.getenv(VCAP_SERVICES));
      JsonNode credentials = vcap.get(SDM).get(0).get(CREDENTIALS);

      map.put(CLIENT_ID, credentials.get(UAA).get(CLIENT_ID).asText());
      map.put(CLIENT_SECRET, credentials.get(UAA).get(CLIENT_SECRET).asText());
      map.put(URL, credentials.get(UAA).get(URL).asText());

      return map;
    } catch (IOException e) {
      throw new RuntimeException("Can't retrieve VCAP_SERVICES SDM credentials");
    }
  }

  private String getRepositoryId() {
    Optional<String> repositoryId;
    Optional<String> repositoryInfo;

    try {
      repositoryInfo = getRepositoryInfos();

      // if repository info is not empty, fetch the repository id
      if (!repositoryInfo.get().equals("{}")) {
        repositoryId = parseRepositoryInfosAndGetID();
      } else {
        // creating a new repository when there is none found
        repositoryId = createNewRepository();
      }
    } catch (IOException e) {
      throw new RuntimeException("Can't retrieve repository id");
    }

    return repositoryId.get();
  }



  private Optional<String> parseRepositoryInfosAndGetID() throws IOException {
    Optional<String> repositoryInfo = getRepositoryInfos();
    ObjectMapper mapper = new ObjectMapper();

    if (repositoryInfo.isPresent()) {
      JsonNode vcap = mapper.readTree(repositoryInfo.get());
      JsonNode repoAndConnectionInfos = vcap.get(REPO_AND_CONNECTION_INFOS);

      return Optional.of(repoAndConnectionInfos.get(REPOSITORY).get(ID).asText());
    } else {
      throw new RuntimeException("Can't parse repositories information");
    }
  }

  private Optional<String> getRepositoryInfos() throws IOException {
    String accessToken = getAuthorizationToken();

    final String repoURL = getEcmServiceUrl() + REPOSITORIES_ENDPOINT;

    try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
      HttpGet httpGet = new HttpGet(repoURL);

      httpGet.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
      httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

      HttpResponse response = httpClient.execute(httpGet);
      BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));

      return Optional.of(br.readLine());
    }
  }

  private Optional<String> createNewRepository() throws IOException {
    Optional<String> id;

    final String repoURL = getEcmServiceUrl() + REPOSITORIES_ENDPOINT;
    try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
      HttpPost request = new HttpPost(repoURL);

      request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
      request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getAuthorizationToken());

      // Setting payload for the new repository
      StringEntity payload = new StringEntity(setRepositoryPayload());
      request.setEntity(payload);

      // An error is thrown if no entitlement is configured for document management
      // service, repository option
      HttpResponse response = httpClient.execute(request);
      if (response.getStatusLine().getStatusCode() == 500) {
        throw new RuntimeException("No entitlement is configured for " + "Document Management Service, Repository Option. Entitlements is required to create a repository.");
      }

      BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
      Optional<String> responseBody = Optional.of(br.readLine());

      ObjectMapper mapper = new ObjectMapper();
      JsonNode jsonNode = mapper.readTree(responseBody.get());
      id = Optional.of(jsonNode.get("id").asText());

      return id;
    }
  }

  private String setRepositoryPayload() {

    // creating payload for new repository
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode rootNode = mapper.createObjectNode();

    ObjectNode leafNode = mapper.createObjectNode();
    leafNode.put("displayName", "JDBC-DocumentMGMT Repository");
    leafNode.put("description", "JDBC-DocumentMGMT document store");
    leafNode.put("repositoryType", "internal");
    leafNode.put("isVirusScanEnabled", "true");
    leafNode.put("skipVirusScanForLargeFile", "false");
    leafNode.put("hashAlgorithms", "SHA-256");

    rootNode.set("repository", leafNode);
    return rootNode.toString();
  }

}
