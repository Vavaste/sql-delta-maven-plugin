package io.github.vavaste.sqldelta.engine.testmodel.store.v1;

import jakarta.persistence.*;

@Entity
public class PurchaseOrder {
    @Id
    Long id;
    @ManyToOne
    Customer customer;
}
