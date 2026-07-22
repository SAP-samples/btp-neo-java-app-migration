package com.example.document;

public class CredStoreResponse {
    private final boolean success;
    private final String message;

    public CredStoreResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
