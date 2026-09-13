package com.crabit.backend.simulation;

import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Simulation-only references to actual earlier responses; never constructs or resigns a cursor. */
public final class SimulationFeedContinuation {
    private static final String PREFIX="event:";
    private static final Pattern REFERENCE=Pattern.compile("event:([A-Za-z0-9:_-]{1,160}):nextCursor");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationFeedContinuation() {}

    /** Returns null for an initial page or literal service cursor. Called during preflight too. */
    public static String source(JsonNode event,Map<String,JsonNode> prior) {
        if(!event.get("kind").asString().equals("FEED_QUERY"))return null;
        JsonNode cursor=event.get("command").get("cursor");
        if(cursor.isNull() || !cursor.asString().startsWith(PREFIX))return null;
        var match=REFERENCE.matcher(cursor.asString());
        check(match.matches(),"SYNTAX");String id=match.group(1);
        JsonNode previous=prior.get(id);
        check(previous!=null && previous.get("sequence").longValue()<event.get("sequence").longValue(),"NOT_EARLIER");
        boolean cause=false;for(JsonNode entry:event.get("causes"))if(entry.asString().equals(id))cause=true;
        check(cause,"CAUSE_REQUIRED");
        check(previous.get("kind").asString().equals("FEED_QUERY")
            && previous.get("outcome").get("status").asString().equals("APPLIED"),"SOURCE_NOT_APPLIED_FEED");
        check(previous.get("actorStudentId").equals(event.get("actorStudentId"))
            && previous.get("command").get("academyId").equals(event.get("command").get("academyId")),"SCOPE");
        return id;
    }

    public static String resolve(JsonNode event,Map<String,JsonNode> prior,
        Map<String,SimulationCommandDispatcher.Result> results) {
        JsonNode cursor=event.get("command").get("cursor");String source=source(event,prior);
        if(source==null)return cursor.isNull()?null:cursor.asString();
        var actual=results.get(source);check(actual!=null && actual.status().equals("APPLIED"),"RESULT_NOT_APPLIED");
        JsonNode next=JSON.readTree(actual.rawResult()).get("nextCursor");
        check(next!=null && next.isString() && !next.asString().isBlank(),"NO_NEXT_PAGE");
        return next.asString();
    }
    private static void check(boolean valid,String code) {
        if(!valid)throw new IllegalStateException("FEED_CONTINUATION_"+code);
    }
}
