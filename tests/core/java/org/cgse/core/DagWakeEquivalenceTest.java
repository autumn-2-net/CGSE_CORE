// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/** Independent full-scan/ordered-set model of existing two-lane wake semantics. */
public final class DagWakeEquivalenceTest {
    private static long checks, polls;

    public static void main(String[] args) {
        for (int seed = 0; seed < 64; seed++) compare(seed);
        System.out.println("DagWakeEquivalenceTest: events=384000; polls=" + polls + "; assertions=" + checks);
    }

    private static void compare(int seed) {
        var random = new Random(0x53da9L + seed);
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); var counts = new LinkedHashMap<String, BigInteger>();
        var accepted = new LinkedHashMap<String, BigInteger>(); var steps = new ArrayList<PlanStep>();
        int size = 4 + random.nextInt(29);
        for (int i = 0; i < size; i++) {
            String id = "r" + i;
            var keys = new LinkedHashSet<String>(); keys.add("shared");
            for (int j = 0; j < 4; j++) keys.add("k" + random.nextInt(8));
            var slots = new ArrayList<GraphRecipe.Slot<String>>();
            for (String key : keys) slots.add(new GraphRecipe.Slot<>(key, 1));
            recipes.put(id, new GraphRecipe<>(id, id, slots, Map.of("product", 1L)));
            long runs = seed % 8 == 0 && i == size - 1 ? Long.MAX_VALUE : 1 + random.nextInt(5);
            int repetitions = 1 + random.nextInt(3);
            BigInteger count = BigInteger.valueOf(runs).multiply(BigInteger.valueOf(repetitions)); counts.put(id, count);
            steps.add(new PlanStep.Repeat(new PlanStep.Batch(id, runs), repetitions));
            if (i % 7 == 0) accepted.put(id, count);
            else if (i % 3 == 0) accepted.put(id, BigInteger.ONE.min(count));
        }
        var plan = new GraphPlan<>("product", 1, false, new PlanStep.Sequence(steps), recipes, Map.of(), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var actual = DagScheduler.create(plan, accepted); check(actual != null, "Independent source graph declined");
        var expected = new Reference(recipes, counts, accepted);
        long tick = 0;
        for (int event = 0; event < 6000; event++) {
            tick += random.nextInt(3);
            String key = random.nextInt(5) == 0 ? "shared" : "k" + random.nextInt(10);
            if (random.nextBoolean()) { actual.resourceChanged(key); expected.changed(key); }
            if (random.nextInt(3) == 0) continue;
            var got = poll(actual, expected, tick);
            if (got == null) continue;
            if (random.nextBoolean()) {
                // Real receipts can arrive inside the provider callback, before
                // acceptance or refusal resolves the prepared node.
                String own = recipes.get(got.recipe()).inputs().keySet().iterator().next();
                actual.resourceChanged(own); expected.changed(own);
                actual.resourceChanged(key); expected.changed(key);
            }
            switch (random.nextInt(3)) {
                case 0 -> { /* Unfunded node sleeps, without a provider timer. */ }
                case 1 -> { long at = tick + 1 + random.nextInt(17); actual.retry(at); expected.retry(at); }
                default -> { long n = Math.min(got.runs(), 1 + random.nextInt(4)); actual.accepted(n); expected.accepted(n); }
            }
            check(actual.finished() == expected.finished(), "Accepted-count accounting changed");
        }
        // Settle all pending work, including exact counts larger than long.
        for (int i = 0; !actual.finished() && i < size * 8; i++) {
            actual.resourceChanged("shared"); expected.changed("shared");
            var got = poll(actual, expected, tick + 100);
            check(got != null && got.runs() > 0, "Lost pending node on final receipt");
            actual.accepted(got.runs()); expected.accepted(got.runs());
        }
        check(actual.finished() && expected.finished(), "Wide counts failed to settle");
        long visits = actual.wakeupVisits();
        for (String key : List.of("shared", "k0", "k1", "absent")) for (int i = 0; i < 100; i++) {
            actual.resourceChanged(key); expected.changed(key);
        }
        check(actual.wakeupVisits() == visits, "Completed subscribers were scanned");
        check(poll(actual, expected, tick + 1000) == null, "Stale wake or retry revived finished work");
    }

    private static PlanStep.Batch poll(DagScheduler<String> actual, Reference expected, long tick) {
        polls++; var a = actual.poll(tick); var b = expected.poll(tick);
        check(java.util.Objects.equals(a, b), "Different next dispatch at tick " + tick + ": " + a + " / " + b); return a;
    }

    private static final class Reference {
        private record Retry(String id, long tick, long generation) {}
        private final Map<String, GraphRecipe<String>> recipes;
        private final LinkedHashMap<String, BigInteger> remaining = new LinkedHashMap<>();
        private final Map<String, Long> generations = new LinkedHashMap<>();
        private final LinkedHashSet<String> ordinary = new LinkedHashSet<>(), awake = new LinkedHashSet<>();
        private final PriorityQueue<Retry> timers = new PriorityQueue<>(Comparator.comparingLong(Retry::tick));
        private String active;
        private boolean preferWake = true;

        Reference(Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> counts, Map<String, BigInteger> accepted) {
            this.recipes = recipes;
            for (var value : counts.entrySet()) {
                remaining.put(value.getKey(), value.getValue().subtract(accepted.getOrDefault(value.getKey(), BigInteger.ZERO)));
                generations.put(value.getKey(), 0L); enqueue(value.getKey());
            }
        }
        private boolean live(String id) { return remaining.get(id).signum() > 0; }
        private void enqueue(String id) {
            if (!live(id) || ordinary.contains(id) || awake.contains(id)) return;
            generations.merge(id, 1L, Long::sum); ordinary.add(id);
        }
        void changed(String key) {
            for (var value : recipes.entrySet()) {
                String id = value.getKey();
                if (!value.getValue().inputs().containsKey(key) || !live(id) || awake.contains(id)) continue;
                ordinary.remove(id); generations.merge(id, 1L, Long::sum); awake.add(id);
            }
        }
        PlanStep.Batch poll(long tick) {
            while (!timers.isEmpty() && timers.peek().tick() <= tick) {
                var retry = timers.remove(); if (retry.generation() == generations.get(retry.id())) enqueue(retry.id());
            }
            if (ordinary.isEmpty() && awake.isEmpty()) { active = null; return null; }
            boolean wake = !awake.isEmpty() && (preferWake || ordinary.isEmpty());
            var row = wake ? awake : ordinary; active = row.iterator().next(); row.remove(active); preferWake = !wake;
            return new PlanStep.Batch(active, remaining.get(active).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
        }
        void accepted(long count) {
            remaining.put(active, remaining.get(active).subtract(BigInteger.valueOf(count)));
            if (!live(active)) { ordinary.remove(active); awake.remove(active); generations.merge(active, 1L, Long::sum); }
            else enqueue(active);
        }
        void retry(long tick) {
            ordinary.remove(active); awake.remove(active);
            long generation = generations.merge(active, 1L, Long::sum); timers.add(new Retry(active, tick, generation));
        }
        boolean finished() { return remaining.values().stream().allMatch(n -> n.signum() == 0); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
