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
 * Runs with the Hibernate 7 DEFAULT Instant binding (no
 * preferred_instant_jdbc_type override), i.e. TIMESTAMP_UTC.
 *
 * The point of this test is the counterpart to {@link TimestampVsTimestamptzDriftTest}:
 *
 *  - On the CORRECT column type (`timestamptz` / created_at_tz), the default
 *    TIMESTAMP_UTC binding round-trips the Instant with ZERO drift across JVM
 *    time zones. This is the certain, headline assertion.
 *
 *  - On the zone-less column (`timestamp` / created_at), the default binding does
 *    NOT make the pairing safe. Hibernate would emit `timestamptz` for this field
 *    under schema generation, so `ddl-auto: validate` REJECTS the zone-less column
 *    (see README). At runtime, writing a UTC/offset value into a zone-less column
 *    goes through an implicit `timestamptz -> timestamp` cast using the Postgres
 *    session time zone, which is driver/session dependent. We therefore only
 *    OBSERVE (print) that column here rather than asserting an exact value.
 */
@SpringBootTest // no preferred_instant_jdbc_type -> Hibernate 7 default (TIMESTAMP_UTC)
@DisplayName("Hibernate 7 default binding (TIMESTAMP_UTC): timestamptz is drift-free")
class DefaultBindingTest extends AbstractPostgresTest {

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
    void timestamptzIsDriftFreeUnderTheDefault() {
        // Write from New York...
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Long id = txTemplate.execute(status -> {
            Example example = new Example();
            example.setValue(100);
            example.setCreatedAt(ORIGINAL);
            example.setCreatedAtTz(ORIGINAL);
            return exampleRepository.save(example).getId();
        });

        // Observe what each column stored (the zone-less one is driver/session dependent).
        String rawNoZone = jdbcTemplate.queryForObject(
                "SELECT to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') FROM example WHERE id = ?",
                String.class, id);
        String rawTzInUtc = jdbcTemplate.queryForObject(
                "SELECT to_char(created_at_tz AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM example WHERE id = ?",
                String.class, id);
        System.out.println("[default binding] stored `timestamp`  (raw)  : " + rawNoZone + "   (driver/session dependent - observe only)");
        System.out.println("[default binding] stored `timestamptz` (UTC) : " + rawTzInUtc);

        // ...read back from Tokyo.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        Example reloaded = txTemplate.execute(status ->
                exampleRepository.findById(id).orElseThrow());

        System.out.println("[default binding] read back `created_at`     : " + reloaded.getCreatedAt()   + "   (observe only)");
        System.out.println("[default binding] read back `created_at_tz`  : " + reloaded.getCreatedAtTz());

        // Clear verdict for the zone-less column under the default TIMESTAMP_UTC binding:
        boolean zoneLessDrifted = !reloaded.getCreatedAt().equals(ORIGINAL);
        System.out.println("[default binding] `created_at` (timestamp, no zone) drifted? " + zoneLessDrifted
                + "   original=" + ORIGINAL + " readBack=" + reloaded.getCreatedAt());

        // CERTAIN: with the correct column type, the Hibernate 7 default binding
        // is zone-independent -> the Instant survives a cross-zone round trip.
        assertThat(reloaded.getCreatedAtTz())
                .as("Under the Hibernate 7 default, Instant + timestamptz does not drift")
                .isEqualTo(ORIGINAL);
    }
}
