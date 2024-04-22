package com.example.application2;

import com.sap.cloud.security.token.SecurityContext;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class HomeServlet extends HttpServlet {

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    Token token = SecurityContext.getAccessToken();

    if (token == null) {
      request.setAttribute("message", "Access token is null");
      request.getRequestDispatcher("/error.jsp").forward(request, response);
      return;
    }

    String userName = token.getClaimAsString(TokenClaims.USER_NAME);
    String email = token.getClaimAsString(TokenClaims.EMAIL);

    // Print the username and email
    request.setAttribute("username", userName);
    request.setAttribute("email", email);

    request.getRequestDispatcher("/home.jsp").forward(request, response);
  }

}