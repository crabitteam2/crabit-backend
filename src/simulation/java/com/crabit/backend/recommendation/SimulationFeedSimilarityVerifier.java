package com.crabit.backend.recommendation;

import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Raw-row feature oracle. Does not call production classifier, similarity or representative selection. */
public final class SimulationFeedSimilarityVerifier {
    private static final String DIGEST="de23b80260907e3d818892c0ea6ba2d9d28251e49d75a812fb925e1a47733f61";
    private static final JsonNode MODEL=load();
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private SimulationFeedSimilarityVerifier() {}
    private static JsonNode load() {
        try {
            byte[] bytes=Files.readAllBytes(Path.of("api/recommendation/feed-classifier-v1.json"));
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(DIGEST),"MODEL_DIGEST");
            return JsonMapper.builder().build().readTree(bytes);
        }catch(java.io.IOException|GeneralSecurityException e){throw new IllegalStateException("FEED_FEATURE_MODEL_UNAVAILABLE",e);}
    }
    public static void verify(JsonNode request,JsonNode source) {
        check(request.path("feature_version").asString().equals("feed-features-v1"),"FEATURE_VERSION");
        check(request.path("classifier_version").asString().equals("wish-category-v1@sha256:"+DIGEST),"CLASSIFIER_VERSION");
        String viewer=request.get("viewer_id").asString(),academy=request.get("academy_id").asString();
        String account=null;
        for(var row:source.get("card_balance_account"))if(text(row,"student_id").equals(viewer)&&text(row,"academy_id").equals(academy)&&!row.hasNonNull("closed_at")) {
            check(account==null,"VIEWER_ACCOUNT");account=text(row,"id");
        }
        check(account!=null,"VIEWER_ACCOUNT");
        Map<String,JsonNode> wishes=new HashMap<>(),cards=new HashMap<>();
        for(var w:source.get("wish"))check(wishes.put(text(w,"id"),w)==null,"WISH_ID");
        for(var c:source.get("shared_card"))check(cards.put(text(c,"id"),c)==null,"CARD_ID");
        JsonNode selections=source.get("representative_wish_selection");check(selections!=null&&selections.isArray(),"SELECTION_SOURCE");
        JsonNode representative=null;int selectionCount=0;
        for(var selection:selections)if(text(selection,"account_id").equals(account)) {
            check(++selectionCount==1,"DUPLICATE_SELECTION");
            JsonNode w=wishes.get(text(selection,"wish_id"));
            check(w!=null&&text(w,"account_id").equals(account),"SELECTION_OWNER");
            if(!w.hasNonNull("deleted_at")&&Set.of("IN_PROGRESS","AMOUNT_REACHED").contains(text(w,"state")))representative=w;
        }
        if(representative==null) {
            List<JsonNode> active=new ArrayList<>();
            for(var w:wishes.values())if(text(w,"account_id").equals(account)&&!w.hasNonNull("deleted_at")&&text(w,"state").equals("IN_PROGRESS"))active.add(w);
            active.sort(Comparator.<JsonNode,Instant>comparing(w->Instant.parse(text(w,"created_at"))).thenComparing(w->text(w,"id")));
            if(!active.isEmpty())representative=active.getFirst();
        }
        String category=representative==null?null:classify(text(representative,"purpose"));
        for(var candidate:request.get("candidates")) {
            JsonNode card=cards.get(text(candidate,"card_id"));check(card!=null,"CANDIDATE_CARD");
            JsonNode wish=wishes.get(text(card,"wish_id"));check(wish!=null,"CANDIDATE_WISH");
            String wanted=classify(text(wish,"purpose"));
            check(text(candidate,"category_id").equals(wanted),"CATEGORY");
            double basic=0,title=0;
            if(representative!=null) {
                int matches=Objects.equals(category,wanted)?1:0;
                if(amount(representative)==amount(wish))matches++;
                if(deadline(representative)==deadline(wish))matches++;
                basic=matches/3.0;title=ratio(text(representative,"purpose"),text(wish,"purpose"));
            }
            number(candidate,"basic_similarity",basic);number(candidate,"title_similarity",title);
        }
    }
    private static int amount(JsonNode wish) {
        long value=wish.get("target_amount").asLong();int result=0;
        for(long boundary:new long[]{10000,30000,50000,100000,300000})if(value>=boundary)result++;
        return result;
    }
    private static int deadline(JsonNode wish) {
        if(!wish.hasNonNull("target_date"))return 4;
        long days=ChronoUnit.DAYS.between(Instant.parse(text(wish,"created_at")).atZone(SEOUL).toLocalDate(),LocalDate.parse(text(wish,"target_date")));
        return days<30?0:days<90?1:days<180?2:3;
    }
    static String classify(String title) {
        // Sparse n-gram multiset, evaluated against independently loaded pinned model bytes.
        Map<String,Integer> grams=new HashMap<>();
        for(String word:title.toLowerCase(Locale.ROOT).split("[\\p{javaWhitespace}\\p{Z}\\u0085]+"))if(!word.isEmpty()) {
            int[] points=(" "+word+" ").codePoints().toArray();
            for(int size=2;size<=3;size++)for(int offset=0;offset+size<=points.length;offset++)
                grams.merge(new String(points,offset,size),1,Integer::sum);
        }
        int size=MODEL.get("vocabulary").size();double[] vector=new double[size];double norm=0;
        for(int i=0;i<size;i++) {
            vector[i]=grams.getOrDefault(MODEL.get("vocabulary").get(i).asString(),0)*MODEL.get("idf").get(i).asDouble();
            norm+=vector[i]*vector[i];
        }
        if(norm==0)return "기타";
        norm=Math.sqrt(norm);double best=-1;int winner=0;
        for(int c=0;c<MODEL.get("categories").size();c++) {
            double dot=0,denominator=0;
            for(int i=0;i<size;i++){double weight=MODEL.get("weights").get(c).get(i).asDouble();dot+=(vector[i]/norm)*weight;denominator+=weight*weight;}
            double score=dot/Math.sqrt(denominator);if(score>best){best=score;winner=c;}
        }
        return best<MODEL.get("threshold").asDouble()?"기타":MODEL.get("categories").get(winner).asString();
    }
    static double ratio(String left,String right) {
        int[] a=left.codePoints().toArray(),b=right.codePoints().toArray();
        if(a.length+b.length==0)return 1;
        Map<Integer,Integer> counts=new HashMap<>();for(int cp:b)counts.merge(cp,1,Integer::sum);
        Set<Integer> popular=new HashSet<>();if(b.length>=200)counts.forEach((cp,n)->{if(n>b.length/100+1)popular.add(cp);});
        return 2.0*matches(a,b,0,a.length,0,b.length,popular)/(a.length+b.length);
    }
    private static int matches(int[] a,int[] b,int lo,int hi,int start,int end,Set<Integer> popular) {
        int ai=lo,bi=start,longest=0;
        // Exhaustive seed search uses earliest left then earliest right on equal lengths.
        for(int i=lo;i<hi;i++)for(int j=start;j<end;j++) {
            int length=0;
            while(i+length<hi&&j+length<end&&a[i+length]==b[j+length]&&!popular.contains(b[j+length]))length++;
            if(length>longest){ai=i;bi=j;longest=length;}
        }
        while(ai>lo&&bi>start&&a[ai-1]==b[bi-1]){ai--;bi--;longest++;}
        while(ai+longest<hi&&bi+longest<end&&a[ai+longest]==b[bi+longest])longest++;
        if(longest==0)return 0;
        int total=longest;
        if(lo<ai&&start<bi)total+=matches(a,b,lo,ai,start,bi,popular);
        if(ai+longest<hi&&bi+longest<end)total+=matches(a,b,ai+longest,hi,bi+longest,end,popular);
        return total;
    }
    private static void number(JsonNode row,String field,double expected){var n=row.get(field);check(n!=null&&n.isNumber()&&Double.compare(n.asDouble(),expected)==0,field);}
    private static String text(JsonNode row,String field){var n=row.get(field);check(n!=null&&n.isString(),"FIELD:"+field);return n.asString();}
    private static void check(boolean valid,String rule){if(!valid)throw new IllegalArgumentException("FEED_FEATURE_"+rule);}
}
