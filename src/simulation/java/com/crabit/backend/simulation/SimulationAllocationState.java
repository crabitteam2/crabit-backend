package com.crabit.backend.simulation;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed, diagnostic DB projection. Not an import format or the complete relational bundle. */
public final class SimulationAllocationState {
    public record Wish(UUID id, UUID accountId, long targetAmount, long amount, String state,
                       Instant createdAt, Instant completedAt, Instant deletedAt) {}
    public record Root(UUID id, UUID accountId, String kind, long accountDelta, Instant occurredAt,
                       UUID depositObservationId, UUID correctionOfEventId) {}
    public record Effect(UUID id, UUID eventId, UUID accountId, UUID wishId, long delta) {}
    public record State(int schemaVersion, String schemaKind, String datasetId,
                        List<Wish> wishes, List<Root> roots, List<Effect> effects) {
        public State { wishes=List.copyOf(wishes);roots=List.copyOf(roots);effects=List.copyOf(effects); }
    }
    public record Verification(int wishes, int roots, int effects, long allocatedAmount) {}
    private SimulationAllocationState() {}
    private static Instant instant(java.sql.ResultSet rs,String name) throws java.sql.SQLException {
        var value=rs.getTimestamp(name);return value==null?null:value.toInstant();
    }
    static State capture(JdbcTemplate jdbc,String dataset) {
        var wishes=jdbc.query("""
            SELECT w.* FROM wish w JOIN demo_simulation_account a ON a.account_id=w.account_id
            WHERE a.dataset_id=? ORDER BY w.id
            """,(rs,n)->new Wish(rs.getObject("id",UUID.class),rs.getObject("account_id",UUID.class),
                rs.getLong("target_amount"),rs.getLong("wish_amount"),rs.getString("state"),
                instant(rs,"created_at"),instant(rs,"completed_at"),instant(rs,"deleted_at")),dataset);
        var roots=jdbc.query("""
            SELECT e.* FROM ledger_event e JOIN demo_simulation_account a ON a.account_id=e.account_id
            WHERE a.dataset_id=? ORDER BY e.occurred_at,e.id
            """,(rs,n)->new Root(rs.getObject("id",UUID.class),rs.getObject("account_id",UUID.class),
                rs.getString("event_type"),rs.getLong("account_delta"),instant(rs,"occurred_at"),
                rs.getObject("deposit_balance_observation_id",UUID.class),rs.getObject("correction_of_event_id",UUID.class)),dataset);
        // Select by root ownership so an inconsistent effect account cannot silently disappear.
        var effects=jdbc.query("""
            SELECT f.* FROM ledger_wish_effect f JOIN ledger_event e ON e.id=f.event_id
            JOIN demo_simulation_account a ON a.account_id=e.account_id
            WHERE a.dataset_id=? ORDER BY f.event_id,f.id
            """,(rs,n)->new Effect(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getObject("account_id",UUID.class),rs.getObject("wish_id",UUID.class),rs.getLong("wish_delta")),dataset);
        return new State(1,"simulation-allocation-state",dataset,wishes,roots,effects);
    }
    /** Independent of the production balance calculators; does not repair observed rows. */
    public static Verification verify(State state,String dataset,Map<String,UUID> identities,Instant cutoff) {
        check(state.schemaVersion()==1 && state.schemaKind().equals("simulation-allocation-state") && state.datasetId().equals(dataset),"BINDING");
        Set<UUID> accounts=new HashSet<>(), expectedWishes=new HashSet<>();
        identities.forEach((key,id)->{if(key.startsWith("ACCOUNT:"))accounts.add(id);if(key.startsWith("WISH:"))expectedWishes.add(id);});
        Map<UUID,Wish> wishes=new HashMap<>();Map<UUID,Root> roots=new HashMap<>();
        Map<UUID,List<Effect>> byRoot=new HashMap<>();Map<UUID,Long> amounts=new HashMap<>();
        for(Wish w:state.wishes()) {
            check(wishes.putIfAbsent(w.id(),w)==null && accounts.contains(w.accountId()),"WISH_IDENTITY");
            time(w.createdAt(),cutoff);if(w.completedAt()!=null)time(w.completedAt(),cutoff);if(w.deletedAt()!=null)time(w.deletedAt(),cutoff);
            check(w.targetAmount()>0 && w.amount()>=0 && w.amount()<=w.targetAmount(),"WISH_AMOUNT");
        }
        check(wishes.keySet().equals(expectedWishes),"WISH_SET");
        for(Root r:state.roots()) {
            check(roots.putIfAbsent(r.id(),r)==null && accounts.contains(r.accountId()),"ROOT_IDENTITY");time(r.occurredAt(),cutoff);
            // Correction execution is not yet supported; never silently treat it as a normal event.
            check(r.correctionOfEventId()==null,"CORRECTION_UNSUPPORTED");
        }
        Set<UUID> effectIds=new HashSet<>();Set<String> pairs=new HashSet<>();
        for(Effect e:state.effects()) {
            Root r=roots.get(e.eventId());Wish w=wishes.get(e.wishId());
            check(effectIds.add(e.id()) && pairs.add(e.eventId()+":"+e.wishId()),"EFFECT_DUPLICATE");
            check(r!=null && w!=null && r.accountId().equals(e.accountId()) && w.accountId().equals(e.accountId()),"EFFECT_FOREIGN_KEY");
            check(!r.occurredAt().isBefore(w.createdAt()),"EFFECT_BEFORE_WISH");
            byRoot.computeIfAbsent(e.eventId(),ignored->new ArrayList<>()).add(e);
            amounts.merge(e.wishId(),e.delta(),Math::addExact);
        }
        for(Root r:state.roots()) {
            List<Effect> es=byRoot.getOrDefault(r.id(),List.of());
            if(r.kind().equals("CARD_BALANCE_CHANGE")) {
                check(es.isEmpty() && r.depositObservationId()==null,"CASH_ROOT_EFFECT");continue;
            }
            check(r.accountDelta()==0,"ALLOCATION_CHANGED_CASH");
            switch(r.kind()) {
                case "WISH_DEPOSIT" -> check(es.size()==1 && es.get(0).delta()>0 && r.depositObservationId()!=null,"DEPOSIT_SHAPE");
                case "WISH_WITHDRAWAL", "WISH_COMPLETION_RETURN", "WISH_ABANDONMENT_RETURN", "WISH_DELETION_RETURN" ->
                    check(es.size()==1 && es.get(0).delta()<0 && r.depositObservationId()==null,"RETURN_SHAPE");
                case "WISH_TRANSFER" -> check(es.size()==2 && es.get(0).delta()!=0
                    && Math.addExact(es.get(0).delta(),es.get(1).delta())==0 && r.depositObservationId()==null,"TRANSFER_SHAPE");
                default -> throw new IllegalStateException("ALLOCATION_UNKNOWN_ROOT");
            }
        }
        long total=0;
        for(Wish w:state.wishes()) {
            check(amounts.getOrDefault(w.id(),0L)==w.amount(),"STORED_AMOUNT_MISMATCH");
            boolean terminal=Set.of("COMPLETED","ABANDONED").contains(w.state());
            check(Set.of("IN_PROGRESS","AMOUNT_REACHED","COMPLETED","ABANDONED").contains(w.state()),"WISH_STATE");
            check(!(terminal || w.deletedAt()!=null) || w.amount()==0,"TERMINAL_AMOUNT");
            if(w.deletedAt()==null && !terminal)check(w.state().equals(w.amount()==w.targetAmount()?"AMOUNT_REACHED":"IN_PROGRESS"),"STATE_AMOUNT");
            check(w.state().equals("COMPLETED")== (w.completedAt()!=null),"COMPLETION_TIME");
            check(w.completedAt()==null || !w.completedAt().isBefore(w.createdAt()),"COMPLETION_TIME");
            check(w.deletedAt()==null || !w.deletedAt().isBefore(w.createdAt()),"DELETION_TIME");
            total=Math.addExact(total,w.amount());
        }
        // Every command-bound actual monetary root must appear; implicit observations are also exported above.
        identities.forEach((key,id)->{if(key.startsWith("LEDGER_ROOT:"))check(roots.containsKey(id),"MISSING_COMMAND_ROOT");});
        return new Verification(wishes.size(),roots.size(),state.effects().size(),total);
    }
    /** Match allocation effects to accepted command inputs, preserving first-execution idempotency. */
    static void verifyCommands(State state,Map<String,UUID> identities,List<tools.jackson.databind.JsonNode> commands) {
        Map<UUID,Root> roots=new HashMap<>();state.roots().forEach(r->roots.put(r.id(),r));
        Map<UUID,List<Effect>> effects=new HashMap<>();
        state.effects().forEach(e->effects.computeIfAbsent(e.eventId(),ignored->new ArrayList<>()).add(e));
        Map<String,String> kinds=Map.of("DEPOSIT","WISH_DEPOSIT","WITHDRAW","WISH_WITHDRAWAL","TRANSFER","WISH_TRANSFER",
            "COMPLETE","WISH_COMPLETION_RETURN","ABANDON","WISH_ABANDONMENT_RETURN","DELETE","WISH_DELETION_RETURN");
        Set<UUID> checked=new HashSet<>();
        for(var e:commands) {
            String kind=e.get("kind").asString();if(!kinds.containsKey(kind))continue;
            var c=e.get("command");String logical=kind.equals("TRANSFER")?c.get("rootEventId").asString():e.get("eventId").asString();
            UUID rootId=identities.get("LEDGER_ROOT:"+logical);
            if(rootId==null)continue; // No-money terminal operations and already replayed idempotency keys have no new root.
            check(e.get("outcome").get("status").asString().equals("APPLIED"),"REJECTED_COMMAND_ROOT");
            if(!checked.add(rootId))continue;
            Root r=roots.get(rootId);check(r!=null && r.kind().equals(kinds.get(kind)),"COMMAND_ROOT_TYPE");
            check(r.accountId().equals(identities.get("ACCOUNT:"+c.get("accountId").asString()))
                && r.occurredAt().equals(Instant.parse(e.get("occurredAt").asString())),"COMMAND_ROOT_PROVENANCE");
            List<Effect> es=effects.getOrDefault(rootId,List.of());
            if(kind.equals("TRANSFER")) {
                check(es.size()==2,"COMMAND_TRANSFER");
                UUID source=identities.get("WISH:"+c.get("sourceWishId").asString());
                UUID destination=identities.get("WISH:"+c.get("destinationWishId").asString());
                long amount=c.get("amount").longValue();
                check(es.stream().anyMatch(f->f.wishId().equals(source) && f.delta()==-amount)
                    && es.stream().anyMatch(f->f.wishId().equals(destination) && f.delta()==amount),"COMMAND_TRANSFER");
            } else {
                check(es.size()==1 && es.get(0).wishId().equals(identities.get("WISH:"+c.get("wishId").asString())),"COMMAND_WISH");
                if(kind.equals("DEPOSIT") || kind.equals("WITHDRAW"))
                    check(es.get(0).delta()==c.get("amount").longValue()*(kind.equals("DEPOSIT")?1:-1),"COMMAND_AMOUNT");
            }
        }
        for(Root r:state.roots())if(!r.kind().equals("CARD_BALANCE_CHANGE"))check(checked.contains(r.id()),"ORPHAN_COMMAND_ROOT");
    }
    private static void time(Instant at,Instant cutoff) {
        check(at!=null && !at.isBefore(SimulationCashOracle.START) && at.isBefore(SimulationCashOracle.END) && !at.isAfter(cutoff),"TIME");
    }
    private static void check(boolean valid,String code) { if(!valid)throw new IllegalStateException("ALLOCATION_"+code); }
}
