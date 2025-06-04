package com.sap.cloud.sample.credstore.client;

import lombok.Getter;

@Getter
public class CredStoreResponse {
    private final boolean success;
    private final String message;

    public CredStoreResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}