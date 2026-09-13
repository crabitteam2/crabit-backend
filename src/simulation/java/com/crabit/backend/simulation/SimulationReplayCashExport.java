package com.crabit.backend.simulation;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads committed cash rows. Command input supplies identity/order only, never money or timestamps. */
final class SimulationReplayCashExport {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationReplayCashExport() {}

    static JsonNode capture(JdbcTemplate jdbc,String dataset,Map<String,UUID> identities,List<JsonNode> events) {
        Map<UUID,String> accounts=new HashMap<>();
        identities.forEach((key,value)->{
            if(key.startsWith("ACCOUNT:") && accounts.putIfAbsent(value,key.substring(8))!=null)
                throw new IllegalStateException("CASH_EXPORT_ACCOUNT_IDENTITY");
        });
        Map<String,JsonNode> cashCommands=new HashMap<>();
        for(JsonNode event:events)if(Set.of("GRANT","PURCHASE").contains(event.get("kind").asString())) {
            String entry=event.get("command").get("cashEntryId").asString();
            if(cashCommands.putIfAbsent(entry,event)!=null)throw new IllegalStateException("CASH_EXPORT_ENTRY_IDENTITY");
        }
        var state=JSON.createObjectNode();
        state.put("schemaVersion",1);state.put("schemaKind","demo-simulation-cash-state");state.put("datasetId",dataset);
        var ledger=state.putArray("ledger");var balances=state.putArray("balances");
        var entries=jdbc.query("""
            SELECT event_id,account_id,sequence,occurred_at,kind,amount_krw,
                sum(CASE WHEN kind='GRANT' THEN amount_krw ELSE -amount_krw END)
                    OVER (PARTITION BY account_id ORDER BY sequence ROWS UNBOUNDED PRECEDING) AS balance_after
            FROM demo_simulation_cash_event WHERE dataset_id=?
            """,(rs,n)->{
                String entry=rs.getString("event_id");JsonNode command=cashCommands.get(entry);
                if(command==null)throw new IllegalStateException("CASH_EXPORT_ORPHAN_ENTRY");
                String account=accounts.get(rs.getObject("account_id",UUID.class));
                if(account==null)throw new IllegalStateException("CASH_EXPORT_UNKNOWN_ACCOUNT");
                var row=JSON.createObjectNode();row.put("id",entry);row.set("eventId",command.get("eventId"));
                row.put("accountId",account);row.put("sequence",rs.getLong("sequence"));
                row.put("occurredAt",rs.getTimestamp("occurred_at").toInstant().toString());
                row.put("kind",rs.getString("kind"));row.put("amountKrw",rs.getLong("amount_krw"));
                row.put("balanceAfter",rs.getBigDecimal("balance_after").longValueExact());return row;
            },dataset);
        // The logical command sequence disambiguates equal timestamps across accounts.
        entries.sort(Comparator.comparingLong(row->cashCommands.get(row.get("id").asString()).get("sequence").longValue()));
        entries.forEach(ledger::add);
        var rows=jdbc.query("""
            SELECT account_id,logical_account_id,card_funds,cash_sequence
            FROM demo_simulation_account WHERE dataset_id=?
            """,(rs,n)->{
                String account=accounts.get(rs.getObject("account_id",UUID.class));
                if(account==null || !account.equals(rs.getString("logical_account_id")))
                    throw new IllegalStateException("CASH_EXPORT_UNKNOWN_ACCOUNT");
                var row=JSON.createObjectNode();row.put("accountId",account);
                row.put("amountKrw",rs.getLong("card_funds"));row.put("sequence",rs.getLong("cash_sequence"));return row;
            },dataset);
        rows.sort(Comparator.comparing(row->row.get("accountId").asString()));rows.forEach(balances::add);
        return state;
    }
}
