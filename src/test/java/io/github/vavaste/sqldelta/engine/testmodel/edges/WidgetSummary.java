package io.github.vavaste.sqldelta.engine.testmodel.edges;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/** A read model that a view of the database can back. */
@Entity
@Immutable
public class WidgetSummary {
    @Id
    Long id;
    String name;
}
