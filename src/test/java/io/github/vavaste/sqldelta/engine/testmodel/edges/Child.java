package io.github.vavaste.sqldelta.engine.testmodel.edges;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
public class Child {
    @Id
    Long id;
    @ManyToOne
    @OnDelete(action = OnDeleteAction.CASCADE)
    Parent parent;
}
