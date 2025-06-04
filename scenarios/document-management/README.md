# Document Management Service

## Table of Contents
- [Overview](#overview)
- [Refactoring Guidelines for Document Management Service](#refactoring-guidelines-for-document-management-service)
  - [Enterprise Content Management Service](#enterprise-content-management-service)
  - [OpenCMIS Dependencies](#opencmis-dependencies)
  - [OpenCMIS Session](#opencmis-session)
  - [Session Parameters](#session-parameters)
  - [Session Creation](#session-creation)
  - [Retrieving the Document Management Service Binding Credentials](#retrieving-the-document-management-service-binding-credentials)
    - [Retrieving the Document Management Service Binding](#retrieving-the-document-management-service-binding)
    - [Accessing the Document Management Service Credentials from the Service Binding](#accessing-the-document-management-service-credentials-from-the-service-binding)
    - [Retrieving the Enterprise Content Management Service URL from the Document Management Service Credentials](#retrieving-the-enterprise-content-management-service-url-from-the-document-management-service-credentials)
    - [Retrieving the Authorization Token from the Document Management Service Credentials](#retrieving-the-authorization-token-from-the-document-management-service-credentials)
  - [Retrieving the Repository ID](#retrieving-the-repository-id)
    - [Create a Repository](#create-a-repository)
    - [Retrieve the Repository ID](#retrieve-the-repository-id)
- [Example](#example)
- [Related Information](#related-information)
- [Additional Scenarios](#additional-scenarios)

## Overview

SAP Document Management service is crucial for managing the complete lifecycle of documents, from storage and retrieval to version control and secure sharing. If your application leverages the document management service, it’s essential to ensure that it can seamlessly transition between various cloud environments to maintain its reliability and continuity.

## Refactoring Guidelines for Document Management Service

### Enterprise Content Management Service

Remove the following `<resource-ref>` from `src/main/webapp/WEB-INF/web.xml`:<br>
```xml
<resource-ref>
    <res-ref-name>[your-res-ref-name]</res-ref-name>
    <res-type>com.sap.ecm.api.EcmService</res-type>
</resource-ref>
```

### OpenCMIS Dependencies

For the Cloud Foundry environment, add the following dependencies to `pom.xml`:<br>
```xml
<dependency>
    <groupId>org.apache.chemistry.opencmis</groupId>
    <artifactId>chemistry-opencmis-client-impl</artifactId>
    <version>1.1.0</version>
</dependency>

<dependency>
    <groupId>org.apache.chemistry.opencmis</groupId>
    <artifactId>chemistry-opencmis-client-api</artifactId>
    <version>1.1.0</version>
</dependency>
```

### OpenCMIS Session

For the Cloud Foundry environment, the main difference is in the retrieval of an OpenCMIS session.

In the Neo environment, you retrieve the session from `com.sap.ecm.api.EcmService`, which you usually retrieve with a JNDI lookup in the `InitialContext`.

In the Cloud Foudry environment, you can create the session by using `org.apache.chemistry.opencmis.client.api.SessionFactory` and `org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl`.

### Session Parameters

Pass the following parameters to the `SessionFactory`:

| Parameter | Value |
| --- | --- |
| org.apache.chemistry.opencmis.binding.spi.`type` | `browser` |
| org.apache.chemistry.opencmis.binding.browser.`url` | `<ecm-service-URL>` |
| org.apache.chemistry.opencmis.oauth.`accessToken` | `<access-token>` |
| org.apache.chemistry.opencmis.session.repository.`id` | `<repository-id>` |
| org.apache.chemistry.opencmis.binding.auth.http.`basic` | `false` |
| org.apache.chemistry.opencmis.binding.auth.soap.`usernametoken` | `false` |
| org.apache.chemistry.opencmis.binding.auth.http.oauth.`bearer` | `true` |
| org.apache.chemistry.opencmis.binding.`compression` | `true` |
| org.apache.chemistry.opencmis.binding.`clientcompression` | `false` |
| org.apache.chemistry.opencmis.binding.`cookies` | `true` |
| org.apache.chemistry.opencmis.locale.`iso639` | `en` |
| org.apache.chemistry.opencmis.binding.`connecttimeout` | `<connection-timeout>` |
| org.apache.chemistry.opencmis.binding.`readtimeout` | `<read-timeout>` |

> Note: `<connection-timeout>` and `<read-timeout>` represent timeout values in milliseconds. For example, you can set them to `30000` (equivalent to 30 seconds) and `600000` (equivalent to 600 seconds or 10 minutes) respectively, but feel free to adjust these values based on your specific requirements.<br>

> Note: You can declare the predefined parameters specified above with the following constants:

```java
private static final String BROWSER = "browser";
private static final String TRUE = "true";
private static final String FALSE = "false";
private static final String LANGUAGE_EN = "en";
private static final String CONNECTION_TIMEOUT = Integer.toString(<connection-timeout>);
private static final String READ_TIMEOUT = Integer.toString(<read-timeout>);
```

### Session Creation

You create the `Session` with `SessionFactory`, which requires the parameters mentioned above.

```java
private Session createCMISSession() {
    SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
    Map<String, String> parameterMap = new HashMap<>();

    parameterMap.put(SessionParameter.BINDING_TYPE, BROWSER);
    parameterMap.put(SessionParameter.BROWSER_URL, <ecm-service-URL> + BROWSER);
    parameterMap.put(SessionParameter.OAUTH_ACCESS_TOKEN, <access-token>);

    parameterMap.put(SessionParameter.REPOSITORY_ID, <repository-id>);

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
```

### Retrieving the Document Management Service Binding Credentials

While you can retrieve the parameters from the Document Management (`sdm`) service binding using the `VCAP_SERVICES` environment variable, it is highly recommended to use the [Service Binding Access library | github.com](https://github.com/SAP/btp-environment-variable-access). 

> Note: In the examples below, we use these constants for retrieving the Document Management service binding credentials:

```java
private static final String SDM = "sdm";
private static final String URL = "url";
private static final String URI = "uri";
private static final String UAA = "uaa";
private static final String ACCESS_TOKEN = "access_token";
private static final String TOKEN_ENDPOINT = "/oauth/token";
private static final String CLIENT_ID = "clientid";
private static final String CLIENT_SECRET = "clientsecret";
private static final String AUTHORIZATION = "Authorization";
private static final String CONTENT_TYPE = "Content-Type";
private static final String GRANT_TYPE = "client_credentials";
```

The following examples show how to consume the service binding and how to get the required parameters:

#### Retrieving the Document Management Service Binding

You can get the Document Management service binding using `com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor`. Here is an example of how you can do it:

```java
private ServiceBinding getSdmServiceBinding() throws ServiceBindingAccessException {
    List<ServiceBinding> allServiceBindings = DefaultServiceBindingAccessor.getInstance().getServiceBindings();

    return allServiceBindings.stream()
            .filter(binding -> SDM.equalsIgnoreCase(binding.getServiceName().orElseThrow(null)))
            .findFirst()
            .orElseThrow(() -> new ServiceBindingAccessException(String.format("Failed to find %s service binding!", SDM)));
}
```

#### Accessing the Document Management Service Credentials from the Service Binding

You can find the required parameters in the binding credentials. These parameters will be used later for the accessToken and for the browser.url. Here is how you can get the credentials:

```java
private JsonNode getSdmCredentials() throws ServiceBindingAccessException {
    ObjectMapper mapper = new ObjectMapper();
    ServiceBinding serviceBinding = getSdmServiceBinding();

    return mapper.convertValue(serviceBinding.getCredentials(), JsonNode.class);
}
```

> Note: In this example, we use the `ServiceBinding.getCredentials()` method, which returns `java.util.Map<String, Object>`. You can use this map directly to access the binding credentials. Converting it to `com.fasterxml.jackson.databind.JsonNode` is just one way to work with the credentials and may be a personal preference or an implementation detail.

#### Retrieving the Enterprise Content Management Service URL from the Document Management Service Credentials

You can retrieve the parameter `<ecm-service-URL>` from the binding credentials. The following method is an example of how to get the ecm-service-url:

```java
private String getEcmServiceUrl() {
    try {
        JsonNode credentials = getSdmCredentials();

        return credentials.get(URI).asText();
    } catch (ServiceBindingAccessException e) {
        throw new RuntimeException("Failed to get ECM service URL.", e);
    }
}
```

#### Retrieving the Authorization Token from the Document Management Service Credentials

To obtain the `<access-token>`, you need `clientid`, `clientsecret`, and `url`. You can retrieve them from the `uaa` credentials of the SDM service binding.

To get the `<access-token>`, make an HTTP request with the following parameters:
- HTTP method: `POST`
- URL: `<url>/oauth/token` 
- Headers:
    - Authorization: `Basic <encoded_credentials>`
    - Content-Type: `application/x-www-form-urlencoded`
- Payload (Body): 
    - `grant_type=client_credentials`

> Note: `<encoded_credentials>` is the Base64 encoding of the `"<clientid>:<clientsecret>"` string.

The response is a JSON in the following format:
```json
{
    "access_token": "<access-token>",
    "token_type": "bearer",
    "expires_in": "<...>",
    "scope": "<...>",
    "jti": "<...>"
}
```

This is an example of how to retrieve the `<access-token>`:

```java
private String getAuthorizationToken() throws UnsupportedOperationException {
    try {
        JsonNode credentials = getSdmCredentials();
        String tokenURL = credentials.get(UAA).get(URL).asText() + TOKEN_ENDPOINT;
        String clientId = credentials.get(UAA).get(CLIENT_ID).asText();
        String clientSecret = credentials.get(UAA).get(CLIENT_SECRET).asText();
        
        String base64Credentials = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
        
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpPost httpPost = new HttpPost(tokenURL);
            httpPost.addHeader(AUTHORIZATION, "Basic " + base64Credentials);
            httpPost.addHeader(CONTENT_TYPE, "application/x-www-form-urlencoded");
            
            StringEntity grantType = new StringEntity("grant_type=" + GRANT_TYPE);
            httpPost.setEntity(grantType);
            
            HttpResponse response = httpClient.execute(httpPost);
            BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));

            String tokenContent = br.readLine();
            ObjectMapper mapper = new ObjectMapper();
            TypeReference<Map<String, String>> typeRef = new TypeReference<Map<String, String>>() {};
            Map<String, String> tokenMap = mapper.readValue(tokenContent, typeRef);

            return tokenMap.get(ACCESS_TOKEN);
        } catch (IOException e) {
            throw new RuntimeException("Failed to get authorization token");
        }
    } catch (ServiceBindingAccessException e) {
        throw new RuntimeException("Failed to get SDM service binding for authorization token.", e);
    }
}
```

### Retrieving the Repository ID 

All documents in CMIS are stored in a repository. The repository is generated on the go. If a repository ID already exists, we retrieve it. Otherwise, we create a new repository and fetch the newly generated ID. This ensures efficient management of documents within the SDM service, providing a seamless process for storing and retrieving data.

> Note: Make sure that your subaccount has the Document Management service, Repository Option entitlement to enable onboarding of an internal repository. As the repository is dynamically generated, it's necessary to define configuration parameters for managing effectively the features and capabilities of the repository.

#### Create a Repository

To create a new repository in the Document Management service, make an HTTP request with following parameters:

- HTTP method: `POST`
- URL: `<ecm-service-URL>/rest/v2/repositories`
- Headers:
    - Authorization: `Bearer <access-token>`
- Payload:
```json
{
    "repository": {
        "displayName": "<display-name>",
        "description": "<description>",
        "repositoryType": "internal",
        "isVirusScanEnabled": "<true/false>",
        "skipVirusScanForLargeFile": "<true/false>",
        "hashAlgorithms": "SHA-256"
    }
}
```

In the Cloud Foundry environment, repositories are created through the [SDM API | SAP Business Accelerator Hub](https://api.sap.com/api/AdminAPI/resource/Repository_Management). In the documentation for the API, you can find how to use it to manage your repositories.

> Note: We use the following constants in the examples below:

```java
private static final String REPOSITORIES_ENDPOINT = "rest/v2/repositories/";
private static final String REPO_AND_CONNECTION_INFOS = "repoAndConnectionInfos";
private static final String REPOSITORY = "repository";
private static final String ID = "id";
```

To create the repository, add the following dependency to the `pom.xml` file:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.2</version>
</dependency>
```

The example below shows how you can create a repository:

```java
private String createNewRepository() throws IOException {
    String id;
    String repoURL = getEcmServiceUrl() + REPOSITORIES_ENDPOINT;
    
    try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
        HttpPost request = new HttpPost(repoURL);

        request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getAuthorizationToken());

        StringEntity payload = new StringEntity(setRepositoryPayload());
        request.setEntity(payload);

        // An error is thrown if no entitlement is configured for document management service, repository option
        HttpResponse response = httpClient.execute(request);
        if (response.getStatusLine().getStatusCode() == 500) {
            throw new RuntimeException("No entitlement is configured for "
                    + "Document Management Service, Repository Option. Entitlements is required to create a repository.");
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
        Optional<String> responseBody = Optional.of(br.readLine());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(responseBody.get());
        id = jsonNode.get(ID).asText();

        return id;
    }
}

private String setRepositoryPayload() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rootNode = mapper.createObjectNode();

        ObjectNode leafNode = mapper.createObjectNode();
        leafNode.put("displayName", "JDBC-DocumentMGMT Repository");
        leafNode.put("description", "JDBC-DocumentMGMT document store");
        leafNode.put("repositoryType", "internal");
        leafNode.put("isVirusScanEnabled", "true");
        leafNode.put("skipVirusScanForLargeFile", "false");
        leafNode.put("hashAlgorithms", "SHA-256");

        rootNode.set(REPOSITORY, leafNode);

        return rootNode.toString();
    }
```

#### Retrieve the Repository ID

To get a repository ID from the Document Management service, first you have to make a HTTP request with following parameters:

- HTTP method: `GET`
- URL: `<ecm-service-URL>/rest/v2/repositories`
- Headers:
    - Authorization: `Bearer <access-token>`

The response from this request will be a list of all repositories. This list is then parsed to find the ID of the repository.

The example bellow shows how you can retrieve a repository id:

```java
    private String getRepositoryId() {
        String repositoryId;
        String repositoryInfo;

        try {
            repositoryInfo = getRepositoryInfo();

            // if repository info is not empty, fetch the repository id
            if (!repositoryInfo.equals("{}")) {
                repositoryId = parseRepositoryInfoAndGetID();
            } else {
                // creating a new repository when there is none found
                repositoryId = createNewRepository();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to retrieve repository id");
        }

        return repositoryId;
    }

    private String parseRepositoryInfoAndGetID() throws IOException {
        String repositoryInfo = getRepositoryInfo();
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode repositoryData = mapper.readTree(repositoryInfo);
            JsonNode repoAndConnectionInfos = repositoryData.get(REPO_AND_CONNECTION_INFOS);

            return repoAndConnectionInfos.get(REPOSITORY).get(ID).asText();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse repositories information", e);
        }
    }

    private String getRepositoryInfo() throws IOException {
        String accessToken = getAuthorizationToken();
        String repoURL = getEcmServiceUrl() + REPOSITORIES_ENDPOINT;

        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpGet httpGet = new HttpGet(repoURL);

            httpGet.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

            HttpResponse response = httpClient.execute(httpGet);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                throw new IOException("Failed to get repository info, HTTP code: " + statusCode);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));

            return br.readLine();
        }
    }
```

## Example

- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)

## Related Information
- [SAP Document Management Service | SAP Help Portal](https://help.sap.com/docs/document-management-service/sap-document-management-service/what-is-document-management-service)
- [Migrating Application to SAP Document Management Service from Neo Environment | SAP Help Portal](https://help.sap.com/docs/document-management-service/sap-document-management-service/migrating-application-to-sap-document-management-service-from-neo-environment)
- [Repository Management API | SAP Business Accelerator Hub](https://api.sap.com/api/AdminAPI/resource/Repository_Management)
- [The Service Binding Access Library For Java | SAP Community](https://community.sap.com/t5/technology-blogs-by-sap/the-service-binding-access-library-for-java/ba-p/13568127)
## [Additional Scenarios](../../README.md#7-additional-scenarios)
