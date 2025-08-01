package com.example.application2;

import com.sap.security.um.service.UserManagementAccessor;
import com.sap.security.um.user.User;
import com.sap.security.um.user.UserProvider;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


public class HomeServlet extends HttpServlet {

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    if (request.getUserPrincipal() != null) {
      try {
        // UserProvider provides access to the user storage
        UserProvider users = UserManagementAccessor.getUserProvider();

        // Read the currently logged-in user from the user storage
        User user = users.getUser(request.getUserPrincipal().getName());

        // Print the username and email
        request.setAttribute("username", user.getAttribute("firstname") + " " + user.getAttribute("lastname"));
        request.setAttribute("email", user.getAttribute("email"));
      } catch (Exception e) {
        request.setAttribute("message", e.getMessage());
        request.getRequestDispatcher("/error.jsp").forward(request, response);
      }

      request.getRequestDispatcher("/home.jsp").forward(request, response);
    }
  }

}