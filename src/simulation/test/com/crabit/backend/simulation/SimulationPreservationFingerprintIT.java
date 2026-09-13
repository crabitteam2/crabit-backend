package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class SimulationPreservationFingerprintIT {
    @Test void readOnlyCaptureDetectsValuesSecretsMediaBaselinesAndRolledBackSequenceAllocation() {
        try (var runtime = new SimulationPostgresClock()) {
            var jdbc = new JdbcTemplate(runtime.dataSource());
            var initial = SimulationPreservationFingerprint.capture(runtime);
            assertThat(initial.tables()).hasSize(40);
            assertThat(SimulationPreservationFingerprint.compare(initial,SimulationPreservationFingerprint.capture(runtime)).unchanged()).isTrue();
            UUID student = UUID.randomUUID();
            jdbc.update("INSERT INTO student(id,nickname,age) VALUES (?,?,9)",student,"preservation-before");
            var before = SimulationPreservationFingerprint.capture(runtime);
            jdbc.update("UPDATE student SET nickname=? WHERE id=?","preservation-after",student);
            var after = SimulationPreservationFingerprint.capture(runtime);
            assertThat(before.tables().get("student").rows()).isEqualTo(after.tables().get("student").rows());
            assertThat(SimulationPreservationFingerprint.compare(before,after).changedTables()).containsExactly("student");
            assertThat(after.digest()).isNotEqualTo(before.digest());
            String secret = "SECRET-MUST-STAY-IN-POSTGRES";
            jdbc.update("UPDATE relationship_cursor_key SET secret=?",secret);
            var keyChanged = SimulationPreservationFingerprint.capture(runtime);
            assertThat(SimulationPreservationFingerprint.compare(after,keyChanged).changedTables()).containsExactly("relationship_cursor_key");
            jdbc.update("""
                INSERT INTO wish_photo_cleanup_work(photo_id,object_prefix,requested_at,next_attempt_at)
                VALUES (?, 'private-media-path-must-not-export',clock_timestamp(),clock_timestamp())
                """,UUID.randomUUID());
            var media = SimulationPreservationFingerprint.capture(runtime);
            assertThat(SimulationPreservationFingerprint.compare(keyChanged,media).changedTables()).containsExactly("wish_photo_cleanup_work");
            String serialized = JsonMapper.builder().build().writeValueAsString(media);
            assertThat(serialized).doesNotContain(secret,"private-media-path-must-not-export","preservation-after");
            jdbc.update("UPDATE feed_history_collection SET started_at=started_at+interval '1 second'");
            var baseline = SimulationPreservationFingerprint.capture(runtime);
            assertThat(SimulationPreservationFingerprint.compare(media,baseline).changedTables()).containsExactly("feed_history_collection");
            // Sequence state survives a rolled-back transaction even when every table is identical.
            assertThatThrownBy(() -> runtime.executeAt(SimulationCashOracle.START,step -> {
                step.jdbc().queryForObject("SELECT nextval('feed_source_history_version_seq')",Long.class);
                throw new IllegalArgumentException("rollback");
            })).hasMessage("rollback");
            var allocated = SimulationPreservationFingerprint.capture(runtime);
            var difference = SimulationPreservationFingerprint.compare(baseline,allocated);
            assertThat(difference.changedTables()).isEmpty();
            assertThat(difference.schemaChanged()).isFalse();
            assertThat(difference.changedSequences()).containsExactly("feed_source_history_version_seq");
            assertThat(difference.unchanged()).isFalse();
            assertThat(SimulationPreservationFingerprint.capture(runtime)).isEqualTo(allocated);
        }
    }

    @Test void schemaProtectionChangesAndUnknownTablesAreDetectedWithoutExportingDefinitions() {
        try (var runtime = new SimulationPostgresClock()) {
            var jdbc = new JdbcTemplate(runtime.dataSource());
            var before = SimulationPreservationFingerprint.capture(runtime);
            jdbc.execute("ALTER TABLE student ALTER COLUMN nickname SET DEFAULT 'schema-only-test'");
            var after = SimulationPreservationFingerprint.capture(runtime);
            assertThat(SimulationPreservationFingerprint.compare(before,after).changedTables()).isEmpty();
            assertThat(SimulationPreservationFingerprint.compare(before,after).schemaChanged()).isTrue();
            assertThat(JsonMapper.builder().build().writeValueAsString(after)).doesNotContain("schema-only-test");
            jdbc.execute("CREATE TABLE unexpected_scope(id integer)");
            assertThatThrownBy(() -> SimulationPreservationFingerprint.capture(runtime)).hasMessage("PRESERVATION_TABLE_INVENTORY");
        }
    }

    @Test void differentDatabaseAndAmbientTransactionCannotMasqueradeAsPreservation() {
        try (var first = new SimulationPostgresClock(); var second = new SimulationPostgresClock()) {
            var a = SimulationPreservationFingerprint.capture(first);
            var b = SimulationPreservationFingerprint.capture(second);
            assertThatThrownBy(() -> SimulationPreservationFingerprint.compare(a,b)).hasMessage("PRESERVATION_DATABASE_MISMATCH");
            first.executeAt(SimulationCashOracle.START,step -> {
                assertThatThrownBy(() -> SimulationPreservationFingerprint.capture(first)).hasMessage("PRESERVATION_AMBIENT_TRANSACTION");
                return null;
            });
            assertThat(SimulationPreservationFingerprint.capture(first)).isEqualTo(a);
        }
    }
}
