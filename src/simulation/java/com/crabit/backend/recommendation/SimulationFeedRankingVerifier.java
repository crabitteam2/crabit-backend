package com.crabit.backend.recommendation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Independent feed-rules-v1 oracle over the recorded wire request; never calls the ranker. */
public final class SimulationFeedRankingVerifier {
    private SimulationFeedRankingVerifier() {}
    private record Candidate(JsonNode row, int position, double score, int type) {
        String id() { return row.path("card_id").asString(); }
        String category() { return row.path("category_id").asString(); }
        boolean role() { return row.path("state").asString().equals("COMPLETED") && (type==1 || type==2); }
    }
    private static final Comparator<Candidate> SCORE=Comparator.comparingDouble(Candidate::score).reversed()
        .thenComparingInt(Candidate::position);

    public static Map<String,Object> verify(JsonNode request, JsonNode response) {
        // Structural membership/composition remains a separate prerequisite.
        SimulationFeedCompositionVerifier.verify(request,response);
        List<String> expected=expectedOrder(request);
        var actual=new ArrayList<String>();response.path("ordered_card_ids").forEach(n->actual.add(n.asString()));
        if(!expected.equals(actual))throw new IllegalArgumentException("FEED_RANKING_ORDER");
        return Map.of("rankingAlgorithmVerified",true,"modelVersion","feed-rules-v1",
            "candidateCount",request.path("candidates").size(),"orderedCardIds",expected,
            "inputOrderTieBreakVerified",true);
    }

