package com.crabit.backend.demo;

import com.crabit.backend.wish.KrwAmount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal commands only. Cash changes never mutate Wish allocations or observations. */
@Service
@Profile("demo & !e2e")
@ConditionalOnProperty(name = "crabit.demo.simulation.enabled", havingValue = "true")
public class DemoSimulationCashService {
    private final JdbcTemplate jdbc;

    public DemoSimulationCashService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public enum Kind { GRANT, PURCHASE }

    /** Historical commands may supply time only while the dataset is being built. */
    @Transactional
    public long apply(String dataset, String eventId, UUID account, Kind kind, long amount, Instant occurredAt) {
        if (occurredAt == null || occurredAt.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Replay time must use PostgreSQL microsecond precision");
        }
        return applyCommand(dataset, eventId, account, kind, amount, occurredAt, false);
    }

    /** Live commands use the database clock after locking; callers cannot backdate events. */
    @Transactional
    public long applyCurrent(String dataset, String eventId, UUID account, Kind kind, long amount) {
        return applyCommand(dataset, eventId, account, kind, amount, null, true);
    }

    private long applyCommand(String dataset, String eventId, UUID account, Kind kind, long amount,
            Instant replayTime, boolean live) {
        if (dataset == null || !dataset.matches("sha256:[0-9a-f]{64}") || eventId == null
                || !eventId.matches("[A-Za-z0-9:_-]{1,160}") || account == null || kind == null
                || amount <= 0 || amount > KrwAmount.MAX_SAFE_WON) {
            throw new IllegalArgumentException("Invalid cash command");
        }
        // Dataset first serializes lifecycle transitions and dataset-wide idempotency keys.
        // The account lock also excludes concurrent account closing.
        var datasets = jdbc.queryForList("""
            SELECT state, starts_at, ends_at FROM demo_simulation_dataset
            WHERE dataset_id=? FOR UPDATE
            """, dataset);
        if (datasets.size() != 1 || !(live ? "APPLIED" : "BUILDING").equals(datasets.getFirst().get("state"))) {
            throw new IllegalStateException("Cash command requires the matching dataset lifecycle");
        }
        var rows = jdbc.queryForList("""
            SELECT s.card_funds,s.cash_sequence,s.is_owner,a.opened_at FROM demo_simulation_account s
            JOIN card_balance_account a ON a.id=s.account_id AND a.closed_at IS NULL
            WHERE s.dataset_id=? AND s.account_id=? FOR UPDATE OF a,s
            """, dataset, account);
        if (rows.size() != 1) throw new IllegalStateException("Unknown or inactive simulation account");
        var row = rows.getFirst();
        if (live && Boolean.TRUE.equals(row.get("is_owner"))) {
            throw new IllegalArgumentException("Live Owner cash belongs to the external provider");
        }
        long balance = ((Number) row.get("card_funds")).longValue();
        var previous = jdbc.queryForList("""
            SELECT account_id,kind,amount_krw,occurred_at FROM demo_simulation_cash_event
            WHERE dataset_id=? AND event_id=?
            """, dataset, eventId);
        if (!previous.isEmpty()) {
            var prior = previous.getFirst();
            if (!account.equals(prior.get("account_id")) || !kind.name().equals(prior.get("kind"))
                    || amount != ((Number) prior.get("amount_krw")).longValue()
                    || !live && !replayTime.equals(((Timestamp) prior.get("occurred_at")).toInstant())) {
                throw new IllegalStateException("Cash idempotency conflict");
            }
            return balance;
        }
        Instant occurredAt = live
                ? jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant() : replayTime;
        var metadata = datasets.getFirst();
        if (occurredAt.isBefore(((Timestamp) row.get("opened_at")).toInstant())
                || !live && (occurredAt.isBefore(((Timestamp) metadata.get("starts_at")).toInstant())
                    || !occurredAt.isBefore(((Timestamp) metadata.get("ends_at")).toInstant()))) {
            throw new IllegalArgumentException("Cash command is outside its account/time boundary");
        }
        Instant latest = jdbc.query("SELECT max(occurred_at) FROM demo_simulation_cash_event WHERE account_id=?",
                rs -> { rs.next(); var timestamp = rs.getTimestamp(1); return timestamp == null ? null : timestamp.toInstant(); }, account);
        if (latest != null && occurredAt.isBefore(latest)) {
            throw new IllegalArgumentException("Cash time cannot move backwards");
        }
        long next = kind == Kind.GRANT ? Math.addExact(balance, amount) : Math.subtractExact(balance, amount);
        if (next < 0 || next > KrwAmount.MAX_SAFE_WON) throw new IllegalArgumentException("Cash out of range");
        long sequence = Math.addExact(((Number) row.get("cash_sequence")).longValue(), 1);
        jdbc.update("""
            INSERT INTO demo_simulation_cash_event(dataset_id,event_id,account_id,sequence,kind,amount_krw,occurred_at)
            VALUES (?,?,?,?,?,?,?)
            """, dataset, eventId, account, sequence, kind.name(), amount, Timestamp.from(occurredAt));
        jdbc.update("UPDATE demo_simulation_account SET card_funds=?,cash_sequence=? WHERE account_id=?", next, sequence, account);
        return next;
    }
}
