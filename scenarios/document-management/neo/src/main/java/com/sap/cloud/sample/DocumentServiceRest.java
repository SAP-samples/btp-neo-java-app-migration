package com.sap.cloud.sample;

import com.sap.ecm.api.RepositoryAlreadyExistsException;
import com.sap.ecm.api.RepositoryNotEmptyException;
import com.sap.ecm.api.RepositoryQuotaExceededException;
import com.sap.ecm.api.ServiceException;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.json.simple.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path(value = "/ecm")
public class DocumentServiceRest extends HttpServlet {
  public static final String MESSAGE = "message";
  private DocumentServiceClient documentServiceClient = new DocumentServiceClient();

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getSession(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) throws ServiceException {
    Session session;
    try {
      session = documentServiceClient.getSession(uniqueName);
    } catch (CmisObjectNotFoundException e){
        return Response.status(Response.Status.NOT_FOUND).entity(formatResponse(e.getMessage())).build();
    }
    return Response.status(Response.Status.OK).entity(formatResponse(session.toString())).build();
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public Response createRepository(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) throws ServiceException {
    String repoId;
    try {
      repoId = documentServiceClient.createRepository(uniqueName);
    } catch (RepositoryAlreadyExistsException | RepositoryQuotaExceededException e){
      return Response.status(Response.Status.PRECONDITION_FAILED).entity(formatResponse(e.getMessage())).build();
    } catch (ServiceException e){
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(formatResponse(e.getMessage())).build();
    }
    return Response.status(Response.Status.CREATED).entity(formatResponse(String.format("Repository with id: %s created.", repoId))).build();
  }

  @DELETE
  @Produces(MediaType.APPLICATION_JSON)
  public Response deleteRepository(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) throws ServiceException {
    try {
      documentServiceClient.deleteRepository(uniqueName);
    } catch (CmisObjectNotFoundException e){
      return Response.status(Response.Status.NOT_FOUND).entity(formatResponse(e.getMessage())).build();
    } catch (CmisInvalidArgumentException | RepositoryNotEmptyException e){
      return Response.status(Response.Status.PRECONDITION_FAILED).entity(formatResponse(e.getMessage())).build();
    } catch (ServiceException  e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(formatResponse(e.getMessage())).build();
    }
    return Response.status(Response.Status.OK).entity(formatResponse(String.format("Empty repository with uniqueName: %s deleted.", uniqueName))).build();
  }

  @DELETE
  @Path("/force")
  @Produces(MediaType.APPLICATION_JSON)
  public Response forceDeleteRepository(@Context HttpServletRequest request, @QueryParam("uniqueName") String uniqueName) throws ServiceException {
    try {
      documentServiceClient.forceDeleteRepository(uniqueName);
    } catch (CmisObjectNotFoundException e){
      return Response.status(Response.Status.NOT_FOUND).entity(formatResponse(e.getMessage())).build();
    } catch (CmisInvalidArgumentException e){
      return Response.status(Response.Status.PRECONDITION_FAILED).entity(formatResponse(e.getMessage())).build();
    } catch (ServiceException  e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(formatResponse(e.getMessage())).build();
    }
    return Response.status(Response.Status.OK).entity(formatResponse(String.format("Repository with uniqueName: %s deleted.", uniqueName))).build();
  }

  private String formatResponse(String message){
    JSONObject json = new JSONObject();
    json.put(MESSAGE, message);
    return json.toString();
  }

}