package com.example.application1.dao;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

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
