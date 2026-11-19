package io.github.vavaste.sqldelta.engine.testmodel.store.v2;

import java.math.BigDecimal;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

/** The store after the change, one change per column; see PostgresScenarioTest for what each one expects. */
@Entity
@Table(indexes = {@Index(name = "idx_customer_name", columnList = "name"),
        @Index(name = "idx_customer_email", columnList = "email")})
public class Customer {
    @Id
    Long id;
    @Column(length = 40)
    String name;
    @Column(length = 500, nullable = false)
    String city;
    String nickname;
    @Column(nullable = false)
    int tier;
    @ColumnDefault("0")
    Long points;
    @Column(precision = 12, scale = 2)
    BigDecimal balance;
    Integer rating;
    String email;
    @Column(columnDefinition = "varchar(20) default 'new' not null")
    String status;
}
