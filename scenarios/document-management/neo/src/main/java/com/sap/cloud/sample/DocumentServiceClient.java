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

    // Set at deploy time, e.g. neo deploy ... --ev ECM_REPOSITORY_KEY=<key>
    private static final String REPOSITORY_KEY_ENV = "ECM_REPOSITORY_KEY";
    private final EcmService ecmService;
    private final String repositoryKey;

    public DocumentServiceClient() throws ServiceException {
        try {
            InitialContext ctx = new InitialContext();
            String lookupName = "java:comp/env/EcmService";
            this.ecmService = (EcmService) ctx.lookup(lookupName);
        } catch (NamingException| ExceptionInInitializerError namingException) {
            throw new ServiceException(namingException.getMessage());
        }
        this.repositoryKey = System.getenv(REPOSITORY_KEY_ENV);
        if (this.repositoryKey == null || this.repositoryKey.isEmpty()) {
            throw new ServiceException("Repository key is not configured. Set the " + REPOSITORY_KEY_ENV
                    + " environment variable.");
        }
    }

    public Session getSession(String uniqueName) throws ServiceException, CmisObjectNotFoundException {
        try {
            return ecmService.connect(uniqueName, repositoryKey);
        } catch (ServiceException serviceException) {
            throw new ServiceException(serviceException.getMessage());
        }
    }

    public String createRepository(String uniqueName) {
        RepositoryOptions options = new RepositoryOptions();
        options.setUniqueName(uniqueName);
        options.setRepositoryKey(repositoryKey);
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
