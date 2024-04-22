package com.example.application1.servlet;

import com.sap.core.connectivity.api.authentication.AuthenticationHeader;
import com.sap.core.connectivity.api.authentication.AuthenticationHeaderProvider;
import com.sap.core.connectivity.api.configuration.ConnectivityConfiguration;
import com.sap.core.connectivity.api.configuration.DestinationConfiguration;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;


public class ImageServlet extends HttpServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImageServlet.class);

  private static final String DESTINATION_NAME = "application2-destination";

  private ConnectivityConfiguration connectivityConfiguration;
  private AuthenticationHeaderProvider authenticationHeaderProvider;

  @Override
  public void init() throws ServletException {
    try {
      InitialContext ctx = new InitialContext();

      connectivityConfiguration = (ConnectivityConfiguration) ctx.lookup("java:comp/env/connectivityConfiguration");
      authenticationHeaderProvider = (AuthenticationHeaderProvider) ctx.lookup("java:comp/env/authenticationHeaderProvider");
    } catch (NamingException e) {
      throw new ServletException("Can't lookup resources", e);
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    DestinationConfiguration destinationConfiguration = connectivityConfiguration.getConfiguration(DESTINATION_NAME);
    if (destinationConfiguration == null) {
      String message = "Destination to application2 not found.";
      LOGGER.error(message);

      throw new ServletException(message);
    }

    String requestUrl = destinationConfiguration.getProperty("URL") + "/api/documents";
    AuthenticationHeader appToAppSSOHeader = authenticationHeaderProvider.getApptoAppSSOHeader(requestUrl, destinationConfiguration);

    String documentId = request.getParameter("documentId");

    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      URIBuilder builder = new URIBuilder(requestUrl);
      builder.setParameter("documentId", documentId);

      URI uri = builder.build();

      HttpGet httpGet = new HttpGet(uri);
      httpGet.addHeader(appToAppSSOHeader.getName(), appToAppSSOHeader.getValue());

      httpClient.execute(httpGet, httpResponse -> {
        InputStream inputStream = httpResponse.getEntity().getContent();
        ServletOutputStream outputStream = response.getOutputStream();

        copyContent(inputStream, outputStream);
        return null;
      });

    } catch (URISyntaxException e) {
      LOGGER.error("Exception occurred: " + e.getMessage(), e);
      throw new ServletException(e);
    }
  }

  private void copyContent(InputStream is, OutputStream os) throws IOException {
    try (BufferedInputStream bis = new BufferedInputStream(is); BufferedOutputStream bos = new BufferedOutputStream(os)) {
      byte[] buffer = new byte[1024];
      int count;
      while ((count = bis.read(buffer)) >= 0) {
        bos.write(buffer, 0, count);
      }
    } catch (IOException e) {
      LOGGER.error("Can't copy stream", e);
    } finally {
      is.close();
      os.close();
    }
  }
}
