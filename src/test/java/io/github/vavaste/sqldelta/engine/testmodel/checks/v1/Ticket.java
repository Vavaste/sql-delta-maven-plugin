package io.github.vavaste.sqldelta.engine.testmodel.checks.v1;

import static jakarta.persistence.EnumType.ORDINAL;
import static jakarta.persistence.EnumType.STRING;
import static org.hibernate.type.SqlTypes.VARCHAR;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.YesNoConverter;

@Entity
@Table(name = "ticket")
public class Ticket {

    public enum Priority { LOW, NORMAL, URGENT }
    public enum Status { OPEN, CLOSED }
    public enum Channel { EMAIL, PHONE }
    public enum Mood { HAPPY, SAD, ANGRY }
    public enum Kind { BUG, TASK }
    public enum Single { ONLY }

    @Id Long id;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Priority priority;
    @Enumerated(ORDINAL) Status status;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) @Column(name = "status_code") Channel statusCode;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) @Column(nullable = false) Mood mood;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Kind kind;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Single lonely;
    @Convert(converter = YesNoConverter.class) boolean active;
    @Check(constraints = "motto in ('it''s fine','a, b','naïve café','smile 😀','back\\slash')") String motto;
    @Column(name = "legacy_code") String legacyCode;
    @Column(nullable = false) int grade;
}
