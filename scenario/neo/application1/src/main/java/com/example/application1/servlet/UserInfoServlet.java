package com.example.application1.servlet;

import com.example.application1.dto.UserDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.security.um.service.UserManagementAccessor;
import com.sap.security.um.user.User;
import com.sap.security.um.user.UserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class UserInfoServlet extends HttpServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(UserInfoServlet.class);

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
    if (request.getUserPrincipal() == null) {
      throw new ServletException("User principal is null");
    }

    try {
      // UserProvider provides access to the user storage
      UserProvider users = UserManagementAccessor.getUserProvider();

      // Read the currently logged-in user from the user storage
      User user = users.getUser(request.getUserPrincipal().getName());

      String username = user.getAttribute("firstname") + " " + user.getAttribute("lastname");
      String email = user.getAttribute("email");

      UserDto userDto = new UserDto(username, email);
      String userDtoJson = new ObjectMapper().writeValueAsString(userDto);

      response.getWriter().print(userDtoJson);
    } catch (Exception e) {
      LOGGER.error("Exception occurred: " + e.getMessage(), e);

      throw new ServletException(e);
    }
  }
}
