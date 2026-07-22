package com.example.document;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;

public class CredStoreRequestBuilder {

    private static final String HEADER_NAMESPACE = "sapcp-credstore-namespace";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JOSE = "application/jose";

    public static HttpRequest buildRequest(String requestUrl, String namespace) throws URISyntaxException {
        return HttpRequest.newBuilder()
                .uri(new URI(requestUrl))
                .header(HEADER_NAMESPACE, namespace)
                .header(CONTENT_TYPE, APPLICATION_JOSE)
                .GET()
                .build();
    }
}
