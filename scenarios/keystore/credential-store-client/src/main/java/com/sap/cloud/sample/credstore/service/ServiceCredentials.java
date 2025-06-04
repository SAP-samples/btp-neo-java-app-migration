package com.sap.cloud.sample.credstore.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceCredentials {
    private String url;
    private String key;
    private String certificate;
}

