package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationRelationalInputIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final SimulationRecapDispatcherIT f=new SimulationRecapDispatcherIT();
    private byte[] bytes(JsonNode n) {return JSON.writeValueAsBytes(n);}
    private ObjectNode row(ObjectNode state,String table) {return (ObjectNode)state.get("tables").get(table).get(0);}
    @Test void actualPostgresBytesRoundTripRejectTamperingAndPreserveState() throws Exception {
        try(var d=new SimulationCommandDispatcher(f.schema(),f.DATASET,f.DATASET,f.people())) {
            assertThatThrownBy(()->d.readRelationalState("{}".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("RELATIONAL_CATALOG_REQUIRED");
            d.execute(f.event("JOIN",f.START,Map.of("studentId",f.ACTOR,"accountId",f.ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
            d.execute(f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId",f.ACCOUNT,"amountKrw",20000,"cashEntryId","grant","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
            var create=new HashMap<String,Object>();create.put("accountId",f.ACCOUNT);create.put("wishId","wish");create.put("idempotencyKey","create");
            create.put("purpose","한글 입력 ' ; DROP TABLE student; --");create.put("targetAmount",5000);create.put("startDate","2026-06-01");create.put("targetDate","2026-08-31");create.put("photoId",null);
            d.execute(f.event("CREATE",f.START.plusSeconds(2),create,"APPLIED"));
            d.execute(f.event("DEPOSIT",f.START.plusSeconds(3),Map.of("accountId",f.ACCOUNT,"wishId","wish","amount",4000,"expectedVersion",0,"idempotencyKey","deposit"),"APPLIED"));
            var export=d.relationalState();var before=d.preservationFingerprint();
            byte[] source=JSON.writeValueAsBytes(export.state());byte[] saved=source.clone();
            ObjectNode root=(ObjectNode)JSON.readTree(source);
            assertThat(d.readRelationalState(source)).isEqualTo(export);
            var verification=d.verifyRelationalState(export);
            List<Consumer<ObjectNode>> corruptions=List.of(
                n->row(n,"student").put("age","9"),
                n->row(n,"wish").put("wish_amount",1.5),
                n->row(n,"wish").set("target_amount",JSON.readTree("9223372036854775808")),
                n->row(n,"demo_simulation_account").put("is_owner","true"),
                n->row(n,"student").put("id","1-1-1-1-1"),
                n->row(n,"wish").put("start_date","2026-02-30"),
                n->row(n,"wish").put("created_at","2026-06-01T00:00:00.0000001Z"),
                n->row(n,"wish").putObject("purpose").put("sql","DROP TABLE wish"),
                n->row(n,"student").putObject("wish_idempotency_records").put("bad","\u0000"));
            for(var corrupt:corruptions) {
                ObjectNode bad=root.deepCopy();corrupt.accept(bad);
                assertThatThrownBy(()->d.readRelationalState(bytes(bad))).hasMessage("RELATIONAL_INPUT_VALUE_TYPE");
            }
            ObjectNode catalog=root.deepCopy();catalog.set("catalog",JSON.valueToTree(export.catalog()));
            assertThatThrownBy(()->d.readRelationalState(bytes(catalog))).hasMessage("RELATIONAL_INPUT_FIELDS");
            ObjectNode binding=root.deepCopy();binding.put("catalogDigest","sha256:"+"0".repeat(64));
            assertThatThrownBy(()->d.readRelationalState(bytes(binding))).hasMessage("RELATIONAL_BINDING");
            ObjectNode table=root.deepCopy();((ObjectNode)table.get("tables")).putArray("relationship_cursor_key");
            assertThatThrownBy(()->d.readRelationalState(bytes(table))).hasMessage("RELATIONAL_INPUT_TABLES");
            ObjectNode rows=root.deepCopy();((ObjectNode)rows.get("tables")).putObject("student");
            assertThatThrownBy(()->d.readRelationalState(bytes(rows))).hasMessage("RELATIONAL_INPUT_ROWS");
            String raw=new String(source,StandardCharsets.UTF_8);
            for(String bad:List.of(raw+" {}",raw.replaceFirst("\\{","{\"schemaVersion\":1,")))
                assertThatThrownBy(()->d.readRelationalState(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(SimulationBundleReader.Rejection.class);
            assertThatThrownBy(()->d.readRelationalState(new byte[]{(byte)0xc3,(byte)0x28})).isInstanceOf(SimulationBundleReader.Rejection.class);
            assertThatThrownBy(()->d.readRelationalState(new byte[0])).hasMessage("RELATIONAL_INPUT_BYTE_LIMIT");
            assertThat(source).isEqualTo(saved);assertThat(d.preservationFingerprint()).isEqualTo(before);
            assertThat(d.relationalState().state()).isEqualTo(export.state());
            Path out=Path.of("build/simulation-relational-input");Files.createDirectories(out);
            Files.writeString(out.resolve("observation.json"),JSON.writeValueAsString(Map.of(
                "commands",4,"verification",verification,"typeCorruptionsRejected",corruptions.size(),
                "databasePreserved",true,"sourceBytesPreserved",true,"readyForApplication",false)));
        }
    }
}
