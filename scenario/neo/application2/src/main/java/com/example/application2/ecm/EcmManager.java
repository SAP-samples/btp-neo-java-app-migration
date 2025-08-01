package com.example.application2.ecm;

import com.sap.ecm.api.EcmService;
import com.sap.ecm.api.RepositoryOptions;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

import javax.naming.InitialContext;
import javax.naming.NamingException;

public class EcmManager {
  private static final String UNIQUE_NAME = "test_repository";
  private static final String SECRET_KEY = "test_repository_secret_key";

  private static EcmManager instance;
  private final EcmService ecmService;

  private EcmManager() {
    try {
      InitialContext ctx = new InitialContext();
      String lookupName = "java:comp/env/EcmService";
      ecmService = (EcmService) ctx.lookup(lookupName);

    } catch (NamingException e) {
      throw new RuntimeException("Can't lookup EcmService");
    }
  }

  public static synchronized EcmManager getInstance() {
    if (instance == null) {
      instance = new EcmManager();
    }

    return instance;
  }


  public Session getSession() {
    Session session;
    try {
      // connect to repository
      session = ecmService.connect(UNIQUE_NAME, SECRET_KEY);
    } catch (CmisObjectNotFoundException e) {
      // repository does not exist, so try to create it
      createRepository();
      session = ecmService.connect(UNIQUE_NAME, SECRET_KEY);
    }
    return session;
  }

  private void createRepository() {
    RepositoryOptions options = new RepositoryOptions();
    options.setUniqueName(EcmManager.UNIQUE_NAME);
    options.setRepositoryKey(EcmManager.SECRET_KEY);
    options.setVisibility(RepositoryOptions.Visibility.PROTECTED);
    ecmService.createRepository(options);
  }
}
