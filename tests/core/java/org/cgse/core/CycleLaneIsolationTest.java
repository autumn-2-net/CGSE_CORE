// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A blocked productive loop must not become a barrier for other funded material lanes. */
public final class CycleLaneIsolationTest {
    private static int assertions;
    private static final long ROUNDS = 10_000;

    public static void main(String[] args) {
        for (int budget : new int[] { 1, 4, 64 }) {
            for (boolean sharedRaw : new boolean[] { false, true }) {
                isolatedBranches(budget, sharedRaw, false);
                isolatedBranches(budget, sharedRaw, true);
            }
        }
        System.out.println("CycleLaneIsolationTest: " + assertions + " assertions passed");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static void isolatedBranches(int budget, boolean sharedRaw, boolean interleaved) {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("blocked-grow", recipe("blocked-grow", Map.of("seed", 1L, "raw", 1L), Map.of("mid", 1L)));
        recipes.put("blocked-return", recipe("blocked-return", Map.of("mid", 1L), Map.of("seed", 2L, "A", 1L)));
        recipes.put("other-grow", recipe("other-grow", Map.of("other-seed", 1L, "other-raw", 1L), Map.of("other-mid", 1L)));
        recipes.put("other-return", recipe("other-return", Map.of("other-mid", 1L), Map.of("other-seed", 2L, "B", 1L)));
        var stock = new LinkedHashMap<String, Long>();
        stock.put("seed", 1L); stock.put("other-seed", 1L);
        stock.put("raw", ROUNDS + (sharedRaw ? 70 : 0)); stock.put("other-raw", ROUNDS);
        var blocked = new PlanStep.Sequence(List.of(new PlanStep.Batch("blocked-grow", 1), new PlanStep.Batch("blocked-return", 1)));
        var other = new PlanStep.Sequence(List.of(new PlanStep.Batch("other-grow", 1), new PlanStep.Batch("other-return", 1)));
        var program = new ArrayList<PlanStep>();
        program.add(interleaved ? new PlanStep.Repeat(new PlanStep.Sequence(List.of(blocked, other)), ROUNDS) :
                new PlanStep.Repeat(blocked, ROUNDS));
        if (!interleaved) program.add(new PlanStep.Repeat(other, ROUNDS));
        var joinInputs = new LinkedHashMap<String, Long>();
        joinInputs.put("A", ROUNDS); joinInputs.put("B", ROUNDS);
        for (int i = 0; i < 70; i++) {
            String id = "free-" + i, input = sharedRaw ? "raw" : "raw-" + i, output = "free-output-" + i;
            if (!sharedRaw) stock.put(input, 1L);
            recipes.put(id, recipe(id, Map.of(input, 1L), Map.of(output, 1L)));
            program.add(new PlanStep.Batch(id, 1));
            joinInputs.put(output, 1L);
        }
        recipes.put("join", recipe("join", joinInputs, Map.of("P", 1L)));
        program.add(new PlanStep.Batch("join", 1));
        var plan = new GraphPlan<>("P", 1, true, new PlanStep.Sequence(program), recipes, stock,
                Map.of("seed", 1L, "other-seed", 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        check(DagScheduler.create(plan, Map.of()) == null, "Exercise the cyclic scheduler");
        var m = new Machines(plan, budget);
        // The blocked cycle must not prevent work beyond the 32-step lookahead,
        // either in a separate suffix or in another branch of the same Repeat.
        for (; m.tick < 500; m.step()) {
            if (m.tick == 7 || m.tick == 80) m.runtime = new GraphJobRuntime<>(m.runtime.snapshot());
            if (m.tick == 100) for (int i = 0; i < 70; i++)
                check(m.accepted.getOrDefault("free-" + i, BigInteger.ZERO).equals(BigInteger.ONE), "Independent ready lane was not served fairly");
        }
        for (int i = 0; i < 70; i++) check(m.accepted.getOrDefault("free-" + i, BigInteger.ZERO).equals(BigInteger.ONE), "Unrelated recipe starved " + i);
        check(m.accepted.getOrDefault("other-return", BigInteger.ZERO).equals(BigInteger.valueOf(ROUNDS)), "Other productive cycle starved");
        check(!m.accepted.containsKey("blocked-grow") && !m.accepted.containsKey("join"), "No phantom material");
        m.blocked = false;
        for (; m.tick < 2500 && !m.runtime.finished(); m.step()) {}
        check(m.runtime.finished(), "Unblocking did not resume " + m.runtime.reason());
        check(m.delivered == 1 && m.accepted.equals(plan.patternTimesExact()), "Wrong delivery or recipe counts");
        check(m.stock.values().stream().allMatch(n -> n == 0), "Unsettled material");
        check(m.refunds.equals(Map.of("seed", ROUNDS + 1, "other-seed", ROUNDS + 1)), "Seed/surplus loss");
        System.out.println("Independent lanes budget=" + budget + " sharedRaw=" + sharedRaw + " interleaved=" + interleaved + " ticks=" + m.tick);
    }

    private static final class Machines implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final int budget;
        final Map<String, Long> stock, refunds = new HashMap<>();
        final Map<String, BigInteger> accepted = new HashMap<>();
        final List<Flight> flights = new ArrayList<>();
        long tick, delivered;
        boolean blocked = true;
        record Flight(long due, Map<String, Long> outputs) {}

        Machines(GraphPlan<String> plan, int budget) {
            runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            stock = new HashMap<>(plan.initial()); this.budget = budget;
        }
        void step() {
            for (var it = flights.iterator(); it.hasNext();) {
                var flight = it.next(); if (flight.due > tick) continue;
                it.remove();
                flight.outputs.forEach((k, n) -> {
                    check(runtime.accept(k, n, false) == n, "Lost return");
                    stock.merge(k, n, Math::addExact);
                });
            }
            runtime.tick(this, tick++, budget, () -> 0L);
        }
        public long capacity(GraphRecipe<String> recipe, long count) {
            return blocked && recipe.id().equals("blocked-grow") ? 0 : count;
        }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long count, Map<String, Long> in) {
            in.forEach(this::debit);
            var out = new LinkedHashMap<String, Long>();
            recipe.executionOutputs().forEach((k, n) -> out.put(k, Math.multiplyExact(n, count)));
            flights.add(new Flight(tick + 3, out));
            accepted.merge(recipe.id(), BigInteger.valueOf(count), BigInteger::add);
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        private void debit(String key, long count) {
            check(stock.getOrDefault(key, 0L) >= count, "Spent expected return " + key);
            stock.merge(key, -count, Long::sum);
        }
        public long deliver(String key, long count) { debit(key, count); delivered += count; return count; }
        public long refund(String key, long count) { debit(key, count); refunds.merge(key, count, Math::addExact); return count; }
    }
    private static void check(boolean valid, String message) {
        assertions++; if (!valid) throw new AssertionError(message);
    }
}
