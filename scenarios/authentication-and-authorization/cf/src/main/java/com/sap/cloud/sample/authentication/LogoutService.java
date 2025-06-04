package com.sap.cloud.sample.authentication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class LogoutService {
    public void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Invalidate the session to log out the user
        request.getSession().invalidate();
    }
}
