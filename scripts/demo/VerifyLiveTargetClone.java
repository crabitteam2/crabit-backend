package com.crabit.backend.simulation;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import com.github.dockerjava.api.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read an explicitly approved live backup; every write targets a fresh owned local DB. */
public final class VerifyLiveTargetClone {
    private static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    public static void main(String[] args) throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("dump package schema output console-digest code-sha");
        Path dump=Path.of(args[0]).toRealPath(),pack=Path.of(args[1]).toRealPath(),schema=Path.of(args[2]).toRealPath(),out=Path.of(args[3]);
        Files.createDirectory(out);
        String manifest=SimulationBundleReader.digest(Files.readAllBytes(pack.resolve("bundle/manifest.json")));
        var target=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("live_clone_"+UUID.randomUUID().toString().replace("-",""))
            .withUsername("simulation").withPassword(UUID.randomUUID().toString()).withReuse(false)
            .withStartupTimeout(Duration.ofSeconds(60))
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(5432))));
        String containerId=null;
        try {
            target.start();containerId=target.getContainerId();
            check(Set.of("localhost","127.0.0.1").contains(target.getHost()),"LOCAL_TARGET_REQUIRED");
            target.copyFileToContainer(MountableFile.forHostPath(dump),"/tmp/approved-backup.dump");
            var restored=target.execInContainer("pg_restore","--username",target.getUsername(),"--dbname",target.getDatabaseName(),
                "--no-owner","--no-acl","--exit-on-error","/tmp/approved-backup.dump");
            check(restored.getExitCode()==0,"BACKUP_RESTORE_FAILED");
            var ds=new DriverManagerDataSource(target.getJdbcUrl(),target.getUsername(),target.getPassword());
            var jdbc=new JdbcTemplate(ds);
            String previousVersion=jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",String.class);
            var flyway=Flyway.configure().dataSource(ds).validateOnMigrate(true).load();
            int migrations=flyway.migrate().migrationsExecuted;
            var manager=new SimulationImportManager(ds);String identity="live-backup-clone-"+target.getDatabaseName();
            JsonNode before=manager.inspect(identity);write(out,"before.json",before);
            JsonNode ownerBefore=owner(jdbc,before);
            int oldStudents=before.path("tables").path("student").size();
            var plan=manager.prepare(Files.readAllBytes(pack.resolve("state/relational.json")),
                read(pack.resolve("bundle/students.json")),read(pack.resolve("bundle/personas.json")),read(schema),
                manifest,identity,args[4],args[5]);
            Files.write(out.resolve("backup.json"),plan.backup(),StandardOpenOption.CREATE_NEW);
            check(SimulationBundleReader.digest(plan.backup()).equals(plan.backupDigest()),"BACKUP_DIGEST");
            var request=(ObjectNode)plan.request();var dry=manager.dryRun(request);write(out,"dry-run.json",dry);
            check(before.path("snapshot").equals(manager.inspect(identity).path("snapshot")),"DRY_RUN_ROLLBACK");
            request.set("expectedAfterFingerprint",dry.get("afterFingerprint"));write(out,"apply-request.json",request);
            var applied=manager.execute(request);write(out,"applied.json",applied);
            check(applied.path("authoritativeReadBack").asBoolean() && applied.path("status").asString().equals("APPLIED"),"APPLY_READBACK");
            JsonNode after=manager.inspect(identity);write(out,"after-apply.json",after);
            check(ownerBefore.equals(owner(jdbc,after)),"OWNER_GRAPH_PRESERVATION");
            check(after.path("tables").path("demo_simulation_account").size()==100,"COHORT_100");
            check(after.path("tables").path("student").size()==oldStudents+99,"LEGACY_STUDENTS_PRESERVATION");
            for(String name:before.path("tables").propertyNames()) {
                Set<JsonNode> actual=new HashSet<>();after.path("tables").path(name).forEach(actual::add);
                for(JsonNode row:before.path("tables").path(name))check(actual.contains(row),"EXISTING_ROW_CHANGED:"+name);
            }
            long simulationObservations=0;
            for(JsonNode row:after.path("tables").path("balance_observation"))
                if(row.path("source_kind").asString().equals("SIMULATION"))simulationObservations++;
            check(simulationObservations>0,"SIMULATION_OBSERVATIONS");
            var reverse=manager.prepareRestore(plan.backup(),plan.backupDigest(),args[4],args[5]);
            var undo=(ObjectNode)reverse.request();var undoDry=manager.dryRun(undo);write(out,"restore-dry-run.json",undoDry);
            check(after.path("snapshot").equals(manager.inspect(identity).path("snapshot")),"RESTORE_DRY_RUN_ROLLBACK");
            undo.set("expectedAfterFingerprint",undoDry.get("afterFingerprint"));write(out,"restore-request.json",undo);
            var result=manager.execute(undo);write(out,"restored.json",result);
            check(result.path("authoritativeReadBack").asBoolean() && result.path("status").asString().equals("RESTORED"),"RESTORE_READBACK");
            JsonNode end=manager.inspect(identity);write(out,"after-restore.json",end);
            for(String table:before.path("tables").propertyNames())
                if(!table.equals("demo_simulation_dataset"))check(before.path("tables").get(table).equals(end.path("tables").get(table)),"RESTORED_TABLE:"+table);
            check(before.path("snapshot").path("sequences").equals(end.path("snapshot").path("sequences")),"RESTORED_SEQUENCES");
            write(out,"verification.json",JSON.valueToTree(Map.ofEntries(
                Map.entry("status","PASS"),Map.entry("manifestDigest",manifest),Map.entry("backupFileDigest",SimulationBundleReader.digest(Files.readAllBytes(dump))),
                Map.entry("previousMigration",previousVersion),Map.entry("migrationsApplied",migrations),Map.entry("cohortStudents",100),
                Map.entry("totalStudentsAfterApply",oldStudents+99),Map.entry("retainedLegacyStudents",oldStudents-1),
                Map.entry("ownerWishesPreserved",ownerBefore.path("wish").size()),Map.entry("simulationObservations",simulationObservations),
                Map.entry("originalDomainTablesRestored",true),Map.entry("sequencesRestored",true),
                Map.entry("externalDemoWritten",false),Map.entry("externalConsoleVerified",false))));
            System.out.println("LIVE_TARGET_CLONE_APPLY_RESTORE_PASS");
        } finally {
            target.stop();
            write(out,"cleanup.json",JSON.valueToTree(Map.of("containerId",Objects.toString(containerId,"NOT_STARTED"),"runningAfterStop",target.isRunning())));
        }
    }
    private static JsonNode owner(JdbcTemplate jdbc,JsonNode state) {
        return JSON.readTree(jdbc.queryForObject("SELECT public.demo_import_owner_graph(?::jsonb)::text",String.class,JSON.writeValueAsString(state.get("tables"))));
    }
    private static JsonNode read(Path path) throws Exception {return SimulationBundleReader.parse(Files.readAllBytes(path));}
    private static void write(Path dir,String name,JsonNode value) throws Exception {
        Files.writeString(dir.resolve(name),SimulationBundleReader.canonical(value)+"\n",StandardOpenOption.CREATE_NEW);
    }
    private static void check(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
}
