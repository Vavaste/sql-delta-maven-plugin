package io.github.vavaste.sqldelta.engine.testmodel.schemas;

import jakarta.persistence.*;

/** The one table the run compares: Ledger is in schema billing, and PublicLedger shares Ledger's table name. */
@Entity
public class Account {
    @Id
    Long id;
    String owner;
}
