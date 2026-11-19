package io.github.vavaste.sqldelta.engine.testmodel.edges;

import jakarta.persistence.*;

/** A composite primary key, whose columns Hibernate orders by type: number, then code. */
@Entity
public class Parent {

    @Embeddable
    public record Key(String code, Long number) {
    }

    @EmbeddedId
    Key key;
}
