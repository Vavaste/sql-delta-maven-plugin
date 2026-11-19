package io.github.vavaste.sqldelta.engine.testmodel.schemas;

import jakarta.persistence.*;

@Entity
@Table(schema = "billing")
public class Ledger {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    Long id;
    @ManyToOne
    Account account;
}
