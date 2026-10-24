// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** A fragmented productive source cycle may move only with its full inventory contract. */
public final class PlanSourceCyclesTest {
    private static int checks;
    private static final long MEMORY = 32L << 20;

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream()
                .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static Map<String, GraphRecipe<String>> recipes(boolean reusable, boolean consumed) {
        var result = new LinkedHashMap<String, GraphRecipe<String>>();
        result.put("release", recipe("release", Map.of("charged", 1L), Map.of("dust", 9L)));
        result.put("infuse", recipe("infuse", Map.of("dust", 1L, "raw", 1L), Map.of("infused", 1L)));
        var slots = new ArrayList<GraphRecipe.Slot<String>>();
        slots.add(new GraphRecipe.Slot<>("infused", 1));
        if (reusable || consumed) slots.add(new GraphRecipe.Slot<>("tool", 1, 1, true, reusable));
        result.put("charge", new GraphRecipe<>("charge", "charge", slots,
                reusable ? Map.of("charged", 1L, "tool", 1L) : Map.of("charged", 1L)));
        result.put("consume", recipe("consume", Map.of("dust", 2L), Map.of("goal", 1L)));
        result.put("raw", recipe("raw", Map.of("ore", 1L), Map.of("raw", 1L)));
        return result;
    }

    private static PlanStep fragmented(int order, boolean upstream, long multiplier) {
        String[][] permutations = {{"release", "infuse", "charge"}, {"release", "charge", "infuse"},
                {"infuse", "release", "charge"}, {"infuse", "charge", "release"},
                {"charge", "release", "infuse"}, {"charge", "infuse", "release"}};
        var passes = new ArrayList<PlanStep>();
        if (upstream) passes.add(new PlanStep.Batch("raw", 64));
        int[] sizes = {1, 4, 2, 9, 5, 3, 8, 2, 6, 3};
        for (int i = 0; i < sizes.length; i++) {
            var body = new ArrayList<PlanStep>();
            String[] permutation = permutations[(order + i) % permutations.length];
            for (int j = 0; j < permutation.length; j++) {
                String id = permutation[j];
                long n = sizes[i] + (id.equals("infuse") ? 1 : id.equals("charge") ? 2 : 0);
                body.add(new PlanStep.Batch(id, n));
                if (j == i % 3) body.add(new PlanStep.Batch("consume", sizes[i]));
            }
            PlanStep pass = new PlanStep.Sequence(body);
            if (multiplier > 1) pass = new PlanStep.Repeat(pass, multiplier);
            passes.add(new PlanStep.Repeat(pass, 1));
        }
        return new PlanStep.Sequence(passes);
    }

