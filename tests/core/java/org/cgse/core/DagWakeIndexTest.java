// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Random;
import java.util.TreeSet;

/** Independent ordered-set oracle for sparse resource-local subscriptions. */
public final class DagWakeIndexTest {
    private static long checks;

    public static void main(String[] args) {
        // Every fifth node is already complete. The extra sizes leave exactly
        // 63/64/65 and 4095/4096/4097 live subscribers after removing those nodes.
        for (int size : new int[]{0, 1, 63, 64, 65, 79, 80, 82, 127, 128, 129,
                4095, 4096, 4097, 5119, 5120, 5122, 65537}) run(size);
        System.out.println("DagWakeIndexTest: " + checks + " assertions passed");
    }

    private static void run(int size) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var remaining = new BigInteger[size];
        var expected = new LinkedHashMap<String, NavigableSet<Integer>>();
        int edges = 0;
        for (int i = 0; i < size; i++) {
            var recipe = new GraphRecipe<>("r" + i, "binding" + i, List.of(new GraphRecipe.Slot<>("shared", 1),
                    new GraphRecipe.Slot<>("u" + i, 1), new GraphRecipe.Slot<>("g" + i % 37, 1)), Map.of("product", 1L));
            recipes.add(recipe); remaining[i] = i % 5 == 0 ? BigInteger.ZERO : BigInteger.ONE;
            if (remaining[i].signum() > 0) for (String key : recipe.inputs().keySet()) {
                expected.computeIfAbsent(key, ignored -> new TreeSet<>()).add(i); edges++;
            }
        }
        var index = new DagWakeIndex<>(recipes, remaining);
        check(index.incidences() == edges, "Retained already completed subscriptions");
        check(index.bitmapWords() <= edges, "Sparse keys used global recipe-sized bitmaps");
        var random = new Random(0x31da9L + size);
        for (int event = 0; event < 4000 && size > 0; event++) {
            int i = random.nextInt(size); boolean enable = random.nextBoolean();
            if (enable) index.enable(i); else index.disable(i);
            if (remaining[i].signum() > 0) for (String key : recipes.get(i).inputs().keySet()) {
                if (enable) expected.get(key).add(i); else expected.get(key).remove(i);
            }
            compare(index, expected, "shared"); compare(index, expected, "g" + random.nextInt(37));
            compare(index, expected, "u" + random.nextInt(size)); compare(index, expected, "absent");
        }
        for (int i = 0; i < size; i++) index.disable(i);
        for (String key : expected.keySet()) check(index.first(key) == -1, "Cleared subscription survived");
        long before = index.wordVisits();
        for (int i = 0; i < 1000; i++) check(index.first("shared") == -1, "Empty resource regained a subscriber");
        check(index.wordVisits() - before <= 1000, "Empty callbacks scanned bitmap words");
        for (var row : expected.values()) row.clear();
        // Restore in reverse order: membership history must not change the
        // original consumer order, including at every bitmap level boundary.
        for (int i = size - 1; i >= 0; i--) if (remaining[i].signum() > 0) {
            index.enable(i); index.enable(i);
            for (String key : recipes.get(i).inputs().keySet()) expected.get(key).add(i);
            compare(index, expected, "shared");
        }
        int previous = -1, drained = 0;
        while (index.first("shared") >= 0) {
            int i = index.first("shared"); check(i > previous, "Consumer order changed"); previous = i;
            index.disable(i); index.disable(i); drained++;
        }
        check(drained == edges / 3, "An enabled subscriber was skipped");
        for (String key : expected.keySet()) check(index.first(key) == -1, "Removal left a second-key subscription");
    }

    private static void compare(DagWakeIndex<String> index, Map<String, NavigableSet<Integer>> expected, String key) {
        var row = expected.get(key); int wanted = row == null || row.isEmpty() ? -1 : row.first();
        check(index.first(key) == wanted, "Ordered-set mismatch for " + key);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
