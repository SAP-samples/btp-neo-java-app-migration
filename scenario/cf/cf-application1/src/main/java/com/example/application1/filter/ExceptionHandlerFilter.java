package com.example.application1.filter;

import com.example.application1.dto.ExceptionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class ExceptionHandlerFilter extends HttpFilter {

  @Override
  protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
    try {
      super.doFilter(request, response, chain);
    } catch (Exception e) {
      Throwable rootCause = e;
      while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
        rootCause = rootCause.getCause();
      }
      String message = rootCause.getMessage();

      ExceptionDto exceptionDto = new ExceptionDto(message);
      String json = new ObjectMapper().writeValueAsString(exceptionDto);

      response.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
      response.getWriter().print(json);
    }
  }
}
