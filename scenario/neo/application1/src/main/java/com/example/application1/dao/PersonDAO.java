package com.example.application1.dao;

import com.example.application1.model.Person;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import java.util.List;

public class PersonDAO {

  public List<Person> findAll() {
    EntityManager entityManager = EMFManager.getInstance().getEntityManager();
    try {
      TypedQuery<Person> query = entityManager.createQuery("Select p FROM Person p", Person.class);

      return query.getResultList();
    } finally {
      entityManager.close();
    }
  }

  public Person findById(Long id) {
    EntityManager entityManager = EMFManager.getInstance().getEntityManager();
    try {
      TypedQuery<Person> query = entityManager.createQuery("Select p FROM Person p WHERE p.id = :id", Person.class);
      query.setParameter("id", id);

      return query.getSingleResult();
    } finally {
      entityManager.close();
    }
  }

  public void save(Person person) {
    EntityManager entityManager = EMFManager.getInstance().getEntityManager();
    try {
      entityManager.getTransaction().begin();
      entityManager.persist(person);
      entityManager.getTransaction().commit();
    } catch (Exception e) {
      entityManager.getTransaction().rollback();
    } finally {
      entityManager.close();
    }
  }

  public void delete(Long id) {
    EntityManager entityManager = EMFManager.getInstance().getEntityManager();
    try {
      entityManager.getTransaction().begin();

      Query query = entityManager.createQuery("DELETE FROM Person p WHERE p.id = :id");
      query.setParameter("id", id);
      query.executeUpdate();

      entityManager.getTransaction().commit();
    } catch (Exception e) {
      entityManager.getTransaction().rollback();
    } finally {
      entityManager.close();
    }
  }

}
