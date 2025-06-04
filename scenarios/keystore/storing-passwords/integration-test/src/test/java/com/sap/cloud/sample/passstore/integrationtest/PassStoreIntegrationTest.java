package com.sap.cloud.sample.passstore.integrationtest;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import javax.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;

/**
 * Integration test using HttpUnit.
 */
public class PassStoreIntegrationTest {

    private static final String INTEGRATION_TEST_APP_URL = "app.url";
    public static final String TEST_NAMESPACE_PARAMS = "/?alias=test&namespace=pass-storage-app";

    private static String serverUrl;
    private WebConversation wc;

    /**
     * Take a provided server URL usually fed in from outside through the build process or default
     * to the local server as provided through Eclipse to run the integration test against.
     */
    @BeforeClass
    public static void setupSuite() {
        // Get integration test server URL
        serverUrl = System.getProperty(INTEGRATION_TEST_APP_URL);
        if (serverUrl == null) {
            throw new IllegalArgumentException(String.format("System property '%s' not set.", INTEGRATION_TEST_APP_URL));
        }
    }

    /**
     * Prepare a new web conversation for each test (grouping the requests/responses exchanged with
     * the server).
     */
    @Before
    public void setupTest() {
        // Open web conversation
        wc = new WebConversation();
    }

    /**
     * Call the passStore servlet and check that it contains the expected content.
     */
    @Test
    public void testGetPasswordFromCredStore() throws Exception {
        // Call servlet
        WebRequest request = new GetMethodWebRequest(serverUrl + TEST_NAMESPACE_PARAMS);
        WebResponse response = wc.getResponse(request);

        // Check that we could retrieve the password
        assertThat(response.getResponseCode(), is(equalTo(HttpServletResponse.SC_OK)));
    }
}
