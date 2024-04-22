package com.example.application1;

import com.example.application1.dao.PersonDAO;
import com.example.application1.model.Person;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.exception.DestinationNotFoundException;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.BasicResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor.getDestination;
import static com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient;

@MultipartConfig
public class ManagementPageServlet extends HttpServlet {

  private final static Logger LOGGER = LoggerFactory.getLogger(ManagementPageServlet.class);

  private static final String DESTINATION_NAME = "application2-destination";

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    PersonDAO personDAO = new PersonDAO();
    List<Person> personnel = personDAO.findAll();

    String personnelJson = new ObjectMapper().writeValueAsString(personnel);
    response.getWriter().print(personnelJson);

  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String action = request.getParameter("action");

    if ("delete".equals(action)) {
      doDelete(request, response);
      return;
    }
    String fname = request.getParameter("fname");
    String lname = request.getParameter("lname");

    Person person = new Person();
    person.setFirstName(fname);
    person.setLastName(lname);

    Part file = request.getPart("file");

    if (file.getSize() > 0) {
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
        builder.setParameter("caller", "external");
        builder.setParameter("fileName", file.getSubmittedFileName());
        URI uri = builder.build();

        HttpPost httpPost = new HttpPost(uri);

        HttpEntity httpEntity = MultipartEntityBuilder.create().addBinaryBody("file", file.getInputStream()).build();

        httpPost.setEntity(httpEntity);

        String documentId = httpClient.execute(httpPost, new BasicResponseHandler()).replaceAll("^\"|\\n$", "");
        person.setImageId(documentId);
      } catch (IOException e) {
        LOGGER.error("Can't establish connection with Document Storage application: " + e.getMessage(), e);
      } catch (URISyntaxException e) {
        LOGGER.error("Exception occurred: " + e.getMessage(), e);

        throw new ServletException(e);
      }
    }

    PersonDAO personDAO = new PersonDAO();
    personDAO.save(person);
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String personId = request.getParameter("personId");
    long id = Long.parseLong(personId);
    PersonDAO personDAO = new PersonDAO();

    Person person = personDAO.findById(id);
    String imageId = person.getImageId();

    if (imageId != null) {
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
        builder.setParameter("action", "delete");
        builder.setParameter("caller", "external");
        builder.setParameter("documentId", imageId);
        URI uri = builder.build();

        HttpPost httpPost = new HttpPost(uri);

        httpClient.execute(httpPost);
      } catch (IOException e) {
        LOGGER.error("Can't establish connection with Document Storage application: " + e.getMessage(), e);
      } catch (URISyntaxException e) {
        LOGGER.error("Exception occurred: " + e.getMessage(), e);

        throw new ServletException(e);
      }
    }

    personDAO.delete(id);
  }
}