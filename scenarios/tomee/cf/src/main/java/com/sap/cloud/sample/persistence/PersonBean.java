package com.sap.cloud.sample.persistence;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;


/**
 * Bean encapsulating all operations for a person.
 */
@Stateless
@LocalBean
public class PersonBean {
    @PersistenceContext
    private EntityManager em;

    /**
     * Get all persons from the table.
     */
    public List<Person> getAllPersons() {
        return em.createNamedQuery("AllPersons", Person.class).getResultList();
    }

    /**
     * Add a person to the table.
     */
    public void addPerson(Person person) {
        em.persist(person);
        em.flush();
    }
}
