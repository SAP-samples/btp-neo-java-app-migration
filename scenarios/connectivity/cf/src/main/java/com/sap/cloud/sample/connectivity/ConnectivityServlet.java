package com.sap.cloud.sample.connectivity;

import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.exception.DestinationNotFoundException;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

/**
 * Servlet class making http calls to specified http destinations.
 * Destinations are used in the following example connectivity scenarios:<br>
 * - Connecting to an outbound Internet resource using HTTP destinations<br>
 * - Connecting to an on-premise backend using on premise HTTP destinations,<br>
 * where the destinations have no authentication.<br>
 */
public class ConnectivityServlet extends HttpServlet {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectivityServlet.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String destinationName = request.getParameter("destname");

        // The default request to the Servlet will use outbound-internet-destination
        if (destinationName == null) {
            destinationName = "outbound-internet-destination";
        }

        try {
            HttpDestination destination;
            try {
                destination = DestinationAccessor.getDestination(destinationName).asHttp();
            } catch (DestinationNotFoundException e) {
                LOGGER.error("Connectivity operation failed", e);
                response.sendError(SC_INTERNAL_SERVER_ERROR, String.format(
                        "Destination %s is not found. Hint: Make sure to have the destination configured.",
                        destinationName)
                );
                return;
            }

            HttpClient httpClient = getHttpClient(destination);
            HttpGet httpGet = new HttpGet(destination.getUri());

            HttpResponse destinationResponse = httpClient.execute(httpGet);
            int statusCode = destinationResponse.getStatusLine().getStatusCode();
            if (statusCode != SC_OK) {
                LOGGER.error("Destination returned status code: {}", statusCode);
                response.sendError(SC_INTERNAL_SERVER_ERROR, "Destination returned status code: " + statusCode);
                return;
            }
            destinationResponse.getEntity().writeTo(response.getOutputStream());
        } catch (Exception e) {
            // Connectivity operation failed
            String errorMessage = "Connectivity operation failed with reason: " + e.getMessage() +
                    ". See logs for details. Hint: Make sure to have an HTTP proxy configured in your local " +
                    "environment in case your environment uses an HTTP proxy for the outbound Internet communication.";
            LOGGER.error("Connectivity operation failed", e);
            response.sendError(SC_INTERNAL_SERVER_ERROR, errorMessage);
        }
    }
}