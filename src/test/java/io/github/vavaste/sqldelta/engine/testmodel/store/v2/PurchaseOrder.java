package io.github.vavaste.sqldelta.engine.testmodel.store.v2;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_purchase_order_number", columnNames = "number"))
public class PurchaseOrder {
    @Id
    Long id;
    String number;
    @ManyToOne
    @OnDelete(action = OnDeleteAction.CASCADE)
    Customer customer;
}
