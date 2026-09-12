package com.crabit.backend.simulation;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only admission. Verified bytes are NOT proof of replay, graph integrity, or application. */
public final class SimulationBundleReader {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Map<String,String> REQUIRED = Map.of(
        "CONFIG", "config.json", "STUDENTS", "students.json", "PERSONAS", "personas.json",
        "EVENTS", "events.ndjson", "ID_MAP", "id-map.json", "STATE", "state/export.json",
        "VALIDATION", "validation.json", "NORMALIZED", "normalized.json", "SCHEMA", "demo-simulation-v1.schema.json", "RAW_INDEX", "raw/index.json");
    private static final Instant START = Instant.parse("2026-05-31T15:00:00Z");
    private static final Instant END = Instant.parse("2026-09-10T15:00:00Z");
    private static final Instant JULY = Instant.parse("2026-06-30T15:00:00Z");
    public static final int MAX_FILES = 262_144;
    public static final int MAX_MANIFEST_BYTES = 128 * 1024 * 1024;
    public static final int MAX_ARTIFACT_BYTES = 128 * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_SCHEMA_BYTES = 1024 * 1024;

    public record AdmittedBundle(String datasetId, String manifestDigest, Map<String, byte[]> artifacts) {
        public AdmittedBundle {
            Map<String,byte[]> copy = new TreeMap<>();
            artifacts.forEach((k,v) -> copy.put(k,v.clone())); artifacts = Collections.unmodifiableMap(copy);
        }
        @Override public Map<String,byte[]> artifacts() {
            Map<String,byte[]> copy = new TreeMap<>();
            artifacts.forEach((k,v) -> copy.put(k,v.clone())); return Collections.unmodifiableMap(copy);
        }
    }
    public static final class Rejection extends IllegalArgumentException {
        private final String code;
        Rejection(String code, String rule) { super(code + ": " + rule); this.code = code; }
        public String code() { return code; }
    }
    public AdmittedBundle read(Path directory, Path trustedSchema, String expectedManifestDigest) throws IOException {
        require(expectedManifestDigest != null && expectedManifestDigest.matches("sha256:[0-9a-f]{64}"), "SCHEMA_INVALID", "expected manifest digest");
        require(!Files.isSymbolicLink(directory),"SCHEMA_INVALID","no symlink bundle root");
        Path root = directory.toRealPath();
        byte[] raw = readFile(root, "manifest.json", MAX_MANIFEST_BYTES);
        require(digest(raw).equals(expectedManifestDigest), "CHECKSUM_MISMATCH", "manifest bytes");
        JsonNode manifest = parse(raw);
        Path schemaPath=trustedSchema.toAbsolutePath();
        byte[] schemaBytes = readFile(schemaPath.getParent().toRealPath(),schemaPath.getFileName().toString(),MAX_SCHEMA_BYTES);
        JsonNode schema = parse(schemaBytes);
        validate(schema, manifest, "manifest");
        require(manifest.get("files").size()<=MAX_FILES,"SCHEMA_INVALID","manifest file count limit");
        // Reject an oversized declaration before materializing any artifact bytes.
        long total=0;
        for(JsonNode file:manifest.get("files")) {
            total=Math.addExact(total,file.get("byteLength").longValue());
            require(total<=MAX_TOTAL_BYTES,"SCHEMA_INVALID","total bundle byte limit");
        }
        require(digest(schemaBytes).equals(manifest.get("schemaDigest").asString()), "CHECKSUM_MISMATCH", "schema bytes");
        require(datasetIdentity(manifest).equals(manifest.get("datasetId").asString()), "CHECKSUM_MISMATCH", "dataset identity");
        Set<String> names = new HashSet<>(), foldedNames = new HashSet<>(), roles = new HashSet<>();
        Map<String,byte[]> artifacts = new TreeMap<>(); List<JsonNode> events = new ArrayList<>(); String previous = "";
        for (JsonNode file : manifest.get("files")) {
            String name = file.get("path").asString(), role = file.get("role").asString();
            require(!name.equals("manifest.json") && names.add(name) && foldedNames.add(name.toLowerCase(Locale.ROOT)), "SCHEMA_INVALID", "unique artifact path including case");
            require(previous.compareTo(name) < 0,"SCHEMA_INVALID","files sorted by path"); previous=name;
            if (role.equals("RAW")) require(name.startsWith("raw/"),"SCHEMA_INVALID","raw artifact directory");
            else require(name.equals(REQUIRED.get(role)) && roles.add(role),"SCHEMA_INVALID","required artifact path and unique role");
            long size = file.get("byteLength").longValue();
            byte[] bytes = readFile(root, name, MAX_ARTIFACT_BYTES);
            require(bytes.length == size && digest(bytes).equals(file.get("sha256").asString()), "CHECKSUM_MISMATCH", "artifact bytes");
            long count;
            if (role.equals("RAW")) count=1; // One opaque immutable transport/document record.
            else if (role.equals("EVENTS")) {
                String content=decode(bytes); count=0;
                if (!content.isEmpty()) {
                    String[] lines=content.split("\n", -1);
                    for (int i=0;i<lines.length;i++) {
                        if(i==lines.length-1 && lines[i].isEmpty()) continue;
                        require(!lines[i].isBlank(),"SCHEMA_INVALID","no blank NDJSON record");
                        JsonNode event = parse(lines[i].getBytes(StandardCharsets.UTF_8));
                        validate(schema.get("$defs").get("event"),event,"event");
                        require(events.size()<1_000_000,"SCHEMA_INVALID","event count limit");
                        events.add(event); count++;
                    }
                }
            } else {
                JsonNode payload=parse(bytes); count=payload.isArray()?payload.size():1;
            }
            require(count==file.get("recordCount").longValue(),"CHECKSUM_MISMATCH","artifact record count");
            artifacts.put(name,bytes);
        }
        require(roles.equals(REQUIRED.keySet()), "SCHEMA_INVALID", "complete artifact roles");
        try (var entries = Files.walk(root)) {
            for (var iterator=entries.iterator();iterator.hasNext();) {
                Path entry=iterator.next();
                if(entry.equals(root)) continue;
                String name=root.relativize(entry).toString().replace('\\','/');
                require(!Files.isSymbolicLink(entry),"SCHEMA_INVALID","no symlink in bundle");
                if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))
                    require(names.stream().anyMatch(n->n.startsWith(name+"/")),"SCHEMA_INVALID","unlisted bundle directory");
                else require(name.equals("manifest.json") || names.contains(name),"SCHEMA_INVALID","unlisted bundle entry");
            }
        }
        require(Arrays.equals(schemaBytes,artifacts.get(REQUIRED.get("SCHEMA"))),"CHECKSUM_MISMATCH","bundled schema equals trusted bytes");
        JsonNode config=parse(artifacts.get("config.json"));
        validate(schema.get("$defs").get("config"),config,"config");
        require(digest(canonical(config).getBytes(StandardCharsets.UTF_8)).equals(manifest.get("configDigest").asString()),"CHECKSUM_MISMATCH","canonical config digest");
        require(config.get("runtimeVersions").equals(manifest.get("runtimeVersions")),"INVARIANT_VIOLATION","decision runtime versions bound in config");
        JsonNode students=parse(artifacts.get("students.json")), personas=parse(artifacts.get("personas.json"));
        validate(schema.get("$defs").get("students"),students,"students");
        validate(schema.get("$defs").get("personas"),personas,"personas");
        population(students,personas);
        new SimulationEventTimeline().verify(events, students, artifacts.keySet());
        JsonNode idMap=parse(artifacts.get("id-map.json")), rawIndex=parse(artifacts.get("raw/index.json")),
            validation=parse(artifacts.get("validation.json"));
        validate(schema.get("$defs").get("idMap"),idMap,"idMap");
        validate(schema.get("$defs").get("rawIndex"),rawIndex,"rawIndex");
        validate(schema.get("$defs").get("validation"),validation,"validation");
        new SimulationEvidenceIndex().verify(manifest,students,events,idMap,rawIndex,validation,artifacts);
        JsonNode cashState=parse(artifacts.get("state/export.json"));
        validate(schema.get("$defs").get("cashState"),cashState,"cashState");
        new SimulationBundleCashVerifier().verify(manifest,students,events,cashState);
        require(digest(canonical(parse(artifacts.get("normalized.json"))).getBytes(StandardCharsets.UTF_8))
            .equals(manifest.get("logicalDigest").asString()),"CHECKSUM_MISMATCH","normalized projection digest");
        return new AdmittedBundle(manifest.get("datasetId").asString(),expectedManifestDigest,artifacts);
    }
    static String datasetIdentity(JsonNode manifest) {
        var identity=JSON.createObjectNode();
        for(String key:List.of("schemaVersion","configDigest","codeShas","normalizationVersion")) identity.set(key,manifest.get(key));
        return digest(canonical(identity).getBytes(StandardCharsets.UTF_8));
    }
    private static void population(JsonNode population,JsonNode personas) {
        Set<String> accounts = new HashSet<>(), academies = new HashSet<>(); Map<String,JsonNode> students = new HashMap<>();
        int[] counts = new int[7], initial = new int[7]; int owners = 0;
        for (JsonNode student : population) {
            String id = student.get("logicalStudentId").asString(); int grade = student.get("grade").intValue();
            require(students.put(id,student) == null && accounts.add(student.get("logicalAccountId").asString()), "INVARIANT_VIOLATION", "unique student/account identity");
            academies.add(student.get("logicalAcademyId").asString());
            Set<String> inputNames=new HashSet<>();
            for(JsonNode input:student.get("archetypeInputs")) require(inputNames.add(input.get("name").asString()),"INVARIANT_VIOLATION","unique archetype input");
            Instant joined = Instant.parse(student.get("joinedAt").asString());
            require(!joined.isBefore(START) && joined.isBefore(END), "INVARIANT_VIOLATION", "enrollment cutoff");
            counts[grade]++; if (joined.equals(START)) initial[grade]++;
            else require(!joined.isBefore(JULY), "INVARIANT_VIOLATION", "late enrollment begins July");
            if (student.get("isOwner").asBoolean()) owners++;
        }
        require(owners == 1 && academies.size()==1,"INVARIANT_VIOLATION","exactly one Owner and academy");
        for (int grade=3;grade<=6;grade++) require(counts[grade]==25 && initial[grade]==20,"INVARIANT_VIOLATION","grade enrollment population");
        Set<String> aliases=new HashSet<>(), representatives=new HashSet<>();
        for(JsonNode persona:personas) {
            int grade=persona.get("grade").intValue(); String alias=persona.get("persona").asString();
            JsonNode rep=students.get(persona.get("logicalStudentId").asString());
            require(alias.equals("grade-"+grade) && aliases.add(alias) && representatives.add(persona.get("logicalStudentId").asString()),"INVARIANT_VIOLATION","unique grade representative");
            require(rep != null && rep.get("grade").intValue()==grade && !rep.get("isOwner").asBoolean()
                && rep.get("logicalAccountId").equals(persona.get("logicalAccountId")),"INVARIANT_VIOLATION","representative mapping");
        }
    }
    private static byte[] readFile(Path root, String name, long max) throws IOException {
        Path relative=Path.of(name), path=root.resolve(relative).normalize();
        require(!relative.isAbsolute() && path.startsWith(root) && !path.equals(root),"SCHEMA_INVALID","relative artifact path");
        Path current=root;
        for(Path part:relative) {
            require(!part.toString().equals(".") && !part.toString().equals(".."),"SCHEMA_INVALID","no dot path segments");
            current=current.resolve(part);
            require(!Files.isSymbolicLink(current),"SCHEMA_INVALID","regular artifact file without symlink ancestors");
        }
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS), "SCHEMA_INVALID", "regular artifact file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int)max+1);
            require(bytes.length <= max,"SCHEMA_INVALID","file byte limit"); return bytes;
        }
    }
    private static String decode(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch(java.nio.charset.CharacterCodingException e) { throw new Rejection("SCHEMA_INVALID","strict UTF-8"); }
    }
    static JsonNode parse(byte[] bytes) {
        try {
            // Jackson's UTF-8 parser rejects malformed encodings; reject BOM as noncanonical transport.
            require(bytes.length < 3 || bytes[0] != (byte)0xef || bytes[1] != (byte)0xbb || bytes[2] != (byte)0xbf,"SCHEMA_INVALID","UTF-8 without BOM");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonNode value = JSON.readTree(text);
            require(value != null,"SCHEMA_INVALID","JSON document"); validUnicode(value); return value;
        } catch (Rejection e) { throw e; }
        catch (RuntimeException | java.nio.charset.CharacterCodingException e) { throw new Rejection("SCHEMA_INVALID","strict JSON parsing"); }
    }
    private static void validUnicode(JsonNode node) {
        if (node.isString()) validString(node.asString());
        if (node.isObject()) for (String key : node.propertyNames()) validString(key);
        if ((node.isObject() || node.isArray())) for (JsonNode child : node) validUnicode(child);
    }
    private static void validString(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                require(i+1<text.length() && Character.isLowSurrogate(text.charAt(i+1)),"SCHEMA_INVALID","Unicode scalar value"); i++;
            } else require(!Character.isLowSurrogate(c),"SCHEMA_INVALID","Unicode scalar value");
        }
    }
    /** Implements exactly the keywords in the trusted manifest schema; unknown keywords fail closed. */
    static void validate(JsonNode schema, JsonNode value, String rule) {
        Set<String> supported = Set.of("$schema","$id","$defs","title","description","type","additionalProperties","required","properties","items","minItems","maxItems","minLength","maxLength","minimum","maximum","pattern","format","const","enum","oneOf");
        for (String key : schema.propertyNames()) require(supported.contains(key),"SCHEMA_INVALID","unsupported schema keyword");
        if (schema.has("oneOf")) {
            int matches=0;
            for(JsonNode variant:schema.get("oneOf")) {
                try { validate(variant,value,rule); matches++; } catch(Rejection ignored) { }
            }
            require(matches==1,"SCHEMA_INVALID",rule+" variant");
        }
        if (schema.has("const")) require(schema.get("const").equals(value),"SCHEMA_INVALID",rule);
        if (schema.has("enum")) { boolean found=false; for (JsonNode option:schema.get("enum")) found |= option.equals(value); require(found,"SCHEMA_INVALID",rule); }
        if (!schema.has("type")) return;
        switch (schema.get("type").asString()) {
            case "object" -> {
                require(value.isObject(),"SCHEMA_INVALID",rule);
                for (JsonNode key:schema.get("required")) require(value.has(key.asString()),"SCHEMA_INVALID",rule);
                for (String key:value.propertyNames()) {
                    require(schema.get("properties").has(key),"SCHEMA_INVALID",rule);
                    validate(schema.get("properties").get(key),value.get(key),rule+"."+key);
                }
            }
            case "array" -> { require(value.isArray(),"SCHEMA_INVALID",rule); range(value.size(),schema,"minItems","maxItems",rule); for (JsonNode item:value) validate(schema.get("items"),item,rule); }
            case "string" -> {
                require(value.isString(),"SCHEMA_INVALID",rule); String s=value.asString();
                range(s.codePointCount(0,s.length()),schema,"minLength","maxLength",rule);
                if(schema.has("pattern")) require(java.util.regex.Pattern.compile(schema.get("pattern").asString()).matcher(s).find(),"SCHEMA_INVALID",rule);
                if(schema.has("format")) {
                    String format=schema.get("format").asString();
                    try {
                        if(format.equals("date")) {
                            require(s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"SCHEMA_INVALID",rule);
                            java.time.LocalDate.parse(s);
                        } else {
                            require(format.equals("date-time") && s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z"),"SCHEMA_INVALID",rule);
                            java.time.LocalDateTime.parse(s.substring(0,s.length()-1)); Instant.parse(s);
                        }
                    } catch(RuntimeException e) { throw new Rejection("SCHEMA_INVALID",rule); }
                }
            }
            case "integer" -> { require(value.isIntegralNumber() && value.canConvertToLong(),"SCHEMA_INVALID",rule); range(value.longValue(),schema,"minimum","maximum",rule); }
            case "null" -> require(value.isNull(),"SCHEMA_INVALID",rule);
            case "boolean" -> require(value.isBoolean(),"SCHEMA_INVALID",rule);
            default -> throw new Rejection("SCHEMA_INVALID","unsupported schema type");
        }
    }
    private static void range(long n,JsonNode s,String min,String max,String rule) {
        require((!s.has(min)||n>=s.get(min).longValue())&&(!s.has(max)||n<=s.get(max).longValue()),"SCHEMA_INVALID",rule);
    }
    static String canonical(JsonNode node) {
        if(node.isNumber()) require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue()>=-9007199254740991L && node.longValue()<=9007199254740991L,"SCHEMA_INVALID","canonical safe integer");
        if(node.isObject()) {
            List<String> keys=new ArrayList<>(node.propertyNames()); Collections.sort(keys);
            List<String> parts=new ArrayList<>(); for(String key:keys) parts.add(JSON.writeValueAsString(key)+":"+canonical(node.get(key)));
            return "{"+String.join(",",parts)+"}";
        }
        if(node.isArray()) { List<String> parts=new ArrayList<>(); for(JsonNode item:node)parts.add(canonical(item)); return "["+String.join(",",parts)+"]"; }
        return JSON.writeValueAsString(node);
    }
    public static String digest(byte[] bytes) {
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void require(boolean ok,String code,String rule) { if(!ok) throw new Rejection(code,rule); }
}
