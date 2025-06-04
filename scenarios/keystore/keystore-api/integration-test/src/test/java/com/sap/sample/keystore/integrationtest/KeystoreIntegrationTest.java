package com.sap.sample.keystore.integrationtest;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class KeystoreIntegrationTest {
    private static final String INTEGRATION_TEST_APP_URL = "app.url";
    private static final String TEST_RETRIEVE_KEYS = "/keystore?namespace=keystore-app";
    private static final String TEST_RETRIEVE_KEY = "/keystore?alias=keystore-app-key&namespace=keystore-app";

    private static String serverUrl;
    private WebConversation wc;
    private WebRequest request;
    private WebResponse response;

    @BeforeClass
    public static void setupSuite() {
        // Get the URL of the deployed application
        serverUrl = System.getProperty(INTEGRATION_TEST_APP_URL);
        if (serverUrl == null) {
            throw new IllegalArgumentException(String.format("System property '%s' not set.", INTEGRATION_TEST_APP_URL));
        }
    }

    @Before
    public void setupTest() {
        // Open web conversation
        wc = new WebConversation();
    }

    @Test
    public void testRetrieveKeysFromCredStore() throws IOException, SAXException {
        // Call servlet
        request = new GetMethodWebRequest(serverUrl + TEST_RETRIEVE_KEYS);
        response = wc.getResponse(request);
        Assert.assertThat(response.getResponseCode(), is(equalTo(HttpServletResponse.SC_OK)));
    }

    @Test
    public void testRetrieveKeyFromCredStore() throws IOException, SAXException {
        // Call servlet
        request = new GetMethodWebRequest(serverUrl + TEST_RETRIEVE_KEY);
        response = wc.getResponse(request);
        Assert.assertThat(response.getResponseCode(), is(equalTo(HttpServletResponse.SC_OK)));
    }
}
