package com.crabit.backend.simulation;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.*;
import java.net.http.*;
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

/** Private, owned loopback preview of the approved backup with the imported cohort. */
public final class PrepareDemoPreview {
    private static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    public static void main(String[] args) throws Exception {
        if(args.length!=7 && args.length!=8)throw new IllegalArgumentException("dump package schema output console-digest code-sha backend [APPLIED_LOCAL_PREVIEW]");
        boolean restoredPreview=args.length==8;
        if(restoredPreview && !args[7].equals("APPLIED_LOCAL_PREVIEW"))throw new IllegalArgumentException("LOCAL_PREVIEW_MODE");
        Path dump=Path.of(args[0]).toRealPath(), pack=Path.of(args[1]).toRealPath(), schema=Path.of(args[2]).toRealPath();
        Path out=Path.of(args[3]), backend=Path.of(args[6]).toRealPath();
        Files.createDirectory(out,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var target=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("preview_"+UUID.randomUUID().toString().replace("-",""))
            .withUsername("simulation").withPassword(UUID.randomUUID().toString()).withReuse(false)
            .withStartupTimeout(Duration.ofSeconds(60))
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(5432))));
        Process app=null;
        try {
            target.start();check(Set.of("localhost","127.0.0.1").contains(target.getHost()),"LOCAL_ONLY");
            target.copyFileToContainer(MountableFile.forHostPath(dump),"/tmp/approved-backup.dump");
            check(target.execInContainer("pg_restore","--username",target.getUsername(),"--dbname",target.getDatabaseName(),
                "--no-owner","--no-acl","--exit-on-error","/tmp/approved-backup.dump").getExitCode()==0,"RESTORE_BACKUP");
            var ds=new DriverManagerDataSource(target.getJdbcUrl(),target.getUsername(),target.getPassword());
            var jdbc=new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).validateOnMigrate(true).load().migrate();
            var manager=new SimulationImportManager(ds);String identity="local-preview-"+target.getDatabaseName();
            String manifest=SimulationBundleReader.digest(Files.readAllBytes(pack.resolve("bundle/manifest.json")));
            if(!restoredPreview) {
            var plan=manager.prepare(Files.readAllBytes(pack.resolve("state/relational.json")),read(pack.resolve("bundle/students.json")),
                read(pack.resolve("bundle/personas.json")),read(schema),manifest,identity,args[4],args[5]);
            Files.write(out.resolve("backup.json"),plan.backup(),StandardOpenOption.CREATE_NEW);
            var request=(ObjectNode)plan.request();var dry=manager.dryRun(request);write(out,"dry-run.json",dry);
            request.set("expectedAfterFingerprint",dry.get("afterFingerprint"));
            write(out,"apply-request.json",request);var applied=manager.execute(request);write(out,"applied.json",applied);
            check(applied.path("authoritativeReadBack").asBoolean() && applied.path("status").asString().equals("APPLIED"),"APPLY_READBACK");
            } else {
                check(Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM demo_simulation_dataset WHERE state='APPLIED' AND manifest_digest=?",Integer.class,manifest)),"RESTORED_PREVIEW_MANIFEST");
                check(Integer.valueOf(100).equals(jdbc.queryForObject("SELECT count(*) FROM demo_simulation_account",Integer.class)),"RESTORED_PREVIEW_POPULATION");
                // The explicitly local dump omits cluster roles and ACLs. Restore
                // the V21 management boundary before ever enabling an app login.
                jdbc.execute("CREATE ROLE crabit_demo_manager NOLOGIN NOINHERIT");
                for(String function:List.of("demo_import_snapshot()","demo_import_export()","demo_import_capture(TEXT)","demo_import_graph(JSONB,BOOLEAN)")) {
                    jdbc.execute("REVOKE ALL ON FUNCTION public."+function+" FROM PUBLIC");
                    jdbc.execute("GRANT EXECUTE ON FUNCTION public."+function+" TO crabit_demo_manager");
                }
                jdbc.execute("GRANT USAGE ON SCHEMA public TO crabit_demo_manager");
                jdbc.execute("GRANT REFERENCES ON ALL TABLES IN SCHEMA public TO crabit_demo_manager");
                write(out,"restored-local-preview.json",JSON.valueToTree(Map.of("sourceKind","APPLIED_LOCAL_PREVIEW",
                    "dumpDigest",SimulationBundleReader.digest(Files.readAllBytes(dump)),"manifestDigest",manifest,"managementAclReinstated",true,"externalDemoWritten",false)));
            }
            target.copyFileToContainer(MountableFile.forHostPath(backend.resolve("scripts/demo/create-runtime-role.sql")),"/tmp/runtime-role.sql");
            check(target.execInContainer("psql","--username",target.getUsername(),"--dbname",target.getDatabaseName(),
                "--set","ON_ERROR_STOP=1","--file","/tmp/runtime-role.sql").getExitCode()==0,"RUNTIME_GRANTS");
            String password=UUID.randomUUID().toString();
            jdbc.execute("ALTER ROLE crabit_demo_app LOGIN PASSWORD '"+password+"'");
            var runtime=new JdbcTemplate(new DriverManagerDataSource(target.getJdbcUrl(),"crabit_demo_app",password));
            check(Boolean.TRUE.equals(runtime.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)),"ORDINARY_APP_ROLE");
            check(!Boolean.TRUE.equals(runtime.queryForObject("SELECT pg_has_role(current_user,'crabit_demo_manager','MEMBER')",Boolean.class)),"NO_MANAGER_MEMBERSHIP");
            for(String call:List.of("demo_import_snapshot()","demo_import_export()","demo_import_capture('preview')","demo_import_graph('{}'::jsonb,true)")) {
                try {runtime.queryForObject("SELECT public."+call,String.class);throw new IllegalStateException("MANAGER_FUNCTION_ALLOWED:"+call);}
                catch(org.springframework.dao.DataAccessException expected) {
                    Throwable cause=expected;while(cause.getCause()!=null)cause=cause.getCause();
                    check(cause instanceof java.sql.SQLException && "42501".equals(((java.sql.SQLException)cause).getSQLState()),"FUNCTION_NOT_PERMISSION_DENIED:"+call);
                }
            }
            int port;try(var socket=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
            String origin="http://127.0.0.1:"+port;
            Path jar=backend.resolve("build/libs/crabit-backend-0.0.1-SNAPSHOT.jar");
            var builder=new ProcessBuilder(Path.of(System.getenv("JAVA_HOME"),"bin/java").toString(),"-Xmx768m","-jar",jar.toString(),
                "--server.address=127.0.0.1","--server.port="+port,"--spring.flyway.enabled=false");
            builder.directory(backend.toFile()).redirectErrorStream(true).redirectOutput(out.resolve("backend.log").toFile());
            var env=builder.environment();
            env.putAll(Map.of("SPRING_PROFILES_ACTIVE","demo","CRABIT_DATABASE_URL",target.getJdbcUrl(),"CRABIT_DATABASE_USERNAME","crabit_demo_app",
                "CRABIT_DATABASE_PASSWORD",password,"CRABIT_DEMO_SIMULATION_ENABLED","true","CRABIT_DEMO_OWNER_LOOKUPS_PAUSED","true",
                "CRABIT_DEMO_BALANCE_PROVIDER_URL","https://127.0.0.1:9/api/provider/balance-lookups","CRABIT_DEMO_BALANCE_PROVIDER_TOKEN",UUID.randomUUID().toString()));
            Map<String,String> tokens=new TreeMap<>();
            for(String key:List.of("OWNER","FRIEND","NONFRIEND","BLOCKED","OTHER_ACADEMY","STAFF","GRADE_3","GRADE_4","GRADE_5","GRADE_6")) {
                String token=UUID.randomUUID().toString();tokens.put(key,token);env.put("CRABIT_DEMO_TOKEN_"+key,token);
            }
            for(String key:List.of("FEED","RECAP")) {
                String url=System.getenv("CRABIT_SIMULATION_"+key+"_URL"),token=System.getenv("CRABIT_SIMULATION_"+key+"_TOKEN");
                check(url!=null && url.startsWith("http://127.0.0.1:"),"LOCAL_PYTHON_REQUIRED");
                String prefix=key.equals("FEED")?"CRABIT_FEED_RANKING_":"CRABIT_RECAP_GENERATION_";
                env.put(prefix+"ENABLED","true");env.put(prefix+"URL",url);env.put(prefix+"CREDENTIAL",token);
            }
            env.put("CRABIT_FEED_CLASSIFIER_VERSION",new com.crabit.backend.recommendation.FeedCategoryClassifier(JSON).version());
            write(out,"private-connection.json",JSON.valueToTree(Map.of("origin",origin,"tokens",tokens,"databaseUrl",target.getJdbcUrl(),
                "databaseUsername","crabit_demo_app","databasePassword",password,"containerId",target.getContainerId())));
            Files.setPosixFilePermissions(out.resolve("private-connection.json"),PosixFilePermissions.fromString("rw-------"));
            app=builder.start();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();boolean ready=false;
            while(app.isAlive() && System.nanoTime()<deadline) {
                try {ready=client.send(HttpRequest.newBuilder(URI.create(origin+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
                catch(Exception ignored) {}
                if(ready)break;Thread.sleep(1000);
            }
            check(ready,"BACKEND_NOT_READY");
            Map<String,Object> checks=new TreeMap<>();
            for(int grade=3;grade<=6;grade++) {
                var response=client.send(HttpRequest.newBuilder(URI.create(origin+"/v1/me/card-balance-accounts"))
                    .header("Authorization","Bearer "+tokens.get("GRADE_"+grade)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
                write(out,"grade-"+grade+"-accounts.json",JSON.readTree(response.body()));
                check(response.statusCode()==200,"GRADE_ACCOUNTS_"+grade+":"+response.statusCode());
                checks.put("grade-"+grade,Map.of("accountsHttpStatus",response.statusCode()));
            }
            write(out,"ready.json",JSON.valueToTree(Map.of("status","PASS","origin",origin,"checks",checks,
                "ordinaryAppRole",true,"managerFunctionsDenied",4,"jarDigest",SimulationBundleReader.digest(Files.readAllBytes(jar)),
                "externalDemoWritten",false,"ownerLookupPaused",true)));
            System.out.println("LOCAL_PREVIEW_READY "+origin);
            while(app.isAlive() && !Files.exists(out.resolve("stop")))Thread.sleep(1000);
            check(app.isAlive(),"BACKEND_EXITED_AFTER_READY");
        } finally {
            if(app!=null){app.destroy();if(!app.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))app.destroyForcibly();}
            target.stop();write(out,"cleanup.json",JSON.valueToTree(Map.of("runningAfterStop",target.isRunning())));
        }
    }
    private static JsonNode read(Path path) throws Exception{return SimulationBundleReader.parse(Files.readAllBytes(path));}
    private static void write(Path dir,String name,JsonNode value) throws Exception{Files.writeString(dir.resolve(name),SimulationBundleReader.canonical(value)+"\n",StandardOpenOption.CREATE_NEW);}
    private static void check(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
}
