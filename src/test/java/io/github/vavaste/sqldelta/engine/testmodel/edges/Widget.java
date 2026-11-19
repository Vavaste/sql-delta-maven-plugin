package io.github.vavaste.sqldelta.engine.testmodel.edges;

import jakarta.persistence.*;

/** An identity id, and a columnDefinition with its not null before the default. */
@Entity
public class Widget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String name;
    @Column(columnDefinition = "varchar(20) not null default 'new'")
    String state;
}
