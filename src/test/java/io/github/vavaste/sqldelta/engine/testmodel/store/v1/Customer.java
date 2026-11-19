package io.github.vavaste.sqldelta.engine.testmodel.store.v1;

import java.math.BigDecimal;

import jakarta.persistence.*;

/** The store before the change: the scenario creates its database from this model. */
@Entity
@Table(indexes = {@Index(name = "idx_customer_city", columnList = "city"),
        @Index(name = "idx_customer_email", columnList = "email", unique = true)})
public class Customer {
    @Id
    Long id;
    @Column(length = 100)
    String name;
    String city;
    @Column(nullable = false)
    String legacyCode;
    Integer points;
    @Column(precision = 10, scale = 2)
    BigDecimal balance;
    String rating;
    String email;
    @Column(columnDefinition = "varchar(20) default 'new' not null")
    String status;
}
