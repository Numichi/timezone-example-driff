package com.example.tzdemo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same scenario as {@link TimestampVsTimestamptzDriftTest}, but with the
 * config-level fix applied:
 *
 *   spring.jpa.properties.hibernate.jdbc.time_zone=UTC
 *
 * Now Hibernate always binds/extracts the `timestamp` column using a UTC
 * calendar, so the value no longer depends on the JVM default zone and the
 * drift disappears — even though the column is still TIMESTAMP WITHOUT TIME ZONE.
 */
// We pin the legacy Instant binding (preferred_instant_jdbc_type=TIMESTAMP) so that
// hibernate.jdbc.time_zone is actually the lever under test. Under Hibernate 7's
// default (TIMESTAMP_UTC) the value would already be UTC-stable for a different
// reason; pinning TIMESTAMP isolates the jdbc.time_zone=UTC fix on the same code
// path that produced the drift in TimestampVsTimestamptzDriftTest.
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
@DisplayName("hibernate.jdbc.time_zone=UTC removes the drift on a zone-less timestamp")
class JdbcTimeZoneUtcFixTest extends AbstractPostgresTest {

    private static final Instant ORIGINAL = Instant.parse("2024-01-15T10:00:00Z");

    @Autowired
    private ExampleRepository exampleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate txTemplate;

    private TimeZone originalDefaultZone;

    @BeforeEach
    void captureDefaultZone() {
        originalDefaultZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaultZone() {
        TimeZone.setDefault(originalDefaultZone);
    }

    @Test
    void noDriftWithUtcJdbcTimeZone() {
        // Write from New York...
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Long id = txTemplate.execute(status -> {
            Example example = new Example();
            example.setValue("100");
            example.setCreatedAt(ORIGINAL);
            example.setCreatedAtTz(ORIGINAL);
            return exampleRepository.save(example).getId();
        });

        // The zone-less column now stores the UTC wall clock (10:00), not 05:00.
        String rawNoZone = jdbcTemplate.queryForObject(
                "SELECT to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') FROM example WHERE id = ?",
                String.class, id);
        assertThat(rawNoZone).isEqualTo("2024-01-15 10:00:00");

        // ...read back from Tokyo: no drift.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        Example reloaded = txTemplate.execute(status ->
                exampleRepository.findById(id).orElseThrow());

        assertThat(reloaded.getCreatedAt())
                .as("With jdbc.time_zone=UTC the zone-less timestamp is stable")
                .isEqualTo(ORIGINAL);
    }
}
