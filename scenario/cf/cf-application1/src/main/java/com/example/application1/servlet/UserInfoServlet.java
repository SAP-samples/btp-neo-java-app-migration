package com.example.application1.servlet;

import com.example.application1.dto.UserDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cloud.security.token.SecurityContext;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class UserInfoServlet extends HttpServlet {

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    Token token = SecurityContext.getAccessToken();

    if (token == null) {
      throw new ServletException("Access token is null");
    }

    String userName = token.getClaimAsString(TokenClaims.GIVEN_NAME);
    String email = token.getClaimAsString(TokenClaims.EMAIL);

    UserDto userDto = new UserDto(userName, email);

    String userDtoJson = new ObjectMapper().writeValueAsString(userDto);

    // Print the username and email
    response.getWriter().print(userDtoJson);
  }

}