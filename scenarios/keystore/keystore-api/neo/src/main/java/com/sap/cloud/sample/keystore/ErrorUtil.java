package com.sap.cloud.sample.keystore;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

public class ErrorUtil {
    public static void printErrorToResponse(HttpServletResponse response, String errorMessage, int status) throws IOException {
        printErrorToResponse(response, null, errorMessage, status);
    }

    public static void printErrorToResponse(HttpServletResponse response, Throwable throwable, String errorMessage, int status) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            response.setContentType("text/plain");
            response.getWriter().println(errorMessage);
            if (throwable != null) {
                throwable.printStackTrace(response.getWriter());
            }
        } else {
            throw new RuntimeException("Response is committed. Cannot write actual error message");
        }
    }

}