    static List<String> expectedOrder(JsonNode request) {
        Instant now=Instant.parse(request.path("recommendation_at").asString());
        var viewer=request.path("viewer_previous_month");int viewerType=type(viewer);
        var scored=new ArrayList<Candidate>();int position=0;
        for(var row:request.path("candidates")) {
            var author=row.path("author_previous_month");int authorType=type(author);
            double relevance=viewerType==0 || authorType==0?.4:viewerType==authorType?1:authorType<=2?.7:.4;
            double pace=0;
            if(viewerType!=0 && authorType!=0) {
                double v=viewer.path("values").path("deposit_count").asDouble();
                double a=author.path("values").path("deposit_count").asDouble();
                pace=1-Math.abs(v-a)/Math.max(1,Math.max(v,a));
            }
            double seconds=Duration.between(Instant.parse(row.path("content_updated_at").asString()),now).toNanos()/1_000_000_000.0;
            // Preserve the specified feature summation order and binary64 arithmetic.
            double score=.20*row.path("basic_similarity").asDouble();
            score+=.05*row.path("title_similarity").asDouble();
            score+=.10*(row.path("visited_author_before").asBoolean()?1:0);
            score+=.05*(row.path("visited_category_before").asBoolean()?1:0);
            score+=.20*relevance;
            score+=.10*pace;
            score+=.15*(row.path("state").asString().equals("COMPLETED")?1:.5);
            score+=.15*Math.max(0,1-Math.max(seconds,0)/86400/14);
            if(!Double.isFinite(score))throw new IllegalArgumentException("FEED_RANKING_SCORE");
            scored.add(new Candidate(row,position++,score,authorType));
        }
        scored.sort(SCORE);
        if(scored.isEmpty())return List.of();
        Candidate recent=scored.stream().filter(c->recent(c,now)).findFirst().orElse(null);
        var roles=scored.stream().filter(Candidate::role).toList();
        var pool=new ArrayList<>(scored.subList(0,Math.min(40,scored.size())));
        var protectedCards=new ArrayList<Candidate>();if(recent!=null)protectedCards.add(recent);protectedCards.addAll(roles);
        for(var card:protectedCards)if(!pool.contains(card))pool.set(pool.size()-1,card);
        pool=new ArrayList<>(new LinkedHashSet<>(pool));pool.sort(SCORE);
        var selected=new ArrayList<Candidate>();
        while(!pool.isEmpty() && selected.size()<Math.min(20,scored.size())) {
            Candidate best=pool.getFirst();double bestValue=Double.NEGATIVE_INFINITY;
            for(var card:pool) {
                double redundancy=0;
                for(var previous:selected) {
                    double pair=card.category().equals(previous.category())?1:
                        card.row.path("author_id").equals(previous.row.path("author_id"))?.8:0;
                    redundancy=Math.max(redundancy,pair);
                }
                double value=selected.isEmpty()?card.score:.7*card.score-.3*redundancy;
                if(value>bestValue || (value==bestValue && card.position<best.position)) {best=card;bestValue=value;}
            }
            selected.add(best);pool.remove(best);
        }
        if(recent!=null && selected.stream().noneMatch(c->recent(c,now)))replaceWeakest(selected,recent,selected.size());
        int target=Math.min(2,Math.min(roles.size(),Math.min(10,selected.size())));
        for(var role:roles) {
            if(roleCount(selected)>=target)break;
            int index=selected.indexOf(role);
            if(index>=0)Collections.swap(selected,index,firstNonRole(selected));
            else replaceWeakest(selected,role,10);
        }
        var spaced=new ArrayList<Candidate>();pool=new ArrayList<>(selected);
        while(!pool.isEmpty()) {
            String blocked=spaced.size()>=2 && spaced.getLast().category().equals(spaced.get(spaced.size()-2).category())
                ?spaced.getLast().category():null;
            int next=0;
            if(blocked!=null)for(int i=0;i<pool.size();i++)if(!pool.get(i).category().equals(blocked)){next=i;break;}
            spaced.add(pool.remove(next));
        }
        for(var role:roles) {
            if(roleCount(spaced)>=target)break;
            int index=spaced.indexOf(role);if(index>=10)Collections.swap(spaced,index,firstNonRole(spaced));
        }
        if(recent!=null && !spaced.contains(recent))replaceWeakest(spaced,recent,spaced.size());
        return spaced.stream().map(Candidate::id).toList();
    }
    private static int type(JsonNode month) {
        if(!month.path("coverage").asString().equals("COMPLETE"))return 0;
        var v=month.path("values");long count=v.path("deposit_count").asLong();
        if(count>=8 && v.hasNonNull("regularity_std") && v.path("regularity_std").asDouble()<4)return 1;
        if(count>=5 && v.path("avg_amount").asDouble()<2000)return 2;
        if(count<5 && v.hasNonNull("pace_bias") && v.path("pace_bias").asDouble()>.3)return 3;
        return 4;
    }
    private static boolean recent(Candidate c,Instant now) {
        if(!c.row.path("state").asString().equals("COMPLETED") || !c.row.hasNonNull("closed_at"))return false;
        Instant closed=Instant.parse(c.row.path("closed_at").asString());
        return !closed.isAfter(now) && !closed.isBefore(now.minusSeconds(172800));
    }
    private static long roleCount(List<Candidate> items) {return items.subList(0,Math.min(10,items.size())).stream().filter(Candidate::role).count();}
    private static int firstNonRole(List<Candidate> items) {
        for(int i=0;i<Math.min(10,items.size());i++)if(!items.get(i).role())return i;
        throw new IllegalArgumentException("FEED_RANKING_ROLE_WINDOW");
    }
    private static void replaceWeakest(List<Candidate> items,Candidate replacement,int window) {
        if(items.contains(replacement))return;
        int end=Math.min(window,items.size());
        var choices=new ArrayList<Integer>();for(int i=0;i<end;i++)if(!items.get(i).role())choices.add(i);
        if(choices.isEmpty())for(int i=0;i<end;i++)choices.add(i);
        choices.stream().min(Comparator.<Integer>comparingDouble(i->items.get(i).score)
            .thenComparing(Comparator.<Integer>comparingInt(i->items.get(i).position).reversed()))
            .ifPresent(i->items.set(i,replacement));
    }
}
