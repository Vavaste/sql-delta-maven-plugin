package io.github.vavaste.sqldelta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The regular expressions of {@link CheckConstraints} on texts captured from Hibernate 6.6 and 7.2, PostgreSQL 15 and
 * 18 and H2 2.4, and the constraint names PostgreSQL 18 gave the same tables.
 */
class CheckConstraintsParseTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            priority in ('LOW','NORMAL','URGENT')                                    | priority [LOW, NORMAL, URGENT]
            motto in ('it''s fine','a, b','naïve café','smile 😀','back\\slash')      | motto [a, b, back\\slash, it's fine, naïve café, smile 😀]
            lonely = 'ONLY'                                                          | lonely [ONLY]
            CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'NORMAL'::character varying])::text[]))) | priority [LOW, NORMAL]
            CHECK (((motto)::text = ANY ((ARRAY['it''s fine'::character varying, 'a, b'::character varying, 'naïve café'::character varying, 'smile 😀'::character varying, 'back\\slash'::character varying])::text[]))) | motto [a, b, back\\slash, it's fine, naïve café, smile 😀]
            CHECK ((active = ANY (ARRAY['N'::bpchar, 'Y'::bpchar])))                 | active [N, Y]
            CHECK (((lonely)::text = 'ONLY'::text))                                  | lonely [ONLY]
            "PRIORITY" IN('LOW', 'NORMAL')                                           | PRIORITY [LOW, NORMAL]
            "MOTTO" IN('it''s fine', 'a, b', U&'na\\00efve caf\\00e9', U&'smile \\+01f600', 'back\\slash') | MOTTO [a, b, back\\slash, it's fine, naïve café, smile 😀]
            "CODE" IN(U&'\\00e9\\\\x', 'plain')                                      | CODE [plain, é\\x]
            "LONELY" = 'ONLY'                                                        | LONELY [ONLY]
            status between 0 and 2                                                   | status 0..2
            CHECK (((status >= 0) AND (status <= 2)))                                | status 0..2
            "STATUS" BETWEEN 0 AND 2                                                 | STATUS 0..2
            weight >= 0                                                              | custom
            weight < 100 or carrier = 'COURIER'                                      | custom
            CHECK (((weight < 100) OR ((carrier)::text = 'COURIER'::text)))          | custom
            CHECK (((low >= 0) AND (high <= 2)))                                     | custom
            kind in ('A') or code in ('B')                                           | custom
            "Mixed Case" in ('A','B')                                                | custom
            "WEIGHT" >= 0                                                            | custom
            """)
    void readsTheColumnAndTheAcceptedValuesOfEveryRendering(String condition, String expected) {
        CheckConstraints.Check check = CheckConstraints.parse(null, condition);
        String accepted = check == null ? "custom"
                : check.column() + " " + (check.isRange() ? check.min() + ".." + check.max() : check.values());
        assertThat(accepted).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(delimiter = '|', textBlock = """
            ticket                                                  | status                                                   | check  | ticket_status_check
            ticket                                                  |                                                          | check  | ticket_check
            parcel_with_a_rather_long_name_to_force_name_truncation | carrier_with_an_extremely_long_column_name_that_goes_on_1 | check  | parcel_with_a_rather_long_na_carrier_with_an_extremely_lo_check
            parcel_with_a_rather_long_name_to_force_name_truncation | carrier_with_an_extremely_long_column_name_that_goes_on_1 | check1 | parcel_with_a_rather_long_na_carrier_with_an_extremely_l_check1
            téééééééééééééééééééééééééééééé                         | c                                                        | check  | tééééééééééééééééééééééééééé_c_check
            """)
    void namesNewChecksTheWayPostgresqlDoes(String table, String column, String label, String expected) {
        assertThat(CheckConstraints.objectName(table, column, label)).isEqualTo(expected);
    }
}
