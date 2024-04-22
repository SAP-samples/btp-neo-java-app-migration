package com.example.application1;

import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.exception.DestinationNotFoundException;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.net.URISyntaxException;

import static com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor.getDestination;
import static com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient;

public class ImageServlet extends HttpServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImageServlet.class);

  private static final String DESTINATION_NAME = "application2-destination";

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

    String documentId = request.getParameter("documentId");

    HttpDestination httpDestination;
    try {
      httpDestination = getDestination(DESTINATION_NAME).asHttp();
    } catch (DestinationNotFoundException e) {
      String message = "Destination to application2 not found.";
      LOGGER.error(message);

      throw new ServletException(message);
    }

    String requestUrl = httpDestination.getUri() + "/api/documents";

    try {
      HttpClient httpClient = getHttpClient(httpDestination);

      URIBuilder builder = new URIBuilder(requestUrl);
      builder.setParameter("documentId", documentId);

      HttpGet httpGet = new HttpGet(builder.build());
      HttpResponse httpResponse = httpClient.execute(httpGet);

      InputStream inputStream = httpResponse.getEntity().getContent();
      ServletOutputStream outputStream = response.getOutputStream();

      copyContent(inputStream, outputStream);
    } catch (URISyntaxException e) {
      throw new ServletException(e);
    }

  }

  private void copyContent(InputStream is, OutputStream os) throws ServletException {
    try (BufferedInputStream bis = new BufferedInputStream(is); BufferedOutputStream bos = new BufferedOutputStream(os)) {
      byte[] buffer = new byte[1024];
      int count;
      while ((count = bis.read(buffer)) >= 0) {
        bos.write(buffer, 0, count);
      }
    } catch (IOException e) {
      throw new ServletException("Can't copy stream", e);
    }
  }
}
