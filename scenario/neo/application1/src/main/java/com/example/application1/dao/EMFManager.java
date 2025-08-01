package com.example.application1.dao;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

public class EMFManager {

  private static EMFManager instance;

  private final EntityManagerFactory entityManagerFactory;

  private EMFManager() {
    entityManagerFactory = Persistence.createEntityManagerFactory("application1");
  }

  public static synchronized EMFManager getInstance() {
    if (instance == null) {
      instance = new EMFManager();
    }
    return instance;
  }

  public EntityManager getEntityManager() {
    if (entityManagerFactory == null) {
      throw new RuntimeException("EMF is null");
    }
    return entityManagerFactory.createEntityManager();
  }
}
