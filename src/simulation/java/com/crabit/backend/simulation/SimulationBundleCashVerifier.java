package com.crabit.backend.simulation;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.crabit.backend.simulation.SimulationCashOracle.*;

/** Derives cash commands from admitted events, never from caller-provided replacement commands. */
public final class SimulationBundleCashVerifier {
    /** All documents must first pass their closed shared schemas and event timeline validation. */
    public Result verify(JsonNode manifest, JsonNode students, List<JsonNode> events, JsonNode state) {
        if (!manifest.get("datasetId").equals(state.get("datasetId")))
            throw new SimulationBundleReader.Rejection("INVARIANT_VIOLATION", "CASH_STATE_DATASET_BINDING");
        List<Account> accounts=new ArrayList<>(); List<Command> commands=new ArrayList<>();
        List<Entry> ledger=new ArrayList<>(); List<Balance> balances=new ArrayList<>();
        for(JsonNode student:students) accounts.add(new Account(text(student,"logicalAccountId"),time(student,"joinedAt")));
        for(JsonNode event:events) {
            String kind=text(event,"kind");
            if(!kind.equals("GRANT") && !kind.equals("PURCHASE"))continue;
            JsonNode command=event.get("command");
            commands.add(new Command(text(event,"eventId"),number(event,"sequence"),text(command,"accountId"),
                time(event,"occurredAt"),Kind.valueOf(kind),number(command,"amountKrw"),text(command,"cashEntryId"),
                Outcome.valueOf(text(event.get("outcome"),"status")),
                kind.equals("GRANT")?text(command,"budgetMonth"):null,
                kind.equals("GRANT")?time(command,"scheduledAt"):null));
        }
        for(JsonNode entry:state.get("ledger"))ledger.add(new Entry(text(entry,"id"),text(entry,"eventId"),text(entry,"accountId"),
            number(entry,"sequence"),time(entry,"occurredAt"),Kind.valueOf(text(entry,"kind")),number(entry,"amountKrw"),number(entry,"balanceAfter")));
        String previous="";
        for(JsonNode balance:state.get("balances")) {
            String account=text(balance,"accountId");
            if(account.compareTo(previous)<=0)
                throw new SimulationBundleReader.Rejection("INVARIANT_VIOLATION", "CASH_BALANCE_CANONICAL_ORDER");
            previous=account;
            balances.add(new Balance(account,number(balance,"amountKrw"),number(balance,"sequence")));
        }
        try { return new SimulationCashOracle().verify(accounts,commands,ledger,balances); }
        catch(Violation e) {
            throw new SimulationBundleReader.Rejection("INVARIANT_VIOLATION",e.rule()+" logicalId="+e.logicalId());
        }
    }
    private static String text(JsonNode n,String key){return n.get(key).asString();}
    private static long number(JsonNode n,String key){return n.get(key).longValue();}
    private static Instant time(JsonNode n,String key){return Instant.parse(text(n,key));}
}
