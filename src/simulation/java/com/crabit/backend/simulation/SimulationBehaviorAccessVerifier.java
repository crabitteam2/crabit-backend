package com.crabit.backend.simulation;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Reconstructs access from executed command order, independently of production queries and current DB state. */
public final class SimulationBehaviorAccessVerifier {
    private SimulationBehaviorAccessVerifier() {}
    public record Verification(int feedPages, int feedCards, int collectedCards, int profileVisits) {}
    private record Pair(String source, String target) {}
    private record Wish(String owner, String academy, String visibility, boolean deleted) {}

    public static Verification verify(SimulationRelationalState.Export export, List<JsonNode> commands,
                                      Map<String,UUID> identities) {
        Map<String,String> memberships=new HashMap<>();
        Map<String,Wish> wishes=new HashMap<>();
        Map<String,String> activeCards=new HashMap<>(); // logical wish -> actual current card
        Set<Pair> follows=new HashSet<>(), blocks=new HashSet<>();
        Map<String,List<JsonNode>> pageItems=new HashMap<>();
        for(JsonNode row:export.state().tables().get("behavior_result_item"))
            pageItems.computeIfAbsent(s(row,"context_id"),ignored->new ArrayList<>()).add(row);
        Map<String,JsonNode> behavior=new HashMap<>();
        for(JsonNode row:export.state().tables().get("behavior_event"))
            check(behavior.put(s(row,"actor_id")+":"+s(row,"event_id"),row)==null,"DUPLICATE_BEHAVIOR","export");
        int pages=0,cards=0,collected=0,visits=0;
        long previous=0;
        for(JsonNode event:commands) {
            String eventId=s(event,"eventId"),actor=s(event,"actorStudentId"),kind=s(event,"kind");
            long sequence=event.get("sequence").longValue();
            check(sequence>previous,"COMMAND_ORDER",eventId);previous=sequence;
            if(!s(event.get("outcome"),"status").equals("APPLIED"))continue;
            JsonNode c=event.get("command");
            switch(kind) {
                case "JOIN" -> check(memberships.putIfAbsent(actor,s(c,"academyId"))==null,"DUPLICATE_JOIN",eventId);
                case "CREATE" -> {
                    check(memberships.containsKey(actor),"MEMBERSHIP",eventId);
                    // Repeated successful idempotency requests must not restore PRIVATE or undelete a wish.
                    wishes.putIfAbsent(s(c,"wishId"),new Wish(actor,memberships.get(actor),"PRIVATE",false));
                }
                case "SHARE", "VISIBILITY_CHANGE" -> {
                    String wishId=s(c,"wishId");Wish old=wishes.get(wishId);
                    check(old!=null && old.owner().equals(actor) && !old.deleted(),"WISH_TRANSITION",eventId);
                    String visibility=s(c,"visibility");
                    check(Set.of("PRIVATE","FOLLOWERS","ACADEMY").contains(visibility),"VISIBILITY",eventId);
                    wishes.put(wishId,new Wish(old.owner(),old.academy(),visibility,false));
                    if(visibility.equals("PRIVATE"))activeCards.remove(wishId);
                    else if(!activeCards.containsKey(wishId))
                        activeCards.put(wishId,id(identities,"SHARED_CARD",eventId,eventId));
                }
                case "DELETE" -> {
                    String wishId=s(c,"wishId");Wish old=wishes.get(wishId);
                    check(old!=null && old.owner().equals(actor),"WISH_TRANSITION",eventId);
                    wishes.put(wishId,new Wish(old.owner(),old.academy(),old.visibility(),true));activeCards.remove(wishId);
                }
                case "FOLLOW", "UNFOLLOW", "BLOCK", "UNBLOCK" -> {
                    String target=s(c,"ownerStudentId");Pair forward=new Pair(actor,target),reverse=new Pair(target,actor);
                    check(actor.equals(s(c,"viewerStudentId")) && !actor.equals(target)
                        && memberships.containsKey(actor) && memberships.containsKey(target),"RELATIONSHIP",eventId);
                    if(kind.equals("FOLLOW") || kind.equals("UNFOLLOW")) {
                        check(s(c,"academyId").equals(memberships.get(actor))
                            && s(c,"academyId").equals(memberships.get(target))
                            && !blocked(blocks,actor,target),"RELATIONSHIP_ACCESS",eventId);
                        if(kind.equals("FOLLOW"))follows.add(forward);else follows.remove(forward);
                    } else if(kind.equals("BLOCK")) {
                        check(blocks.add(forward),"DUPLICATE_BLOCK",eventId);
                        // Blocking ends both directed follows. Unblocking never restores either one.
                        follows.remove(forward);follows.remove(reverse);
                    } else check(blocks.remove(forward),"MISSING_BLOCK",eventId);
                }
                case "FEED_QUERY" -> {
                    String academy=s(c,"academyId");
                    check(academy.equals(memberships.get(actor)),"MEMBERSHIP",eventId);
                    String context=id(identities,"FEED_CONTEXT",s(c,"resultContextId"),eventId);
                    for(JsonNode item:pageItems.getOrDefault(context,List.of())) {
                        requireCard(s(item,"card_id"),actor,academy,memberships,wishes,activeCards,follows,blocks,eventId);
                        cards++;
                    }
                    pages++;
                }
                case "CLICK", "IMPRESSION", "PROFILE_VISIT" -> {
                    String actorUuid=id(identities,"STUDENT",actor,eventId);
                    JsonNode row=behavior.get(actorUuid+":"+id(identities,"BEHAVIOR_EVENT",eventId,eventId));
                    check(row!=null,"MISSING_BEHAVIOR",eventId);
                    String academy=s(c,"academyId");
                    if(kind.equals("PROFILE_VISIT")) {
                        String target=s(c,"targetStudentId");
                        check(!actor.equals(target) && academy.equals(memberships.get(actor))
                            && academy.equals(memberships.get(target)) && !blocked(blocks,actor,target),"PROFILE_DENIED",eventId);
                        visits++;
                    } else {
                        requireCard(s(row,"card_id"),actor,academy,memberships,wishes,activeCards,follows,blocks,eventId);
                        collected++;
                    }
                }
                default -> { /* Other supported financial commands do not alter access. */ }
            }
        }
        return new Verification(pages,cards,collected,visits);
    }

