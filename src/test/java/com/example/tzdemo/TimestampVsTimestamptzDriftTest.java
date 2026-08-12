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
 * Reproduces the bug:
 *
 *   Instant (Java)  +  `timestamp` == TIMESTAMP WITHOUT TIME ZONE (Postgres)
 *
 * with NO hibernate.jdbc.time_zone set (the default). We simulate two app
 * instances running in different JVM time zones: one WRITES the row, another
 * READS it back. The `timestamp` column drifts; the `timestamptz` column does not.
 */
// NOTE on Hibernate 7 (shipped with Spring Boot 4.1):
// Since Hibernate 6, `Instant` maps to the JDBC type TIMESTAMP_UTC by default,
// which binds/extracts using a UTC calendar and is therefore NOT affected by the
// JVM default zone or hibernate.jdbc.time_zone -> the naive drift below does not
// reproduce under the default. To demonstrate the classic failure mode we pin the
// legacy binding with `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP`, which
// routes Instant through the JVM/JDBC time zone exactly like older Hibernate did.
// Many real codebases carry this setting for backwards-compat, or hit the same
// drift via java.util.Date / Calendar, so this is a realistic reproduction.
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP")
@DisplayName("Instant + `timestamp without time zone` drifts across JVM time zones")
class TimestampVsTimestamptzDriftTest extends AbstractPostgresTest {

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
    void demonstrateDrift() {
        // --- Instance #1: runs in New York, writes the row -----------------
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); // UTC-5 in January
        Long id = txTemplate.execute(status -> {
            Example example = new Example();
            example.setValue(100);
            example.setCreatedAt(ORIGINAL);    // -> `timestamp` column (no zone)
            example.setCreatedAtTz(ORIGINAL);  // -> `timestamptz` column
            return exampleRepository.save(example).getId();
        });

        // What actually landed in each column, as a raw wall-clock string:
        String rawNoZone = jdbcTemplate.queryForObject(
                "SELECT to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') FROM example WHERE id = ?",
                String.class, id);
        String rawTzInUtc = jdbcTemplate.queryForObject(
                "SELECT to_char(created_at_tz AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM example WHERE id = ?",
                String.class, id);

        System.out.println("Original Instant            : " + ORIGINAL);
        System.out.println("Stored `timestamp`  (raw)   : " + rawNoZone + "   <- wall clock in New York (10:00Z -> 05:00)");
        System.out.println("Stored `timestamptz` (UTC)  : " + rawTzInUtc + "   <- true UTC instant preserved");

        // The zone-less column stored the New-York wall clock, NOT 10:00.
        assertThat(rawNoZone).isEqualTo("2024-01-15 05:00:00");
        // The timestamptz column preserved the real instant.
        assertThat(rawTzInUtc).isEqualTo("2024-01-15 10:00:00");

        // --- Instance #2: runs in Tokyo, reads the same row -----------------
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")); // UTC+9
        Example reloaded = txTemplate.execute(status ->
                exampleRepository.findById(id).orElseThrow());

        System.out.println("Read back `created_at`      : " + reloaded.getCreatedAt()   + "   <- DRIFTED");
        System.out.println("Read back `created_at_tz`   : " + reloaded.getCreatedAtTz() + "   <- correct");

        // BUG: the timestamp-without-zone value drifted. Tokyo reads the stored
        // "05:00" as Tokyo-local, producing 2024-01-14T20:00:00Z instead of 10:00Z.
        assertThat(reloaded.getCreatedAt())
                .as("Instant + timestamp-without-time-zone drifts when the JVM zone changes")
                .isNotEqualTo(ORIGINAL)
                .isEqualTo(Instant.parse("2024-01-14T20:00:00Z"));

        // CORRECT: the timestamptz value is stable regardless of JVM zone.
        assertThat(reloaded.getCreatedAtTz())
                .as("Instant + timestamptz is zone-independent")
                .isEqualTo(ORIGINAL);
    }
}
