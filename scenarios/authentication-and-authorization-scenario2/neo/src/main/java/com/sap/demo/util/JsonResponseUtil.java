package com.sap.demo.util;

import org.json.JSONObject;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Utility class for formatting and sending JSON responses
 */
public class JsonResponseUtil {

    /**
     * Send a JSON response with the given data
     * 
     * @param response HttpServletResponse object
     * @param data Map containing the data to be sent as JSON
     * @throws IOException if an I/O error occurs
     */
    public static void sendJsonResponse(HttpServletResponse response, Map<String, Object> data) throws IOException {
        JSONObject jsonObject = new JSONObject(data);
        sendJsonResponse(response, jsonObject, HttpServletResponse.SC_OK);
    }

    /**
     * Send a JSON response with the given data and status code
     * 
     * @param response HttpServletResponse object
     * @param data Map containing the data to be sent as JSON
     * @param statusCode HTTP status code
     * @throws IOException if an I/O error occurs
     */
    public static void sendJsonResponse(HttpServletResponse response, Map<String, Object> data, int statusCode) throws IOException {
        JSONObject jsonObject = new JSONObject(data);
        sendJsonResponse(response, jsonObject, statusCode);
    }

    /**
     * Send a JSON response with the given JSONObject
     * 
     * @param response HttpServletResponse object
     * @param jsonObject JSONObject to be sent
     * @param statusCode HTTP status code
     * @throws IOException if an I/O error occurs
     */
    public static void sendJsonResponse(HttpServletResponse response, JSONObject jsonObject, int statusCode) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);
        
        PrintWriter out = response.getWriter();
        out.print(jsonObject.toString(2)); // Pretty print with 2 spaces indentation
    }

    /**
     * Send a success JSON response
     * 
     * @param response HttpServletResponse object
     * @param message Success message
     * @param data Additional data to include
     * @throws IOException if an I/O error occurs
     */
    public static void sendSuccessResponse(HttpServletResponse response, String message, Map<String, Object> data) throws IOException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("status", "success");
        jsonObject.put("message", message);
        jsonObject.put("timestamp", System.currentTimeMillis());
        
        if (data != null && !data.isEmpty()) {
            jsonObject.put("data", new JSONObject(data));
        }
        
        sendJsonResponse(response, jsonObject, HttpServletResponse.SC_OK);
    }

    /**
     * Send an error JSON response
     * 
     * @param response HttpServletResponse object
     * @param message Error message
     * @param statusCode HTTP status code
     * @throws IOException if an I/O error occurs
     */
    public static void sendErrorResponse(HttpServletResponse response, String message, int statusCode) throws IOException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("status", "error");
        jsonObject.put("message", message);
        jsonObject.put("timestamp", System.currentTimeMillis());
        
        sendJsonResponse(response, jsonObject, statusCode);
    }

    /**
     * Send an unauthorized error response
     * 
     * @param response HttpServletResponse object
     * @throws IOException if an I/O error occurs
     */
    public static void sendUnauthorizedResponse(HttpServletResponse response) throws IOException {
        sendErrorResponse(response, "Unauthorized access", HttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * Send a forbidden error response
     * 
     * @param response HttpServletResponse object
     * @throws IOException if an I/O error occurs
     */
    public static void sendForbiddenResponse(HttpServletResponse response) throws IOException {
        sendErrorResponse(response, "Access forbidden", HttpServletResponse.SC_FORBIDDEN);
    }
}