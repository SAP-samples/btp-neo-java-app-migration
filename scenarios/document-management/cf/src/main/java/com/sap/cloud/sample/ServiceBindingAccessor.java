package com.sap.cloud.sample;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;
import com.sap.cloud.environment.servicebinding.api.exception.ServiceBindingAccessException;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ServiceBindingAccessor {
    Logger logger = LoggerFactory.getLogger(ServiceBindingAccessor.class);

    // SDM credentials nodes
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

    private ServiceBinding getSdmServiceBinding() throws ServiceBindingAccessException {
        List<ServiceBinding> allServiceBindings = DefaultServiceBindingAccessor.getInstance().getServiceBindings();

        return allServiceBindings.stream()
                .filter(binding -> SDM.equalsIgnoreCase(binding.getServiceName().orElseThrow(null)))
                .findFirst()
                .orElseThrow(() -> new ServiceBindingAccessException(
                "Failed to find %s service binding!".formatted(SDM)));
    }

    private JsonNode getSdmCredentials() throws ServiceBindingAccessException {
        ObjectMapper mapper = new ObjectMapper();
        ServiceBinding serviceBinding = getSdmServiceBinding();
        return mapper.convertValue(serviceBinding.getCredentials(), JsonNode.class);
    }

    public String getEcmServiceUrl() {
        try {
            JsonNode credentials = getSdmCredentials();

            return credentials.get(URI).asText();
        } catch (ServiceBindingAccessException e) {
            throw new RuntimeException("Failed to get ECM service URL.", e);
        }
    }

    public String getAuthorizationToken() throws UnsupportedOperationException, ServiceBindingAccessException {
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
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));

            String tokenContent = br.readLine();
            ObjectMapper mapper = new ObjectMapper();
            TypeReference<Map<String, String>> typeRef = new TypeReference<Map<String, String>>() {
            };
            Map<String, String> tokenMap = mapper.readValue(tokenContent, typeRef);
            return tokenMap.get(ACCESS_TOKEN);
        } catch (IOException e) {
            throw new RuntimeException("Failed to get authorization token");
        }
    }

}
