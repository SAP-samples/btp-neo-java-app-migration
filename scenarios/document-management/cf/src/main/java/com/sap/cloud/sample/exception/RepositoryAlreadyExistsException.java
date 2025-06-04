package com.sap.cloud.sample.exception;

public class RepositoryAlreadyExistsException extends Exception {
    public RepositoryAlreadyExistsException(String message) {
        super(message);
    }
}
