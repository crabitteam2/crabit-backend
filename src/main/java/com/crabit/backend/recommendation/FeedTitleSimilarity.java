package com.crabit.backend.recommendation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Python difflib.SequenceMatcher(None, a, b).ratio(), including autojunk at 200 code points. */
public final class FeedTitleSimilarity {
    private FeedTitleSimilarity() {}

    public static double ratio(String left, String right) {
        int[] a = left.codePoints().toArray(), b = right.codePoints().toArray();
        if (a.length + b.length == 0) return 1;
        Map<Integer, List<Integer>> positions = new HashMap<>();
        for (int j = 0; j < b.length; j++)
            positions.computeIfAbsent(b[j], ignored -> new ArrayList<>()).add(j);
        if (b.length >= 200) positions.values().removeIf(p -> p.size() > b.length / 100 + 1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[] {0, a.length, 0, b.length});
        int matches = 0;
        while (!queue.isEmpty()) {
            int[] range = queue.pop();
            int alo = range[0], ahi = range[1], blo = range[2], bhi = range[3];
            int bestA = alo, bestB = blo, size = 0;
            Map<Integer, Integer> lengths = Map.of();
            for (int i = alo; i < ahi; i++) {
                Map<Integer, Integer> next = new HashMap<>();
                for (int j : positions.getOrDefault(a[i], List.of())) {
                    if (j < blo) continue;
                    if (j >= bhi) break;
                    int length = lengths.getOrDefault(j - 1, 0) + 1;
                    next.put(j, length);
                    if (length > size) { bestA = i - length + 1; bestB = j - length + 1; size = length; }
                }
                lengths = next;
            }
            // Popular elements do not seed matches, but extend them; there is no user junk predicate.
            while (bestA > alo && bestB > blo && a[bestA - 1] == b[bestB - 1]) { bestA--; bestB--; size++; }
            while (bestA + size < ahi && bestB + size < bhi && a[bestA + size] == b[bestB + size]) size++;
            if (size == 0) continue;
            matches += size;
            if (alo < bestA && blo < bestB) queue.push(new int[] {alo, bestA, blo, bestB});
            if (bestA + size < ahi && bestB + size < bhi)
                queue.push(new int[] {bestA + size, ahi, bestB + size, bhi});
        }
        return 2.0 * matches / (a.length + b.length);
    }
}
