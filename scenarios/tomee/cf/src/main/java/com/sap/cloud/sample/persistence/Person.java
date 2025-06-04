package com.sap.cloud.sample.persistence;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Class holding information on a person.
 */
@Entity
@Table(name = "T_PERSONS")
@NamedQuery(name = "AllPersons", query = "select p from Person p")
public class Person {
    @Id
    private String id;
    @Basic
    private String firstName;
    @Basic
    private String lastName;

    /**
     * Create a Person instance with unique id.
     */
    public Person() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String newId) {
        this.id = newId;
    }

    public String getFirstName() {
        return this.firstName;
    }

    public void setFirstName(String newFirstName) {
        this.firstName = newFirstName;
    }

    public String getLastName() {
        return this.lastName;
    }

    public void setLastName(String newLastName) {
        this.lastName = newLastName;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Person)) {
            return false;
        }
        return getId().equals(((Person) obj).getId());
    }
}