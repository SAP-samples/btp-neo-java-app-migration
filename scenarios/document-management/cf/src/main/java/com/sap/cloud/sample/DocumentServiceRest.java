package com.sap.cloud.sample;

import com.sap.cloud.sample.exception.RepositoryAlreadyExistsException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.json.simple.JSONObject;

@Path(value = "/ecm")
public class DocumentServiceRest extends HttpServlet {
    public static final String MESSAGE = "message";
    private final DocumentServiceClient documentServiceClient = new DocumentServiceClient();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSession(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) {
        Session session;
        try {
            session = documentServiceClient.getSession(uniqueName);
        } catch (CmisObjectNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(formatResponse(e.getMessage())).build();
        }
        return Response.status(Response.Status.OK).entity(formatResponse(session.toString())).build();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response createRepository(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) {
        String repoId;
        try {
            repoId = documentServiceClient.createRepository(uniqueName);
        } catch (RepositoryAlreadyExistsException e) {
            return Response.status(Response.Status.PRECONDITION_FAILED).entity(formatResponse(e.toString())).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(formatResponse(e.getMessage()))
                    .build();
        }
        return Response.status(Response.Status.CREATED)
                .entity(formatResponse("Repository with id: %s created.".formatted(repoId))).build();
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteRepository(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) {
        try {
            documentServiceClient.deleteRepository(uniqueName);
        } catch (CmisObjectNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(formatResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(formatResponse(e.getMessage()))
                    .build();
        }
        return Response.status(Response.Status.OK)
                .entity(formatResponse("Empty repository with uniqueName: %s deleted.".formatted(uniqueName)))
                .build();
    }

    private String formatResponse(String message) {
        JSONObject json = new JSONObject();
        json.put(MESSAGE, message);
        return json.toString();
    }

}