    private static PlanningBudget budget(long work, long bytes) {
        return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime);
    }

    private static void contract(PlanStep original, PlanStep revised, Map<String, GraphRecipe<String>> recipes) {
        check(PlanCountComputation.of(original).equals(PlanCountComputation.of(revised)), "Changed a fixed recipe count");
        SequenceSummary<String> before = SequenceSummary.of(original, recipes), after = SequenceSummary.of(revised, recipes);
        var keys = before.keys(); keys.addAll(after.keys());
        for (String key : keys) {
            check(after.required(key).compareTo(before.required(key)) <= 0, "New upfront requirement: " + key);
            check(after.delta(key).equals(before.delta(key)), "Changed final material balance: " + key);
        }
    }

    private static boolean hasAdaptiveCycle(PlanStep word) {
        var pending = new ArrayDeque<PlanStep>(); pending.add(word);
        var seen = Collections.newSetFromMap(new IdentityHashMap<PlanStep, Boolean>());
        while (!pending.isEmpty()) {
            PlanStep next = pending.removeFirst(); if (!seen.add(next)) continue;
            if (next instanceof PlanStep.Repeat repeat) {
                if (repeat.times() > 1 && PlanCountComputation.of(repeat.body()).size() >= 3) return true;
                pending.add(repeat.body());
            } else if (next instanceof PlanStep.Sequence sequence) pending.addAll(sequence.children());
        }
        return false;
    }

    private static void fragmentedOrders() {
        for (boolean reusable : new boolean[]{false, true}) for (int order = 0; order < 6; order++) {
            var recipes = recipes(reusable, false); PlanStep original = fragmented(order, false, 1);
            var budget = budget(4_000_000, MEMORY);
            PlanStep revised = PlanSourceCycles.optimize(original, recipes, budget);
            contract(original, revised, recipes);
            check(revised != original && hasAdaptiveCycle(revised), "Skipped variable pass source cycle, order=" + order);
            check(budget.reservedBytes() == 0, "Successful source rewrite leaked memory");
            if (reusable) check(SequenceSummary.of(revised, recipes).required("tool").equals(BigInteger.ONE),
                    "Read-only tool was multiplied or lost");
        }
    }

    private static void conservativeBoundaries() {
        var plain = recipes(false, false);
        PlanStep dependent = fragmented(0, true, 1);
        var budget = budget(4_000_000, MEMORY);
        PlanStep revised = PlanSourceCycles.optimize(dependent, plain, budget);
        check(revised == dependent, "Moved a cycle before its outside producer");
        contract(dependent, revised, plain);
        check(budget.reservedBytes() == 0, "Source rejection leaked memory");

        var consumed = recipes(false, true); PlanStep configured = fragmented(2, false, 1);
        budget = budget(4_000_000, MEMORY); revised = PlanSourceCycles.optimize(configured, consumed, budget);
        check(revised == configured, "Changed consumed configuration batch boundaries");
        contract(configured, revised, consumed);
        check(budget.reservedBytes() == 0, "Configuration rejection leaked memory");

        PlanStep huge = fragmented(0, false, Long.MAX_VALUE);
        budget = budget(4_000_000, MEMORY); revised = PlanSourceCycles.optimize(huge, plain, budget);
        check(revised == huge, "Truncated counts above long range");
        contract(huge, revised, plain);
        check(PlanCountComputation.of(huge).get("release").compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                "Huge-count control is not above long range");
        check(budget.reservedBytes() == 0, "Huge-count rejection leaked memory");

        PlanStep nested = new PlanStep.Sequence(List.of(new PlanStep.Batch("release", 1),
                new PlanStep.Batch("infuse", 1), new PlanStep.Batch("charge", 1)));
        for (int level = 0; level < 500; level++) nested = new PlanStep.Repeat(nested, 2);
        var wrappers = new ArrayList<PlanStep>();
        for (int i = 0; i < 8; i++) wrappers.add(new PlanStep.Repeat(nested, 1));
        PlanStep deep = new PlanStep.Sequence(wrappers);
        budget = budget(4_000_000, MEMORY); revised = PlanSourceCycles.optimize(deep, plain, budget);
        check(revised == deep, "Deep repetition counts escaped admission");
        contract(deep, revised, plain);
        check(budget.reservedBytes() == 0, "Deep count admission leaked memory");
        check(budget.searchWork() < 25_000, "Deep count admission performed an unbounded expansion");
    }

    private static void lifecycle() {
        var recipes = recipes(false, false); PlanStep original = fragmented(0, false, 1);
        var low = budget(131071, MEMORY);
        check(PlanSourceCycles.optimize(original, recipes, low) == original, "Low work budget lost original word");
        check(low.reservedBytes() == 0, "Low work budget retained memory");
        for (long bytes : new long[]{1, 512, 2048, 8192, 32768}) {
            var limited = budget(4_000_000, bytes); limited.failureDetail("existing diagnostic");
            PlanStep revised = PlanSourceCycles.optimize(original, recipes, limited);
            contract(original, revised, recipes);
            check(limited.reservedBytes() == 0, "Optional memory limit leaked reservations: " + bytes);
            check(limited.failureDetail().equals("existing diagnostic"), "Optional attempt replaced earlier diagnostic");
        }
        for (int at : new int[]{0, 40, 200}) {
            var calls = new AtomicInteger();
            var cancelled = new PlanningBudget(0, 4_000_000, MEMORY, () -> calls.getAndIncrement() >= at, System::nanoTime);
            try {
                PlanSourceCycles.optimize(original, recipes, cancelled);
                throw new AssertionError("Ignored cancellation at " + at);
            } catch (CancellationException expected) { checks++; }
            check(cancelled.reservedBytes() == 0, "Cancelled optional pass leaked memory");
        }
    }

    private static final class Host implements GraphJobRuntime.Adapter<String> {
        final GraphPlan<String> plan;
        final GraphJobRuntime<String> runtime;
        final Map<String, Long> stock, refunded = new LinkedHashMap<>();
        final Map<Integer, List<Map<String, Long>>> returns = new TreeMap<>();
        long delivered;
        int tick;
        Host(GraphPlan<String> plan) {
            this.plan = plan; runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            stock = new LinkedHashMap<>(plan.initial());
        }
        public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long count, Map<String, Long> inputs) {
            check(inputs.equals(recipe.dispatchInputs(count)), "Changed physical dispatch inputs");
            inputs.forEach(this::take);
            var out = new LinkedHashMap<String, Long>();
            recipe.executionOutputs().forEach((key, amount) -> out.put(key, Math.multiplyExact(amount, count)));
            returns.computeIfAbsent(tick + 1, ignored -> new ArrayList<>()).add(out);
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        private void take(String key, long count) {
            long left = Math.subtractExact(stock.getOrDefault(key, 0L), count);
            check(left >= 0, "Spent unreturned output"); stock.put(key, left);
        }
        public long deliver(String key, long count) {
            check(key.equals(plan.target()), "Wrong delivered key"); take(key, count);
            delivered = Math.addExact(delivered, count); return count;
        }
        public long refund(String key, long count) {
            take(key, count); refunded.merge(key, count, Math::addExact); return count;
        }
        void run() {
            for (tick = 0; tick < 512 && !runtime.finished(); tick++) {
                var due = returns.remove(tick);
                if (due != null) for (var out : due) out.forEach((key, count) -> {
                    stock.merge(key, count, Math::addExact);
                    check(runtime.accept(key, count, false) == count, "Lost physical output");
                });
                runtime.tick(this, tick, 4096, () -> 0L);
            }
            check(runtime.finished(), "Rewritten source cycle stalled on reserved inventory: " + runtime.reason());
            check(delivered == plan.amount(), "Lost target delivery");
            check(returns.isEmpty() && stock.values().stream().allMatch(value -> value == 0), "Lost final inventory");
            plan.seeds().forEach((key, count) -> check(refunded.getOrDefault(key, 0L) >= count, "Consumed final seed"));
        }
    }

    private static void reservedHeadroom() {
        var recipes = recipes(false, false);
        recipes.put("consume", recipe("consume", Map.of("dust", 8L), Map.of("goal", 1L)));
        var fragments = new ArrayList<PlanStep>();
        for (int i = 0; i < 64; i++) fragments.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(
                new PlanStep.Batch("release", 1), new PlanStep.Batch("infuse", 1),
                new PlanStep.Batch("charge", 1), new PlanStep.Batch("consume", 1))), 1));
        PlanStep original = new PlanStep.Sequence(fragments);
        for (long reserve : new long[]{13, Long.MAX_VALUE - 16}) {
            var seeds = Map.of("dust", reserve); var budget = budget(4_000_000, MEMORY);
            PlanStep revised = PlanSourceCycles.optimize(original, recipes, budget, "goal", 64, seeds, true);
            contract(original, revised, recipes);
            check((reserve == 13) == (revised != original), "Seed-aware headroom selected the wrong rewrite");
            check(budget.reservedBytes() == 0, "Seed-aware headroom leaked memory");
            var initial = PlanVerifier.initial(original, recipes, "goal", 64, seeds);
            for (PlanStep word : List.of(original, revised)) {
                var plan = new GraphPlan<>("goal", 64, true, word, recipes, initial, seeds, Map.of(),
                        GraphPlan.Result.FEASIBLE, 0, 0);
                PlanVerifier.verifyRuntimeInventory(plan); new Host(plan).run();
            }
        }
        // The order's existing target stock occupies the same physical slots as
        // explicit seed reservations. Both belong in the optional rewrite guard.
        var budget = budget(4_000_000, MEMORY);
        PlanStep targetReserved = PlanSourceCycles.optimize(original, recipes, budget,
                "dust", Long.MAX_VALUE - 16, Map.of(), false);
        check(targetReserved == original, "Ignored target reservation headroom");
        contract(original, targetReserved, recipes);
        check(budget.reservedBytes() == 0, "Target headroom guard leaked memory");
    }

    public static void main(String[] args) {
        fragmentedOrders(); conservativeBoundaries(); lifecycle(); reservedHeadroom();
        checks += DagSuffixCases.run();
        System.out.println("PlanSourceCyclesTest: assertions=" + checks);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    /** Bounded acyclic suffix aggregation, including tool and capacity boundaries. */
    private static final class DagSuffixCases {
        private static int checks;
        private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
            return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
        }
        private record Fixture(PlanStep word, Map<String, GraphRecipe<String>> recipes, long target) {}
        private static Fixture fixture(int rotation, int configuration, boolean otherCycle, boolean capacity) {
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
            recipes.put("release", recipe("release", Map.of("charged", 1L), Map.of("dust", 9L)));
            recipes.put("infuse", recipe("infuse", Map.of("dust", 1L, "external", 1L), Map.of("infused", 1L)));
            recipes.put("charge", recipe("charge", Map.of("infused", 1L), Map.of("charged", 1L)));
            var slots = new ArrayList<GraphRecipe.Slot<String>>(); slots.add(new GraphRecipe.Slot<>("dust", 2));
            if (configuration != 0) slots.add(new GraphRecipe.Slot<>("tool", 1, 1, true, configuration == 1));
            long yield = capacity ? Long.MAX_VALUE / 8 : 1;
            var outputs = new LinkedHashMap<String, Long>(); outputs.put("cut", yield); outputs.put("slag", 1L);
            if (configuration == 1) outputs.put("tool", 1L);
            recipes.put("cut", new GraphRecipe<>("cut", "cut", slots, outputs));
            recipes.put("polish", recipe("polish", Map.of("cut", yield, "slag", 1L), Map.of("part", 1L)));
            recipes.put("finish", recipe("finish", Map.of("part", 1L, "external2", 1L), Map.of("T", 1L)));
            if (configuration == 3) recipes.put("forge", recipe("forge", Map.of("dust", 1L), Map.of("tool", 1L)));
            if (otherCycle) {
                recipes.put("outbound", recipe("outbound", Map.of("part", 1L), Map.of("alternate", 1L)));
                recipes.put("return", recipe("return", Map.of("alternate", 1L), Map.of("part", 1L)));
            }
            int[] sizes = capacity ? new int[]{1,1,1,1,1,1,1,1,1,1} : new int[]{1,4,2,9,5,3,8,2,6,3};
            String[] cycle = {"release", "infuse", "charge"};
            var passes = new ArrayList<PlanStep>(); long total = 0;
            for (int index = 0; index < sizes.length; index++) {
                int n = sizes[index]; total += n; var pass = new ArrayList<PlanStep>();
                for (int i = 0; i < 3; i++) pass.add(new PlanStep.Batch(cycle[(i + rotation + index) % 3], n));
                if (configuration == 3) pass.add(new PlanStep.Batch("forge", n));
                pass.add(new PlanStep.Batch("cut", n)); pass.add(new PlanStep.Batch("polish", n));
                if (otherCycle) { pass.add(new PlanStep.Batch("outbound", n)); pass.add(new PlanStep.Batch("return", n)); }
                pass.add(new PlanStep.Batch("finish", n)); passes.add(new PlanStep.Repeat(new PlanStep.Sequence(pass), 1));
            }
            return new Fixture(new PlanStep.Sequence(passes), recipes, total);
        }
        private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
        private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
        private static boolean compact(PlanStep word) {
            if (!(word instanceof PlanStep.Sequence root) || root.children().size() != 2 ||
                    !(root.children().get(1) instanceof PlanStep.Sequence suffix)) return false;
            return suffix.children().stream().allMatch(PlanStep.Batch.class::isInstance);
        }
        private static void verify(Fixture fixture, PlanStep changed, Map<String, Long> seeds) {
            var before = SequenceSummary.of(fixture.word, fixture.recipes); var after = SequenceSummary.of(changed, fixture.recipes);
            check(PlanCountComputation.of(fixture.word).equals(PlanCountComputation.of(changed)), "Recipe counts changed");
            var initial = new LinkedHashMap<String, Long>();
            var keys = before.keys(); keys.addAll(after.keys());
            for (String key : keys) {
                check(after.required(key).compareTo(before.required(key)) <= 0, "Upfront demand grew: " + key);
                check(after.delta(key).equals(before.delta(key)), "Balance changed: " + key);
                long goal = seeds.getOrDefault(key, 0L) + (key.equals("T") ? fixture.target : 0);
                initial.put(key, before.required(key).max(BigInteger.valueOf(goal).subtract(before.delta(key))).longValueExact());
            }
            var plan = new GraphPlan<>("T", fixture.target, true, changed, fixture.recipes, initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            var budget = budget(1_000_000, 32L << 20);
            try (var proof = new PlanVerification<>(plan, budget)) { while (!proof.step()) {} }
            check(budget.reservedBytes() == 0, "Verification reservation leaked");
        }
        static int run() {
            for (int rotation = 0; rotation < 3; rotation++) for (int configuration = 0; configuration < 4; configuration++) {
                var fixture = fixture(rotation, configuration, false, false);
                var budget = budget(8_000_000, 32L << 20);
                var seeds = configuration == 1 ? Map.of("charged", 1L, "tool", 1L) : Map.of("charged", 1L);
                PlanStep revised = PlanSourceCycles.optimize(fixture.word, fixture.recipes, budget, "T", fixture.target, seeds, true);
                check(revised != fixture.word, "Source cycle unexpectedly unchanged");
                check(compact(revised) == (configuration != 3), "Consumed configuration production boundary");
                verify(fixture, revised, seeds); check(budget.reservedBytes() == 0, "Optimizer reservation leaked");
            }
            for (boolean capacity : new boolean[]{false, true}) {
                var fixture = fixture(0, 0, !capacity, capacity); var budget = budget(8_000_000, 32L << 20);
                PlanStep revised = PlanSourceCycles.optimize(fixture.word, fixture.recipes, budget, "T", fixture.target, Map.of("charged", 1L), true);
                check(revised != fixture.word, "Safe source prefix was discarded");
                check(!compact(revised), "Cyclic or overflowing suffix was compacted");
                verify(fixture, revised, Map.of("charged", 1L)); check(budget.reservedBytes() == 0, "Rejected suffix reservation leaked");
            }
            var fixture = fixture(0, 1, false, false);
            for (long work : new long[]{1, 131072, 150000, 1000000}) for (long memory : new long[]{1024, 8192, 65536, 32L << 20}) {
                var budget = budget(work, memory);
                try { verify(fixture, PlanSourceCycles.optimize(fixture.word, fixture.recipes, budget), Map.of()); }
                catch (PlanningBudget.Exhausted exhausted) { check(exhausted.limit() != PlanningBudget.Limit.MEMORY_LIMIT, "Optional allocation became fatal"); }
                check(budget.reservedBytes() == 0, "Cutoff reservation leaked");
            }
            var cancelled = new PlanningBudget(0, 1000000, 32L << 20, () -> true, System::nanoTime);
            try { PlanSourceCycles.optimize(fixture.word, fixture.recipes, cancelled); throw new AssertionError("Cancellation swallowed"); }
            catch (CancellationException expected) { check(cancelled.reservedBytes() == 0, "Cancellation reservation leaked"); }
            return checks;
        }
    }
}
