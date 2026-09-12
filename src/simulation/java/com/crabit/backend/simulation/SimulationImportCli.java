package com.crabit.backend.simulation;

import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Operator-only process, never packaged into bootJar or exposed as an HTTP route. */
public final class SimulationImportCli {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: simulationImport <PREPARE|RESTORE_PREPARE|APPLY|INSPECT> <input.json> <new-output-directory>");
        String url=System.getenv("CRABIT_SIMULATION_TARGET_JDBC_URL");
        if(url==null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("TARGET_REQUIRES_EXPLICIT_LOOPBACK_JDBC_URL");
        var manager=new SimulationImportManager(new DriverManagerDataSource(url,
            Objects.requireNonNull(System.getenv("CRABIT_SIMULATION_TARGET_DB_USER")),
            Objects.requireNonNull(System.getenv("CRABIT_SIMULATION_TARGET_DB_PASSWORD"))));
        JsonNode input=read(Path.of(args[1]));Path output=Path.of(args[2]);Files.createDirectory(output);
        switch(args[0]) {
            case "INSPECT" -> {
                keys(input,"targetIdentity");write(output.resolve("inspection.json"),manager.inspect(input.get("targetIdentity").asString()));
            }
            case "PREPARE" -> {
                keys(input,"schema","relational","students","personas","manifestDigest","targetIdentity","consoleBaselineDigest","codeSha");
                var plan=manager.prepare(Files.readAllBytes(path(input,"relational")),read(path(input,"students")),read(path(input,"personas")),read(path(input,"schema")),
                    input.get("manifestDigest").asString(),input.get("targetIdentity").asString(),input.get("consoleBaselineDigest").asString(),input.get("codeSha").asString());
                prepare(manager,plan,output);
            }
            case "RESTORE_PREPARE" -> {
                keys(input,"backup","backupDigest","consoleBaselineDigest","codeSha");
                prepare(manager,manager.prepareRestore(Files.readAllBytes(path(input,"backup")),input.get("backupDigest").asString(),
                    input.get("consoleBaselineDigest").asString(),input.get("codeSha").asString()),output);
            }
            case "APPLY" -> {
                // Persist exact intent before the single call. Any failure leaves this marker and requires INSPECT.
                write(output.resolve("submitted-request.json"),input);
                write(output.resolve("result.json"),manager.execute(input));
            }
            default -> throw new IllegalArgumentException("IMPORT_OPERATION");
        }
    }
    private static void prepare(SimulationImportManager manager,SimulationImportManager.Plan plan,Path output) throws Exception {
        Files.write(output.resolve("backup.json"),plan.backup(),StandardOpenOption.CREATE_NEW);
        var request=(ObjectNode)plan.request();write(output.resolve("unbound-request.json"),request);
        var dry=manager.dryRun(request);write(output.resolve("dry-run.json"),dry);
        request.set("expectedAfterFingerprint",dry.get("afterFingerprint"));
        write(output.resolve("prepared-request.json"),request);
        write(output.resolve("preparation.json"),JSON.valueToTree(Map.of("backupDigest",plan.backupDigest(),"status","DRY_RUN_READY","externalConsoleVerified",false)));
    }
    private static JsonNode read(Path path) throws Exception {return SimulationBundleReader.parse(Files.readAllBytes(path));}
    private static Path path(JsonNode input,String key) {return Path.of(input.get(key).asString());}
    private static void keys(JsonNode input,String... names) {
        if(!input.isObject() || !new HashSet<>(input.propertyNames()).equals(Set.of(names)))throw new IllegalArgumentException("IMPORT_CONFIG");
    }
    private static void write(Path path,JsonNode value) throws Exception {Files.writeString(path,SimulationBundleReader.canonical(value)+"\n",StandardOpenOption.CREATE_NEW);}
}
