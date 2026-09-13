package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import com.crabit.backend.auth.CurrentPrincipal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves only imported mappings. Credentials stay in the server environment. */
@Component
@Profile("demo & !e2e")
@ConditionalOnProperty(name="crabit.demo.simulation.enabled", havingValue="true")
@DependsOnDatabaseInitialization
public final class DemoRepresentativeRegistry {
    private final Map<String,CurrentPrincipal> principals;
    public DemoRepresentativeRegistry(JdbcTemplate jdbc, Environment env) {
        var counts=jdbc.queryForList("""
            SELECT s.grade,count(*) AS total,count(*) FILTER (WHERE s.is_owner) AS owners
            FROM demo_simulation_account s JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id
            JOIN card_balance_account a ON a.id=s.account_id AND a.closed_at IS NULL
            WHERE d.state='APPLIED' GROUP BY s.grade ORDER BY s.grade
            """);
        if (counts.size()!=4 || counts.stream().anyMatch(c -> ((Number)c.get("total")).longValue()!=25)
                || counts.stream().mapToLong(c -> ((Number)c.get("owners")).longValue()).sum()!=1)
            throw invalid();
        Long academyCount=jdbc.queryForObject("""
            SELECT count(DISTINCT a.academy_id) FROM demo_simulation_account s
            JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id AND d.state='APPLIED'
            JOIN card_balance_account a ON a.id=s.account_id
            """,Long.class);
        Boolean ownerValid=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM demo_simulation_account s
            JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id AND d.state='APPLIED'
            JOIN card_balance_account a ON a.id=s.account_id WHERE s.is_owner AND a.student_id=?)
            """,Boolean.class,OWNER_ID);
        if (!Long.valueOf(1).equals(academyCount) || !Boolean.TRUE.equals(ownerValid)) throw invalid();
        var rows=jdbc.queryForList("""
            SELECT p.persona,s.grade,a.student_id,a.academy_id FROM demo_simulation_persona p
            JOIN demo_simulation_account s ON s.account_id=p.account_id AND s.dataset_id=p.dataset_id AND NOT s.is_owner
            JOIN demo_simulation_dataset d ON d.dataset_id=s.dataset_id AND d.state='APPLIED'
            JOIN card_balance_account a ON a.id=s.account_id AND a.closed_at IS NULL
            JOIN academy_membership m ON m.student_id=a.student_id AND m.academy_id=a.academy_id AND m.left_at IS NULL
            ORDER BY p.persona
            """);
        if(rows.size()!=4) throw invalid();
        Map<String,CurrentPrincipal> result=new LinkedHashMap<>();
        Set<UUID> students=new HashSet<>(); Set<UUID> academies=new HashSet<>(); Set<String> aliases=new HashSet<>();
        for(var row:rows) {
            int grade=((Number)row.get("grade")).intValue();
            String alias=(String)row.get("persona");
            String token=env.getProperty("CRABIT_DEMO_TOKEN_GRADE_"+grade, "");
            UUID student=(UUID)row.get("student_id"), academy=(UUID)row.get("academy_id");
            if (!alias.equals("grade-"+grade) || !aliases.add(alias) || !students.add(student)
                    || OWNER_ID.equals(student) || token.isBlank()
                    || token.chars().anyMatch(c -> c<0x21 || c>0x7e)) throw invalid();
            academies.add(academy);
            if (result.putIfAbsent(token,new CurrentPrincipal(student,CurrentPrincipal.Role.STUDENT,academy,alias))!=null) throw invalid();
        }
        if (academies.size()!=1) throw invalid();
        principals=Map.copyOf(result);
    }
    public Map<String,CurrentPrincipal> all() { return principals; }
    private static IllegalStateException invalid() {
        return new IllegalStateException("Invalid simulation population or representative mapping/credentials");
    }
}
