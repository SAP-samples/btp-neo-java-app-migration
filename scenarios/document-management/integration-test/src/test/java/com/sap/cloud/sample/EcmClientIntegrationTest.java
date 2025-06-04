package com.sap.cloud.sample;

import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EcmClientIntegrationTest {
    private static final String BASE_URL = System.getProperty("app.url");
    private static final String END_POINT = "/ecm/?uniqueName=ecm-test.repository";
    private static final String TEST_URL = BASE_URL + END_POINT;
    
    @BeforeAll
    static void setup() {
        System.out.println("Test url is:" + TEST_URL);
        Unirest.delete(TEST_URL);
    }

    @AfterEach
    void cleanup() throws UnirestException {
        deleteRepo();
    }

    @Test
    void whenConnect_givenNotExistingRepoName_thenReturnErrorResponse() throws UnirestException {
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, getRepo());
    }

    @Test
    void whenCreate_givenNotExistingRepoName_thenReturnNewRepoId() throws UnirestException {
        assertEquals(HttpURLConnection.HTTP_CREATED, createRepo());
    }

    @Test
    void whenDelete_givenNotExistingRepoName_thenReturnErrorResponse() throws UnirestException {
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, deleteRepo());
    }
    
    @Test
    void whenConnect_givenExistingRepoName_thenReturnSession() throws UnirestException {
        createRepo();
        assertEquals(HttpURLConnection.HTTP_OK, getRepo());
    }

    @Test
    void whenCreate_givenExistingRepoName_thenReturnErrorResponse() throws UnirestException {
        createRepo();
        assertEquals(HttpURLConnection.HTTP_PRECON_FAILED, createRepo());
    }

    @Test
    void whenDelete_givenExistingRepo_thenReturnSuccessResponse() throws UnirestException {
        createRepo();
        assertEquals(HttpURLConnection.HTTP_OK, deleteRepo());
    }

    private int getRepo() throws UnirestException {
        return Unirest.get(TEST_URL).asString().getStatus();
    }

    private int createRepo() throws UnirestException {
        return Unirest.post(TEST_URL).asString().getStatus();
    }

    private int deleteRepo() throws UnirestException {
        return Unirest.delete(TEST_URL).asString().getStatus();
    }
}