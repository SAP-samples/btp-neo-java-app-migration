package com.sap.cloud.sample.credstore.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceCredentials {
    private String url;
    private String key;
    private String certificate;

    public String getUrl() {
        return url;
    }

    public String getKey() {
        return key;
    }

    public String getCertificate() {
        return certificate;
    }
}
