package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;

import com.crabit.backend.balance.CardBalanceProvider;
import com.crabit.backend.balance.CardBalanceProviderResult;
import com.crabit.backend.balance.DemoHttpCardBalanceProvider;
import com.crabit.backend.wish.KrwAmount;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** All entry points use the same routing; unknown accounts never reach the console. */
@Component
@Primary
@Profile("demo & !e2e")
@ConditionalOnProperty(name = "crabit.demo.simulation.enabled", havingValue = "true")
public final class DemoSimulationBalanceProvider implements CardBalanceProvider {
    private final DemoHttpCardBalanceProvider owner;
    private final JdbcTemplate jdbc;

    public DemoSimulationBalanceProvider(DemoHttpCardBalanceProvider owner, JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }

    @Override
    public CardBalanceProviderResult lookup(UUID accountId) {
        if (OWNER_ACCOUNT_ID.equals(accountId)) {
            Boolean mapped = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM demo_simulation_account s
                JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id AND d.state='APPLIED'
                JOIN card_balance_account a ON a.id=s.account_id AND a.closed_at IS NULL
                JOIN academy_membership m ON m.student_id=a.student_id AND m.academy_id=a.academy_id AND m.left_at IS NULL
                WHERE s.account_id=? AND s.is_owner AND a.student_id=?)
                """, Boolean.class, accountId, OWNER_ID);
            return Boolean.TRUE.equals(mapped) ? owner.lookup(accountId) : CardBalanceProviderResult.failure();
        }
        var rows = jdbc.query("""
            SELECT s.card_funds, s.dataset_id, s.cash_sequence FROM demo_simulation_account s
            JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id AND d.state='APPLIED'
            JOIN card_balance_account a ON a.id=s.account_id AND a.closed_at IS NULL
            JOIN academy_membership m ON m.student_id=a.student_id AND m.academy_id=a.academy_id AND m.left_at IS NULL
            CROSS JOIN LATERAL (
                SELECT COALESCE(sum(CASE WHEN e.kind='GRANT' THEN e.amount_krw ELSE -e.amount_krw END),0) AS funds,
                       COALESCE(max(e.sequence),0) AS last_sequence, count(*) AS entries
                FROM demo_simulation_cash_event e WHERE e.account_id=s.account_id AND e.dataset_id=s.dataset_id
            ) ledger
            WHERE s.account_id=? AND NOT s.is_owner AND s.card_funds=ledger.funds
              AND s.cash_sequence=ledger.last_sequence AND s.cash_sequence=ledger.entries
            """, (rs, n) -> new CardBalanceProviderResult.Success(
                KrwAmount.nonNegative(rs.getLong(1)), rs.getString(2),
                "cash:" + accountId + ":" + rs.getLong(3)), accountId);
        return rows.size() == 1 ? rows.getFirst() : CardBalanceProviderResult.failure();
    }
}
