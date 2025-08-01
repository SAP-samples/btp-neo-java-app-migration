package com.example.application2;

import com.example.application2.ecm.EcmManager;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.Session;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;

@MultipartConfig
public class DocumentsPageServlet extends HttpServlet {

  private static final String GET_IMAGE_URL = "api/documents";

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    Session session = EcmManager.getInstance().getSession();

    HashMap<String, String> documents = new HashMap<>();

    Folder rootFolder = session.getRootFolder();
    ItemIterable<CmisObject> children = rootFolder.getChildren();
    for (CmisObject o : children) {
      if (o instanceof Document) {
        Document document = (Document) o;
        String documentId = document.getId();
        documents.put(documentId, GET_IMAGE_URL + "?documentId=" + documentId);
      }
    }

    request.setAttribute("documents", documents);

    request.getRequestDispatcher("/documents.jsp").forward(request, response);
  }

}