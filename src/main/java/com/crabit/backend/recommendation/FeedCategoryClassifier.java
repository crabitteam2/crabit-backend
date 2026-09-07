package com.crabit.backend.recommendation;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local evaluation of the byte-pinned Python TF-IDF artifact. No title leaves the process. */
@Component
public final class FeedCategoryClassifier {
    private final String version;
    private final List<String> categories = new ArrayList<>();
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final double[] idf;
    private final double[][] weights;

    public FeedCategoryClassifier(ObjectMapper json) {
        try {
            byte[] bytes = FeedClassifierV1.bytes();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!FeedClassifierV1.ARTIFACT_SHA256.equals(digest))
                throw new IllegalStateException("Embedded feed classifier digest mismatch");
            version = "wish-category-v1@sha256:" + digest;
            JsonNode root = json.readTree(bytes);
            if (!"tfidf-char-wb-2-3-v1".equals(root.path("algorithm").stringValue())
                    || root.path("threshold").doubleValue() != 0.05)
                throw new IllegalStateException("Unsupported classifier artifact");
            root.get("categories").forEach(c -> categories.add(c.stringValue()));
            int i = 0;
            for (JsonNode term : root.get("vocabulary")) vocabulary.put(term.stringValue(), i++);
            idf = new double[i];
            for (i = 0; i < idf.length; i++) idf[i] = root.get("idf").get(i).doubleValue();
            weights = new double[categories.size()][idf.length];
            for (i = 0; i < weights.length; i++)
                for (int j = 0; j < idf.length; j++) weights[i][j] = root.get("weights").get(i).get(j).doubleValue();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot load feed classifier", failure);
        }
    }

    public String version() { return version; }

    public String classify(String title) {
        double[] counts = new double[idf.length];
        // Python str.split() whitespace includes U+001C..001F, NEL and nonbreaking spaces.
        StringBuilder word = new StringBuilder();
        title.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85) {
                if (!word.isEmpty()) { addWord(word.toString(), counts); word.setLength(0); }
            } else word.appendCodePoint(cp);
        });
        if (!word.isEmpty()) addWord(word.toString(), counts);
        double norm = 0;
        for (int i = 0; i < counts.length; i++) { counts[i] *= idf[i]; norm += counts[i] * counts[i]; }
        if (norm == 0) return "기타";
        norm = Math.sqrt(norm);
        double best = -1; int winner = 0;
        for (int category = 0; category < weights.length; category++) {
            double dot = 0, weightNorm = 0;
            for (int i = 0; i < counts.length; i++) {
                dot += counts[i] / norm * weights[category][i];
                weightNorm += weights[category][i] * weights[category][i];
            }
            double score = dot / Math.sqrt(weightNorm);
            if (score > best) { best = score; winner = category; }
        }
        return best < 0.05 ? "기타" : categories.get(winner);
    }

    private void addWord(String word, double[] counts) {
        int[] padded = (" " + word + " ").codePoints().toArray();
        for (int width = 2; width <= 3; width++) {
            for (int offset = 0; offset + width <= padded.length; offset++) {
                Integer index = vocabulary.get(new String(padded, offset, width));
                if (index != null) counts[index]++;
            }
        }
    }
}
