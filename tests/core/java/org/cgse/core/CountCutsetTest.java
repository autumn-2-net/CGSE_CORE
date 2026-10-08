// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Compare separator scores with independent delete-and-BFS connectivity. */
public final class CountCutsetTest {
    private static long cases;

    public static void main(String[] args) {
        Random random = new Random(183421);
        for (int n = 0; n <= 6; n++) {
            int edges = n * (n - 1) / 2;
            for (int mask = 0; mask < 1 << edges; mask++) {
                BitSet[] graph = graph(n);
                int bit = 0;
                for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++, bit++)
                    if ((mask & 1 << bit) != 0) edge(graph, a, b);
                BitSet live = new BitSet();
                live.set(0, n);
                compare(graph, live);
                for (int id = 0; id < n; id++) if (random.nextBoolean()) live.clear(id);
                compare(graph, live);
            }
        }
        for (int sample = 0; sample < 400; sample++) {
            int n = 7 + random.nextInt(80);
            BitSet[] graph = graph(n);
            int density = 1 + random.nextInt(12);
            for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++)
                if (random.nextInt(16) < density) edge(graph, a, b);
            BitSet live = new BitSet();
            for (int id = 0; id < n; id++) if (random.nextInt(5) != 0) live.set(id);
            compare(graph, live);
        }
        BitSet[] chain = graph(256);
        for (int id = 1; id < chain.length; id++) edge(chain, id - 1, id);
        BitSet live = new BitSet();
        live.set(0, chain.length);
        compare(chain, live);
        System.out.println("Cutset scores: " + cases + " exhaustive/random live graphs verified by deletion and BFS");
    }

    private static void compare(BitSet[] graph, BitSet live) {
        var originalLive = (BitSet) live.clone();
        var originalGraph = Arrays.stream(graph).map(x -> (BitSet) x.clone()).toArray(BitSet[]::new);
        int[] visits = {0};
        int[] actual = CountCutset.largestParts(graph, live, () -> visits[0]++);
        long edges = Arrays.stream(graph).mapToLong(BitSet::cardinality).sum();
        check(visits[0] <= 4L * graph.length + edges, "Separator scoring stopped being linear");
        for (int removed = 0; removed < graph.length; removed++) {
            if (!live.get(removed)) {
                check(actual[removed] == -1, "Dead vertex scored as a candidate");
                continue;
            }
            boolean[] seen = new boolean[graph.length];
            seen[removed] = true;
            int largest = 0;
            for (int start = 0; start < graph.length; start++) {
                if (!live.get(start) || seen[start]) continue;
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(start);
                seen[start] = true;
                int size = 0;
                while (!queue.isEmpty()) {
                    int id = queue.removeFirst();
                    size++;
                    for (int next = 0; next < graph.length; next++) if (!seen[next] && live.get(next) && graph[id].get(next)) {
                        seen[next] = true;
                        queue.add(next);
                    }
                }
                largest = Math.max(largest, size);
            }
            check(actual[removed] == largest, "Incorrect separator component size");
        }
        check(live.equals(originalLive) && Arrays.equals(graph, originalGraph), "Scoring mutated caller graph");
        cases++;
    }

    private static BitSet[] graph(int n) {
        BitSet[] result = new BitSet[n];
        Arrays.setAll(result, ignored -> new BitSet());
        return result;
    }
    private static void edge(BitSet[] graph, int a, int b) { graph[a].set(b); graph[b].set(a); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
