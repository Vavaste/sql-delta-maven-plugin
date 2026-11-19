package io.github.vavaste.sqldelta.engine.testmodel.store.v2;

import java.time.Instant;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A new table, with a sequence whose increment Liquibase cannot read on H2 and a default the databases give back as
 * another function.
 */
@Entity
public class Coupon {
    @Id
    @GeneratedValue(generator = "coupon_seq")
    @SequenceGenerator(name = "coupon_seq", allocationSize = 20)
    Long id;
    @ManyToOne
    @OnDelete(action = OnDeleteAction.CASCADE)
    Customer customer;
    @Column(unique = true)
    String code;
    @ColumnDefault("CURRENT_TIMESTAMP")
    Instant createdAt;
}
