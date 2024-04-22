package com.example.application2;

import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor.getDestination;
import static com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient;

public class OnPremiseServlet extends HttpServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(OnPremiseServlet.class);

  private static final String DESTINATION_NAME = "on-premise-dest";

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String message;
    try {
      HttpDestination httpDestination = getDestination(DESTINATION_NAME).asHttp();
      String requestUrl = httpDestination.getUri() + "/hello-servlet";

      HttpClient httpClient = getHttpClient(httpDestination);
      HttpGet httpGet = new HttpGet(requestUrl);

      message = httpClient.execute(httpGet, new BasicResponseHandler());
    } catch (Exception e) {
      LOGGER.error("Can't establish connection to OnPremise system", e);

      message = "Can't establish connection to OnPremise system: " + e.getMessage();
    }

    request.setAttribute("message", message);

    request.getRequestDispatcher("/on-premise.jsp").forward(request, response);
  }
}
