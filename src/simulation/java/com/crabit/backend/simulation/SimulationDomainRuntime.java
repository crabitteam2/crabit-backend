package com.crabit.backend.simulation;

import com.crabit.backend.balance.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.wish.KrwAmount;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Local, non-web service host. Owns its DB, has no external provider or scheduler.
 * Callbacks run synchronously; services and JDBC/Clock references must not escape a callback.
 */
public final class SimulationDomainRuntime implements AutoCloseable {
    private final SimulationPostgresClock database;
    private final AnnotationConfigApplicationContext context;
    private boolean running, closed;
    public SimulationDomainRuntime() { this(null); }
    public SimulationDomainRuntime(com.crabit.backend.recommendation.SimulationFeedSession feed) {
        database = new SimulationPostgresClock();
        context = new AnnotationConfigApplicationContext();
        try {
            context.registerBean(DataSource.class, database::dataSource);
            context.registerBean(Clock.class, database::domainClock);
            if(feed!=null) context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("localSimulationFeed",Map.of("crabit.feed.ranking.enabled","true")));
            if(feed!=null) context.registerBean(com.crabit.backend.recommendation.FeedRankingClient.class,
                ()->feed.client(context.getBean(com.crabit.backend.recommendation.FeedCategoryClassifier.class),
                    context.getBean(tools.jackson.databind.ObjectMapper.class),context.getBean(JdbcTemplate.class)));
            context.register(DomainConfiguration.class);
            context.refresh();
        } catch (RuntimeException | Error failure) {
            context.close(); database.close(); throw failure;
        }
    }
    public synchronized <T> T executeAt(Instant instant, Function<Services,T> command) {
        if (closed) throw new IllegalStateException("SIMULATION_RUNTIME_CLOSED");
        if (running) throw new IllegalStateException("SIMULATION_NESTED_STEP");
        Objects.requireNonNull(command,"command");
        running=true;
        try { return database.executeServicesAt(instant, step -> command.apply(new Services(context,step.jdbc(),step.clock()))); }
        finally { running=false; }
    }
    synchronized SimulationPreservationFingerprint.Snapshot preservationFingerprint() {
        if (closed) throw new IllegalStateException("SIMULATION_RUNTIME_CLOSED");
        if (running) throw new IllegalStateException("SIMULATION_STEP_ACTIVE");
        return SimulationPreservationFingerprint.capture(database);
    }
    synchronized <T> T preservationWindow(String expected, java.util.function.BiFunction<JdbcTemplate,SimulationPreservationFingerprint.Snapshot,T> read) {
        if (closed) throw new IllegalStateException("SIMULATION_RUNTIME_CLOSED");
        if (running) throw new IllegalStateException("SIMULATION_STEP_ACTIVE");
        running=true;
        try { return SimulationPreservationWindow.read(database,expected,read); }
        finally { running=false; }
    }
    public record Services(AnnotationConfigApplicationContext context, JdbcTemplate jdbc, Clock clock) {
        public <T> T service(Class<T> type) { return context.getBean(type); }
    }
    @Override public synchronized void close() {
        if (running) throw new IllegalStateException("SIMULATION_STEP_ACTIVE");
        if (!closed) { closed=true; try { context.close(); } finally { database.close(); } }
    }

    @Configuration(proxyBeanMethods=false)
    @Import({com.crabit.backend.recap.RecapSnapshotService.class,
        com.crabit.backend.recap.RecapGenerationCoordinator.class,
        com.crabit.backend.recommendation.FeedPageContextRepository.class,
        com.crabit.backend.behavior.BehaviorService.class,
        com.crabit.backend.recommendation.FeedVisitEvidenceService.class,
        com.crabit.backend.recommendation.FeedCategoryClassifier.class,
        com.crabit.backend.recommendation.FeedRankingRequestAssembler.class,
        com.crabit.backend.recommendation.FeedMonthMetricsService.class,
        com.crabit.backend.recommendation.FeedMonthMetricsRepository.class,
        com.crabit.backend.recommendation.FeedVisitSignals.class})
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="com.crabit.backend")
    @ComponentScan(basePackages={"com.crabit.backend.account", "com.crabit.backend.wish",
        "com.crabit.backend.balance", "com.crabit.backend.relationship", "com.crabit.backend.notification"})
    static class DomainConfiguration {
        @Bean tools.jackson.databind.ObjectMapper objectMapper() { return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(); }
        @Bean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource ds) { return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { var jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(15); return jdbc; }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds); factory.setPackagesToScan("com.crabit.backend");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate", "hibernate.jdbc.time_zone","UTC"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) { var tx=new JpaTransactionManager(emf); tx.setDefaultTimeout(30); return tx; }
        @Bean DemoSimulationCashService simulationCash(JdbcTemplate jdbc) { return new DemoSimulationCashService(jdbc); }
        // BUILDING only, including Owner: replay never contacts the external console.
        @Bean @Primary CardBalanceProvider simulationProvider(JdbcTemplate jdbc) {
            return account -> {
                var rows=jdbc.query("""
                    SELECT s.card_funds,s.dataset_id,s.cash_sequence FROM demo_simulation_account s
                    JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id AND d.state='BUILDING'
                    JOIN card_balance_account a ON a.id=s.account_id AND a.closed_at IS NULL
                    JOIN academy_membership m ON m.student_id=a.student_id AND m.academy_id=a.academy_id AND m.left_at IS NULL
                    CROSS JOIN LATERAL (SELECT COALESCE(sum(CASE WHEN e.kind='GRANT' THEN e.amount_krw ELSE -e.amount_krw END),0) funds,
                        count(*) entries, COALESCE(max(e.sequence),0) last_sequence FROM demo_simulation_cash_event e
                        WHERE e.dataset_id=s.dataset_id AND e.account_id=s.account_id) ledger
                    WHERE s.account_id=? AND s.card_funds=ledger.funds AND s.cash_sequence=ledger.entries AND s.cash_sequence=ledger.last_sequence
                    """, (rs,n)->new CardBalanceProviderResult.Success(KrwAmount.nonNegative(rs.getLong(1)),rs.getString(2),
                        "cash:"+account+":"+rs.getLong(3)),account);
                return rows.size()==1 ? rows.getFirst() : CardBalanceProviderResult.failure();
            };
        }
    }
}
