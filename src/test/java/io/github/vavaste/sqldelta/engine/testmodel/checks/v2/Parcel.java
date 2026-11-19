package io.github.vavaste.sqldelta.engine.testmodel.checks.v2;

import static jakarta.persistence.EnumType.STRING;
import static org.hibernate.type.SqlTypes.VARCHAR;

import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "parcel")
@Check(name = "parcel_light_or_courier", constraints = "weight < 100 or carrier = 'COURIER'")
public class Parcel {

    public enum Carrier { POST, COURIER }

    @Id Long id;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Carrier carrier;
    @Check(constraints = "weight >= 0") int weight;
}
