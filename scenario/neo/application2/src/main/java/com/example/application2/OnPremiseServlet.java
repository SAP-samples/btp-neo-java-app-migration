package com.example.application2;

import com.sap.cloud.account.TenantContext;
import com.sap.core.connectivity.api.configuration.ConnectivityConfiguration;
import com.sap.core.connectivity.api.configuration.DestinationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class OnPremiseServlet extends HttpServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(OnPremiseServlet.class);

  private static final String DESTINATION_NAME = "on-premise-dest";

  @Resource
  private TenantContext tenantContext;

  private ConnectivityConfiguration connectivityConfiguration;

  @Override
  public void init() {
    try {
      InitialContext ctx = new InitialContext();

      connectivityConfiguration = (ConnectivityConfiguration) ctx.lookup("java:comp/env/connectivityConfiguration");
    } catch (NamingException e) {
      throw new RuntimeException("Can't lookup resources", e);
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String message;
    try {
      DestinationConfiguration destinationConfiguration = connectivityConfiguration.getConfiguration(DESTINATION_NAME);
      String requestUrl = destinationConfiguration.getProperty("URL") + "/hello-servlet";

      String proxyHost = System.getenv("HC_OP_HTTP_PROXY_HOST");
      int proxyPort = Integer.parseInt(System.getenv("HC_OP_HTTP_PROXY_PORT"));

      Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

      URL url = new URL(requestUrl);
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection(proxy);
      urlConnection.setRequestProperty("SAP-Connectivity-ConsumerAccount", tenantContext.getTenant().getAccount().getName());

      try (InputStream inputStream = urlConnection.getInputStream()) {
        message = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
      }
    } catch (Exception e) {
      LOGGER.error("Can't establish connection to OnPremise system", e);

      message = "Can't establish connection to OnPremise system: " + e.getMessage();
    }

    request.setAttribute("message", message);
    request.getRequestDispatcher("/on-premise.jsp").forward(request, response);
  }

}