    private static void requireCard(String card,String actor,String academy,Map<String,String> memberships,
                                    Map<String,Wish> wishes,Map<String,String> activeCards,Set<Pair> follows,
                                    Set<Pair> blocks,String event) {
        String wishId=activeCards.entrySet().stream().filter(entry->entry.getValue().equals(card))
            .map(Map.Entry::getKey).findFirst().orElse(null);
        Wish wish=wishId==null?null:wishes.get(wishId);
        check(wish!=null && !wish.deleted() && !wish.owner().equals(actor)
            && academy.equals(wish.academy()) && academy.equals(memberships.get(actor))
            && academy.equals(memberships.get(wish.owner())) && !blocked(blocks,actor,wish.owner())
            && (wish.visibility().equals("ACADEMY") || wish.visibility().equals("FOLLOWERS")
                && follows.contains(new Pair(actor,wish.owner()))),"CARD_DENIED",event);
    }
    private static boolean blocked(Set<Pair> blocks,String actor,String target) {
        return blocks.contains(new Pair(actor,target)) || blocks.contains(new Pair(target,actor));
    }
    private static String id(Map<String,UUID> identities,String kind,String logical,String event) {
        UUID id=identities.get(kind+":"+logical);check(id!=null,"IDENTITY",event);return id.toString();
    }
    private static String s(JsonNode node,String field) { return node.get(field).asString(); }
    private static void check(boolean valid,String rule,String event) {
        if(!valid)throw new IllegalStateException("BEHAVIOR_ACCESS_"+rule+" event="+event);
    }
}
