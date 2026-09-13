package com.crabit.backend.demo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Imported synthetic coverage applies only to its 99 accounts and fully contained periods. */
@Component
@Profile("demo & !e2e")
@ConditionalOnProperty(name="crabit.demo.simulation.enabled",havingValue="true")
public final class DemoSimulationCoverage {
    private final JdbcTemplate jdbc;
    public DemoSimulationCoverage(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Instant collectionStart(UUID account,UUID student,UUID academy,Instant from,Instant to,Instant ordinary) {
        var starts=jdbc.query("""
            SELECT d.starts_at FROM demo_simulation_dataset d
            JOIN demo_simulation_account s ON s.dataset_id=d.dataset_id AND NOT s.is_owner
            JOIN card_balance_account a ON a.id=s.account_id
            WHERE d.state='APPLIED' AND a.id=? AND a.student_id=? AND a.academy_id=?
              AND d.starts_at<=? AND d.ends_at>=?
            """,(rs,n)->rs.getTimestamp(1).toInstant(),account,student,academy,Timestamp.from(from),Timestamp.from(to));
        return starts.size()==1 && starts.getFirst().isBefore(ordinary)?starts.getFirst():ordinary;
    }
}
