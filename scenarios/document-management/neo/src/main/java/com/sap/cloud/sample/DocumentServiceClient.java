package com.sap.cloud.sample;

import com.sap.ecm.api.EcmService;
import com.sap.ecm.api.RepositoryNotEmptyException;
import com.sap.ecm.api.RepositoryOptions;
import com.sap.ecm.api.RepositoryOptions.Visibility;
import com.sap.ecm.api.ServiceException;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

import javax.naming.InitialContext;
import javax.naming.NamingException;

public final class DocumentServiceClient {

    public static final String SECRET_KEY = "Espm@1234567890";
    private final EcmService ecmService;

    public DocumentServiceClient() throws ServiceException {
        try {
            InitialContext ctx = new InitialContext();
            String lookupName = "java:comp/env/EcmService";
            this.ecmService = (EcmService) ctx.lookup(lookupName);
        } catch (NamingException| ExceptionInInitializerError namingException) {
            throw new ServiceException(namingException.getMessage());
        }
    }

    public Session getSession(String uniqueName) throws ServiceException, CmisObjectNotFoundException {
        try {
            return ecmService.connect(uniqueName, SECRET_KEY);
        } catch (ServiceException serviceException) {
            throw new ServiceException(serviceException.getMessage());
        }
    }

    public String createRepository(String uniqueName) {
        RepositoryOptions options = new RepositoryOptions();
        options.setUniqueName(uniqueName);
        options.setRepositoryKey(SECRET_KEY);
        options.setVisibility(Visibility.PROTECTED);
        return ecmService.createRepository(options);
    }

    public void deleteRepository(String uniqueName) throws ServiceException, CmisObjectNotFoundException, RepositoryNotEmptyException {
        ecmService.deleteRepository(uniqueName, null);
    }
    public void forceDeleteRepository(String uniqueName) throws ServiceException, CmisObjectNotFoundException{
        ecmService.forceDeleteRepository(uniqueName, null);
    }
}
