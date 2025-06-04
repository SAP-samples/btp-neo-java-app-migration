package com.sap.cloud.sample.mail.integrationtest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import javax.servlet.http.HttpServletResponse;

import com.sap.jpaas.test.framework.util.Utils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebForm;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;

/**
 * Integration test using HttpUnit.
 */
public class MailIntegrationTest {
    public static final String FROM_ADDRESS = "fromaddress";
    public static final String TO_ADDRESS = "toaddress";
    public static final String LOCALHOST = "localhost";
    public static final String SUBJECT_TEXT = "subjecttext";
    public static final String MAIL_TEXT = "mailtext";
    public static final String SENT_RESPONSE = "E-mail was sent (in local scenario stored in '<local-server>/work/mailservice')";

    private static final String LOCAL_TEST_EMAIL = "local-test@sap.com";
    private static final String HELLO_WORLD = "Hello World!";
    private static final String INTEGRATION_TEST_APP_URL = "app.url";
    private static final String LOCALHOST_URL = "http://localhost:8080";
    private static final String MAIL_FROM_ADDRESS = "mail.from.address";
    private static final String MAIL_TO_ADDRESS = "mail.to.address";

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
            serverUrl = LOCALHOST_URL;
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
     * Call the main servlet and check that it contains the expected content.
     */
    @Test
    public void testMailServlet() throws Exception {
        // Call servlet
        WebRequest request = new GetMethodWebRequest(serverUrl);
        WebResponse response = wc.getResponse(request);

        // Fill out form and send mail
        final WebForm form = response.getForms()[0];
        if (serverUrl.contains(LOCALHOST)) {
            form.setParameter(FROM_ADDRESS, LOCAL_TEST_EMAIL);
            form.setParameter(TO_ADDRESS, LOCAL_TEST_EMAIL);
        } else {
            form.setParameter(FROM_ADDRESS, System.getProperty(MAIL_FROM_ADDRESS));
            form.setParameter(TO_ADDRESS, System.getProperty(MAIL_TO_ADDRESS));
        }
        form.setParameter(SUBJECT_TEXT, HELLO_WORLD);
        form.setParameter(MAIL_TEXT, HELLO_WORLD);
        
        // Retry mechanism
        int retryCount = 0;
        int maxRetries = 10;
        while (retryCount < maxRetries) {
            try {
                response = form.submit();
                if (response.getResponseCode() == HttpServletResponse.SC_OK) {
                    break;
                }
            } catch (com.meterware.httpunit.HttpInternalErrorException e) {
                Utils.printlnFormatted("Attempt " + (retryCount + 1) + " failed with HttpInternalErrorException");
            }
            
            Utils.printlnFormatted("Attempt " + (retryCount + 1) + " failed with response code: " + response.getResponseCode());
            Utils.printlnFormatted("Response text: " + response.getText());
            
            retryCount++;
        }

        // Check that we could send mail
        assertThat(response.getResponseCode(), is(equalTo(HttpServletResponse.SC_OK)));
        assertThat(response.getText(), containsString(SENT_RESPONSE));
    }
}
