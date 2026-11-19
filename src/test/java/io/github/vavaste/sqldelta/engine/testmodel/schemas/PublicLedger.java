package io.github.vavaste.sqldelta.engine.testmodel.schemas;

import jakarta.persistence.*;

/** A table of the same name as Ledger's, in the compared schema. */
@Entity
@Table(name = "ledger")
public class PublicLedger {
    @Id
    Long id;
}
