// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Multi-stage productive loops must grow even when a rotation cannot expose the funded order. */
public final class LoopPermutationGrowthTest {
    private static final long SEED = 4096, CAP = 1_048_576;
    private static int cases;

    public static void main(String[] args) {
        for (var order : List.of(List.of("charge", "activate", "grind"), List.of("charge", "grind", "activate"),
                List.of("activate", "charge", "grind"), List.of("activate", "grind", "charge"),
                List.of("grind", "charge", "activate"), List.of("grind", "activate", "charge"))) {
            run(order, 1_000_000_000L, 20, 4, true);
        }
        run(List.of("charge", "activate", "grind"), 1_000_000_000_000L, 1, 64, false);
        for (long seed : new long[] { 1, 8, 64 })
            run(List.of("charge", "activate", "grind"), 1_000_000_000L, 20, 4, false, seed);
        intervalSafety();
        System.out.println("LoopPermutationGrowthTest: cases=" + cases);
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static void run(List<String> order, long amount, int delay, int providers, boolean temporaryCap) {
        run(order, amount, delay, providers, temporaryCap, SEED);
    }

    private static void run(List<String> order, long amount, int delay, int providers, boolean temporaryCap, long seed) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("charge", recipe("charge", Map.of("I", 1L), Map.of("C", 1L)));
        recipes.put("activate", recipe("activate", Map.of("P", 1L, "R", 1L), Map.of("I", 1L)));
        recipes.put("grind", recipe("grind", Map.of("C", 1L), Map.of("P", 9L)));
        long runs = amount / 8;
        PlanStep body = new PlanStep.Sequence(order.stream().map(id -> (PlanStep) new PlanStep.Batch(id, seed)).toList());
        PlanStep tail = new PlanStep.Sequence(order.stream().map(id -> (PlanStep) new PlanStep.Batch(id, runs % seed)).toList());
        PlanStep steps = new PlanStep.Sequence(List.of(new PlanStep.Repeat(body, runs / seed), tail));
        var initial = Map.of("P", seed, "I", seed, "C", seed, "R", runs);
        var seeds = Map.of("P", seed, "I", seed, "C", seed);
        var plan = new GraphPlan<>("P", amount, true, steps, recipes, initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        class Machines implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, initial, Map.of());
            final Map<String, Long> stock = new HashMap<>(initial), refunds = new HashMap<>(), peaks = new HashMap<>();
            final Map<String, BigInteger> accepted = new HashMap<>();
            record Flight(String recipe, long due, Map<String, Long> output) {}
            final List<Flight> flights = new ArrayList<>();
            long tick, delivered;

            public long capacity(GraphRecipe<String> recipe, long requested) {
                if (flights.stream().filter(f -> f.recipe.equals(recipe.id())).count() >= providers) return 0;
                return Math.min(requested, temporaryCap && tick < 100 ? 8192 : CAP);
            }

            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long count, Map<String, Long> input) {
                if (!input.equals(recipe.dispatchInputs(count))) throw new AssertionError("Wrong escrow");
                input.forEach((key, n) -> {
                    long left = stock.getOrDefault(key, 0L) - n;
                    if (left < 0) throw new AssertionError("Spent an unreceived output");
                    stock.put(key, left);
                });
                Map<String, Long> output = new HashMap<>();
                recipe.executionOutputs().forEach((key, n) -> output.put(key, Math.multiplyExact(count, n)));
                flights.add(new Flight(recipe.id(), tick + delay, output));
                accepted.merge(recipe.id(), BigInteger.valueOf(count), BigInteger::add);
                peaks.merge(recipe.id(), count, Math::max);
                return GraphJobRuntime.Outcome.ACCEPTED;
            }

            void receive() {
                for (var it = flights.iterator(); it.hasNext();) {
                    var flight = it.next();
                    if (flight.due > tick) continue;
                    it.remove();
                    flight.output.forEach((key, n) -> {
                        stock.merge(key, n, Math::addExact);
                        if (runtime.accept(key, n, false) != n) throw new AssertionError("Unowned return");
                    });
                }
            }

            public long deliver(String key, long count) {
                if (!key.equals("P") || stock.getOrDefault(key, 0L) < count) throw new AssertionError("Unfunded delivery");
                stock.merge(key, -count, Long::sum);
                delivered += count;
                return count;
            }

            public long refund(String key, long count) {
                if (stock.getOrDefault(key, 0L) < count) throw new AssertionError("Unfunded refund");
                stock.merge(key, -count, Long::sum);
                refunds.merge(key, count, Math::addExact);
                return count;
            }
        }
        var machines = new Machines();
        for (machines.tick = 0; machines.tick < 30_000 && !machines.runtime.finished(); machines.tick++) {
            machines.receive();
            machines.runtime.tick(machines, machines.tick, 64, () -> 0L);
            if (machines.tick == 80 || machines.tick == 400) machines.runtime = new GraphJobRuntime<>(machines.runtime.snapshot());
        }
        if (!machines.runtime.finished() || machines.delivered != amount || !machines.accepted.equals(plan.patternTimesExact()) ||
                machines.stock.values().stream().anyMatch(n -> n != 0) || !machines.refunds.equals(seeds))
            throw new AssertionError("Execution/conservation failure " + order + " ticks=" + machines.tick + " reason=" + machines.runtime.reason());
        for (String id : order) if (machines.peaks.getOrDefault(id, 0L) != CAP)
            throw new AssertionError("Funded cycle plateau " + order + " peaks=" + machines.peaks);
        cases++;
        System.out.println("Growth amount=" + amount + " seed=" + seed + " order=" + order + " ticks=" + machines.tick + " peaks=" + machines.peaks);
    }

    private static void intervalSafety() {
        var random = new Random(0xc7c1eL);
        for (int sample = 0; sample < 500; sample++) {
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
            var counts = new LinkedHashMap<String, BigInteger>();
            var initial = new HashMap<String, BigInteger>();
            for (int i = 0; i < 4; i++) {
                String id = "r" + i;
                recipes.put(id, recipe(id, Map.of("k" + i, 1L + random.nextInt(3)), Map.of("k" + ((i + 1) % 4), 1L + random.nextInt(4))));
                counts.put(id, BigInteger.valueOf(1 + random.nextInt(3)));
                initial.put("k" + i, BigInteger.valueOf(random.nextInt(30)));
            }
            var group = LoopBatching.group(counts, 20, recipes, initial::get);
            if (group == null) continue;
            if (!group.counts().equals(counts)) throw new AssertionError("Changed recipe counts");
            var stock = new HashMap<>(initial);
            for (var entry : group.counts().entrySet()) {
                var recipe = recipes.get(entry.getKey());
                long n = entry.getValue().multiply(BigInteger.valueOf(group.iterations())).longValueExact();
                for (long run = 0; run < n; run++) {
                    recipe.inputs().forEach((key, input) -> {
                        if (stock.get(key).compareTo(BigInteger.valueOf(input)) < 0) throw new AssertionError("Unfunded permutation");
                        stock.merge(key, BigInteger.valueOf(input).negate(), BigInteger::add);
                    });
                    recipe.outputs().forEach((key, output) -> stock.merge(key, BigInteger.valueOf(output), BigInteger::add));
                }
            }
            cases++;
        }
    }
}
