package com.example.application2;

import com.example.application2.ecm.EcmManager;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@MultipartConfig
public class DocumentsServlet extends HttpServlet {

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    String documentId = request.getParameter("documentId");
    if (documentId == null) {
      throw new ServletException("documentId is empty");
    }

    Session session = EcmManager.getInstance().getSession();

    CmisObject object = session.getObject(documentId);
    if (!(object instanceof Document)) {
      throw new ServletException("Object is not a document");
    }

    InputStream inputStream = ((Document) object).getContentStream().getStream();
    ServletOutputStream outputStream = response.getOutputStream();
    copyContent(inputStream, outputStream);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String action = request.getParameter("action");
    if ("delete".equals(action)) {
      doDelete(request, response);
      return;
    }

    Part file = request.getPart("file");

    if (file.getSize() == 0) {
      request.setAttribute("message", "No file uploaded");
      request.getRequestDispatcher("/error.jsp").forward(request, response);
      return;
    }

    String submittedFileName;
    if ("external".equals(request.getParameter("caller"))) {
      submittedFileName = request.getParameter("fileName");
    } else {
      submittedFileName = file.getSubmittedFileName();
    }

    Session session = EcmManager.getInstance().getSession();

    Folder rootFolder = session.getRootFolder();

    Map<String, Object> properties = new HashMap<>();
    properties.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");

    String fileExtension = getFileExtension(submittedFileName).orElseThrow(RuntimeException::new);
    String fileName = UUID.randomUUID() + fileExtension;
    String mimeType = URLConnection.guessContentTypeFromName(submittedFileName);

    properties.put(PropertyIds.NAME, fileName);
    InputStream inputStream = file.getInputStream();

    ContentStream contentStream = session.getObjectFactory().createContentStream(fileName,
        -1, mimeType + "; charset=UTF-8", inputStream);

    Document document = rootFolder.createDocument(properties, contentStream, VersioningState.NONE);

    if ("external".equals(request.getParameter("caller"))) {
      response.getWriter().println(document.getId());
      return;
    }

    response.sendRedirect(request.getContextPath() + "/documents");
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String documentId = request.getParameter("documentId");

    if (documentId != null) {
      Session session = EcmManager.getInstance().getSession();

      try {
        CmisObject object = session.getObject(documentId);
        session.delete(object);
      } catch (CmisObjectNotFoundException e) {
        // do nothing
      }
    }

    if ("external".equals(request.getParameter("caller"))) {
      return;
    }
    response.sendRedirect(request.getContextPath() + "/documents");
  }

  private void copyContent(InputStream is, OutputStream os) {
    try (BufferedInputStream bis = new BufferedInputStream(is); BufferedOutputStream bos = new BufferedOutputStream(os)) {
      byte[] buffer = new byte[1024];
      int count;
      while ((count = bis.read(buffer)) >= 0) {
        bos.write(buffer, 0, count);
      }
    } catch (IOException e) {
      throw new RuntimeException("Can't copy stream");
    }
  }

  private Optional<String> getFileExtension(String filename) {
    return Optional.ofNullable(filename).filter(f -> f.contains("."))
        .map(f -> f.substring(filename.lastIndexOf(".") + 1));
  }

}