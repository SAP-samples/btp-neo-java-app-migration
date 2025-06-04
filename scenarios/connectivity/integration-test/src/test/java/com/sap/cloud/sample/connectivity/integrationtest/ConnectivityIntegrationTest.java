package com.sap.cloud.sample.connectivity.integrationtest;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.HttpUnitOptions;
import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;

import static java.net.HttpURLConnection.HTTP_OK;

/**
 * Integration test using HttpUnit.
 */
public class ConnectivityIntegrationTest {
    private static String appUrl;
    private WebConversation wc;
    private WebRequest request;
    private WebResponse response;

    @BeforeClass
    public static void setupSuite() {
        // Get the URL of the deployed application
        appUrl = System.getProperty("app.url");
        if (appUrl == null) {
            throw new IllegalArgumentException("System property 'app.url' not set");
        }
        System.out.println("Running against " + appUrl);
    }

    /**
     * Prepare a new web conversation for each test (grouping the requests/responses exchanged with the server).
     */
    @Before
    public void setupTest() {
        // Open web conversation
        wc = new WebConversation();
    }

    /**
     * Call the main servlet and check that it contains the expected content.
     */
    @Test
    public void testConnectivityServlet() throws IOException, SAXException {
        // Call servlet
        HttpUnitOptions.setScriptingEnabled(false);
        request = new GetMethodWebRequest(appUrl);
        response = wc.getResponse(request);
        int responseCode = response.getResponseCode();
        Assert.assertEquals(HTTP_OK, responseCode);
    }

}
