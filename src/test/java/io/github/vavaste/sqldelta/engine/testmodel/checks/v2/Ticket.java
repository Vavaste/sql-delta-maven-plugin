package io.github.vavaste.sqldelta.engine.testmodel.checks.v2;

import static jakarta.persistence.EnumType.ORDINAL;
import static jakarta.persistence.EnumType.STRING;
import static org.hibernate.type.SqlTypes.VARCHAR;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.YesNoConverter;

@Entity
@Table(name = "ticket")
public class Ticket {

    public enum Priority { LOW, NORMAL, URGENT, CRITICAL }
    public enum Status { OPEN, REOPENED, CLOSED }
    public enum Channel { EMAIL, PHONE }
    public enum Mood { HAPPY, SAD }
    public enum Kind { BUG, TASK }
    public enum Single { ONLY }
    public enum LegacyCode { A1, B2 }

    @Id Long id;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Priority priority;          // constant added
    @Enumerated(ORDINAL) Status status;                                     // ORDINAL constant inserted
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) @Column(name = "status_code") Channel statusCode;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) @Column(nullable = false) Mood mood; // constant removed
    @Enumerated(ORDINAL) Kind kind;                                         // STRING to ORDINAL
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Single lonely;
    @Convert(converter = YesNoConverter.class) boolean active;
    String motto;                                                           // @Check removed
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) @Column(name = "legacy_code") LegacyCode legacyCode; // was a String
    @Column(nullable = false) int grade;
    @Enumerated(STRING) @JdbcTypeCode(VARCHAR) Channel channel;             // new column
}
