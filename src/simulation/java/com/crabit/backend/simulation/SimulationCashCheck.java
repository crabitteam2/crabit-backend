package com.crabit.backend.simulation;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.crabit.backend.simulation.SimulationCashOracle.*;

/** Explicit, read-only cash projection check. Success is never full dataset/application validation. */
public final class SimulationCashCheck {
    private SimulationCashCheck() {}
    public static Result check(byte[] input) throws IOException {
        JsonNode root=SimulationBundleReader.parse(input);
        byte[] schema;
        try(var stream=SimulationCashCheck.class.getResourceAsStream("/cash-oracle-v1.schema.json")) {
            if(stream==null)throw new IOException("Missing bundled cash schema");
            schema=stream.readAllBytes();
        }
        SimulationBundleReader.validate(SimulationBundleReader.parse(schema),root,"cashProjection");
        List<Account> accounts=new ArrayList<>(); List<Command> commands=new ArrayList<>();
        List<Entry> entries=new ArrayList<>(); List<Balance> balances=new ArrayList<>();
        for(JsonNode n:root.get("accounts"))accounts.add(new Account(text(n,"id"),time(n,"joinedAt")));
        for(JsonNode n:root.get("commands"))commands.add(new Command(text(n,"eventId"),number(n,"sequence"),
            text(n,"accountId"),time(n,"occurredAt"),Kind.valueOf(text(n,"kind")),number(n,"amountKrw"),
            text(n,"cashEntryId"),Outcome.valueOf(text(n,"outcome")),
            n.has("budgetMonth")?text(n,"budgetMonth"):null,n.has("scheduledAt")?time(n,"scheduledAt"):null));
        for(JsonNode n:root.get("ledger"))entries.add(new Entry(text(n,"id"),text(n,"eventId"),text(n,"accountId"),
            number(n,"sequence"),time(n,"occurredAt"),Kind.valueOf(text(n,"kind")),number(n,"amountKrw"),number(n,"balanceAfter")));
        for(JsonNode n:root.get("balances"))balances.add(new Balance(text(n,"accountId"),number(n,"amountKrw"),number(n,"sequence")));
        return new SimulationCashOracle().verify(accounts,commands,entries,balances);
    }
    public static void main(String[] args) {
        var json=JsonMapper.builder().build();
        if(args.length!=2 || !args[1].matches("sha256:[0-9a-f]{64}")) {
            System.err.println("Usage: simulationCashCheck --args='<cash-projection.json> <expected-sha256>'");
            System.exit(2);return;
        }
        try {
            Path path=Path.of(args[0]);
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Not a regular input");
            byte[] input;
            try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){input=stream.readNBytes(SimulationBundleReader.MAX_ARTIFACT_BYTES+1);}
            if(input.length>SimulationBundleReader.MAX_ARTIFACT_BYTES)throw new SimulationBundleReader.Rejection("SCHEMA_INVALID","Input size limit");
            if(!SimulationBundleReader.digest(input).equals(args[1]))throw new SimulationBundleReader.Rejection("CHECKSUM_MISMATCH","Cash projection bytes");
            Result result=check(input);
            System.out.println(json.writeValueAsString(Map.of("schemaVersion",1,"schemaKind","cash-projection-check",
                "status","CASH_VERIFIED","inputDigest",args[1],"accountCount",result.balances().size(),
                "appliedCommands",result.applied(),"rejectedCommands",result.rejected(),"failedCommands",result.failed(),
                "readyForApplication",false,"fullDatasetValidationPerformed",false)));
        } catch(SimulationBundleReader.Rejection e) { error(json,e.code(),e.getMessage(),2);
        } catch(Violation e) { error(json,"INVARIANT_VIOLATION",e.rule(),2);
        } catch(Exception e) { error(json,"EXECUTION_NOT_BOUND","Cash projection or bundled schema could not be read.",4); }
    }
    private static void error(JsonMapper json,String code,String rule,int exit) {
        System.out.println(json.writeValueAsString(Map.of("schemaVersion",1,"schemaKind","cash-projection-check",
            "status","REJECTED","code",code,"rule",rule,"readyForApplication",false)));
        System.exit(exit);
    }
    private static String text(JsonNode node,String key){return node.get(key).asString();}
    private static long number(JsonNode node,String key){return node.get(key).longValue();}
    private static Instant time(JsonNode node,String key){return Instant.parse(text(node,key));}
}
