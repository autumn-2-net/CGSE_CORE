// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Positive supply witnesses get a turn without waiting for a negative proof. */
public final class PrimaryPositiveRefillTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 16L << 20, () -> false, System::nanoTime); }
    private static Object get(Object owner, String name) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static void physical(GraphPlan<String> plan) {
        var funded = GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), plan.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0);
        PlanVerifier.verify(funded);
        check(SequenceSummary.of(plan.steps(), plan.recipes()).delta(plan.target()).compareTo(BigInteger.valueOf(plan.amount())) >= 0,
                "supply target stock replaced manufacturing");
    }
    private static void originalSix() throws Exception {
        var random = new Random(2992026); int cases = 0;
        for (int trial = 0; trial < 500; trial++) {
            var recipes = new ArrayList<GraphRecipe<String>>(); var stock = new HashMap<String, Long>();
            for (int i = 0; i < 6; i++) stock.put("k" + i, (long) random.nextInt(8));
            for (int i = 0; i < 8; i++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (int j = 0; j < 1 + random.nextInt(2); j++) in.merge("k" + random.nextInt(6), 1L + random.nextInt(3), Long::sum);
                for (int j = 0; j < 1 + random.nextInt(2); j++) out.merge("k" + random.nextInt(6), 1L + random.nextInt(3), Long::sum);
                recipes.add(recipe("r" + i, in, out));
            }
            int amount = 1 + random.nextInt(6);
            if (!Set.of(151, 171, 291, 380, 414, 466).contains(trial)) continue;
            var b = budget(131072); var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "k0", amount, stock, true, true, b).catalysts(CatalystPolicy.MINIMAL);
            try {
                while (!work.step()) {}
                var p = work.result(); check(p.conditionalPreview(), "missing positive witness for original trial " + trial + ": " + p.result());
                check(p.result() == GraphPlan.Result.INCOMPLETE_MODEL, "undecided stock model acquired an infeasibility conclusion");
                physical(p);
                var keys = new HashSet<>(p.initialExact().keySet()); keys.addAll(p.missingExact().keySet());
                for (String key : keys) {
                    var gap = p.initialExact().getOrDefault(key, BigInteger.ZERO)
                            .subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L))).max(BigInteger.ZERO);
                    check(p.missingExact().getOrDefault(key, BigInteger.ZERO).equals(gap),
                            "conditional bill did not credit original stock: trial=" + trial + "; key=" + key);
                }
                check((Long) get(b, "maxNodes") == 131072L && b.remainingWork() == 0 && b.failureDetail().endsWith("/131072"),
                        "positive turn renewed or changed the global search allowance");
                // chargeTicks accounts before throwing. An existing optional
                // owner can catch that throw, then one coordinator check is
                // charged before terminal publication; no new allowance.
                check(b.searchWork() <= 131074, "positive turn failed to exit within two terminal work checks");
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "original trial leaked workspace"); cases++;
        }
        check(cases == 6, "original fixture coverage");
    }
    private static void ordinaryAndNaturalExit(GraphMode mode) throws Exception {
        var compiler = new GraphCompiler<>(List.of(recipe("make", Map.of("raw", 1L), Map.of("T", 1L, "reserve", 1L))));
        var b = budget(131072); var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of(), Set.of(), Map.of("reserve", 1L), true, true, b, mode).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!work.step()) {}
            check((Boolean) get(work, "positiveRefillTried"), "ordinary restricted model did not share the positive turn");
            var positive = (GraphPlan<?>) get(work, "positiveRefillVerified"); check(positive != null, "ordinary positive witness missing");
            @SuppressWarnings("unchecked") var plan = (GraphPlan<String>) positive; physical(plan);
            check(plan.seeds().equals(Map.of("reserve", 1L)), "explicit final reserve changed");
            set(work, "verified", null); set(work, "productionVerified", null);
            for (var reason : List.of(GraphPlan.Result.UNKNOWN, GraphPlan.Result.INCOMPLETE_MODEL, GraphPlan.Result.GRAPH_LIMIT)) {
                var empty = new GraphPlan<>("T", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                        Map.of(), Map.of("reserve", 1L), Map.of(), reason, 0, 0);
                set(work, "result", empty); check(work.step(), "natural terminal did not complete");
                check(work.result().conditionalPreview(), "natural " + reason + " erased certified positive preview"); physical(work.result());
            }
            var targetOnly = new GraphPlan<>("T", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                    Map.of("T", 1L, "reserve", 1L), Map.of("reserve", 1L), Map.of("T", 1L, "reserve", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
            set(work, "result", targetOnly);
            while (!work.step()) {}
            check(work.result().conditionalPreview() && !work.result().patternTimes().isEmpty(), "rejected target-only proposal erased earlier manufacturing preview");
            physical(work.result());
            var feasible = GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), plan.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, b.nodes());
            set(work, "result", feasible); set(work, "productionVerified", feasible);
            check(work.step() && work.result().feasible(), "conditional preview displaced feasible main result");
            set(work, "verified", feasible);
            var unknown = new GraphPlan<>("T", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                    Map.of(), Map.of("reserve", 1L), Map.of(), GraphPlan.Result.UNKNOWN, 0, 0);
            set(work, "result", unknown);
            check(work.step() && work.result().feasible(), "natural failure displaced separately verified feasible main incumbent");
            var proof = new GraphPlan<>("T", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                    Map.of(), Map.of("reserve", 1L), Map.of(), GraphPlan.Result.INFEASIBLE, 0, 0);
            set(work, "result", proof); check(work.step(), "strict proof terminal"); check(work.result().result() == (mode == GraphMode.FULL ? GraphPlan.Result.INFEASIBLE : GraphPlan.Result.INCOMPLETE_MODEL), "preview overwrote strict proof");
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "ordinary positive workspace leaked");
    }
    private static void noTargetSupplyShortcut() throws Exception {
        var b = budget(131072); var work = new GraphPlanningWork<>(new GraphCompiler<String>(List.of()), "T", 1, Map.of(), Set.of(), Map.of(), false, true, b).catalysts(CatalystPolicy.MINIMAL);
        try { while (!work.step()) {} check(!work.result().conditionalPreview(), "target-only supply became manufacturing"); check(get(work, "positiveRefillVerified") == null, "nonmanufacturing candidate certified"); }
        finally { work.close(); }
        check(b.reservedBytes() == 0, "empty domain leaked workspace");
    }
    private static void migrationAndUnfinishedCutoff() throws Exception {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 2000; i++) recipes.add(recipe("chain" + i, Map.of("k" + i, 1L), Map.of("k" + (i + 1), 1L)));
        var b = budget(500000); var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "k2000", 1, Map.of(), false, true, b).catalysts(CatalystPolicy.MINIMAL);
        var executor = Executors.newSingleThreadExecutor();
        try {
            b.charge(20000);
            var proposal = new GraphPlan<>("k2000", 1, false, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                    Map.of(), Map.of(), Map.of("k2000", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
            set(work, "candidate", proposal);
            Method after = GraphPlanningWork.class.getDeclaredMethod("afterSolve"); after.setAccessible(true); after.invoke(work);
            check(get(work, "positiveRefill") != null, "positive turn did not start on worker A");
            long started = b.nodes(), grant = (Long) get(work, "positiveRefillAllowance");
            var migratingWork = work;
            executor.submit(() -> {
                try { for (int i = 0; get(migratingWork, "positiveRefill") != null && i < 10000; i++) migratingWork.step(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).get();
            check(get(work, "positiveRefill") == null, "worker migration defeated the local grant");
            check(b.nodes() - started <= grant + 512, "positive turn exceeded aggregate grant after worker migration");
            check(get(work, "positiveRefillVerified") == null, "incomplete long-chain builder certified a word");
            check(get(work, "result") == null, "optional quota ended primary search");
        } finally { executor.shutdownNow(); work.close(); }
        check(b.reservedBytes() == 0, "migrated positive turn leaked workspace");
        b = budget(131072); work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe("make", Map.of("raw", 1L), Map.of("T", 1L)))), "T", 1, Map.of(), false, true, b).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (get(work, "positiveRefill") == null) check(!work.step(), "ordinary request ended before positive turn");
            try { b.charge(b.remainingWork() + 1); } catch (PlanningBudget.Exhausted expected) { }
            check(work.step(), "global cutoff did not end unfinished turn");
            check(!work.result().conditionalPreview(), "unfinished positive turn published a supply bill");
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "unfinished positive turn leaked workspace");
    }
    private static void optionalMemoryDecline() throws Exception {
        var memory = new PlanningMemory(16L << 20);
        var refused = new java.util.concurrent.atomic.AtomicBoolean();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        var b = new PlanningBudget(0, 131072, 16L << 20, () -> {
            boolean optional = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f -> f.getMethodName().equals("stepPositiveRefill"));
            if (optional && refused.compareAndSet(false, true)) memory.limit(Math.max(1, memory.used()));
            else if (!optional && refused.get() && restored.compareAndSet(false, true)) memory.limit(16L << 20);
            return false;
        }, System::nanoTime).memory(memory);
        var compiler = new GraphCompiler<>(List.of(recipe("make", Map.of("raw", 1L), Map.of("T", 1L))));
        var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of(), false, true, b).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!work.step()) {}
            check(refused.get() && restored.get(), "optional memory refusal was not exercised");
            check(work.result().missing().equals(Map.of("raw", 1L)), "optional memory refusal discarded the normal primary frontier");
            physical(work.result());
        } finally { work.close(); }
        check(b.reservedBytes() == 0 && memory.used() == 0, "optional memory refusal leaked workspace"); b.closeMemory();
    }
    private static void limitedFeasiblePriority(GraphMode mode) throws Exception {
        var make = recipe("make", Map.of("raw", 1L), Map.of("T", 1L, "reserve", 1L));
        var full = new GraphCompiler<>(List.of(make));
        var compiler = mode == GraphMode.FULL ? full : new GraphCompiler<>(full.catalog(), mode, full.verificationCatalog());
        var b = budget(131072);
        var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of("raw", 1L), Set.of(), Map.of("reserve", 1L), true, true, b, mode).catalysts(CatalystPolicy.MINIMAL);
        try {
            var feasible = new GraphPlan<>("T", 1, true, new PlanStep.Batch("make", 1), Map.of("make", make),
                    Map.of("raw", 1L), Map.of("reserve", 1L), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0, 0);
            feasible.markNetProductionVerified(); physical(feasible);
            var scout = new GraphPlan<>("T", 1, true, new PlanStep.Batch("make", 2), Map.of("make", make),
                    Map.of("raw", 2L), Map.of("reserve", 1L), Map.of("raw", 1L), GraphPlan.Result.INCOMPLETE_MODEL, 0, 0);
            scout.markNetProductionVerified(); physical(scout);
            var unverified = new GraphPlan<>("T", 1, true, new PlanStep.Sequence(List.of()), Map.<String, GraphRecipe<String>>of(),
                    Map.of("T", 1L, "reserve", 1L), Map.of("reserve", 1L), Map.of("T", 1L, "reserve", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
            set(work, "positiveRefillVerified", scout); set(work, "verified", feasible); set(work, "productionVerified", feasible); set(work, "result", unverified);
            try { b.charge(b.remainingWork() + 1); } catch (PlanningBudget.Exhausted expected) { }
            check(work.step(), "limited incumbent priority did not finish");
            check(work.result().feasible(), "positive scout displaced separately verified feasible main incumbent");
            check(work.result().patternTimes().equals(Map.of("make", 1L)), "limited main word changed");
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "limited incumbent priority leaked workspace");
    }
    private static void normalizationCutoff(GraphMode mode) throws Exception {
        for (var limit : List.of(PlanningBudget.Limit.SEARCH_LIMIT, PlanningBudget.Limit.TIMEOUT, PlanningBudget.Limit.MEMORY_LIMIT)) {
            var b = budget(131072);
            var grow = new GraphRecipe<String>("make", "make", List.of(new GraphRecipe.Slot<>("T", 1), new GraphRecipe.Slot<>("raw", 1)), Map.of("T", 2L));
            var compiler = new GraphCompiler<>(List.of(grow));
            var work = new GraphPlanningWork<>(compiler, "T", 1, Map.of("T", 1L), Set.of(), Map.of(), false, true, b, mode).catalysts(CatalystPolicy.MINIMAL);
            try {
                while (get(work, "positiveRefillDeficits") == null) check(!work.step(), "positive normalization never admitted");
                var normalization = (RefillDeficitNormalization<?>) get(work, "positiveRefillDeficits");
                check(normalization.result() == null && get(work, "positiveRefillVerified") == null,
                        "raw goal-builder bill became an incumbent before normalization");
                long before = b.nodes();
                var retained = work.limited(new PlanningBudget.Exhausted(limit));
                check(retained.missingExact().isEmpty(), "limit published an unnormalized shopping bill");
                check(b.nodes() == before, "limit continued deficit normalization outside the budget");
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "pending deficit normalization leaked workspace");
        }
    }

    private static void normalizedFeasibleWins() throws Exception {
        for (boolean preserve : List.of(false, true)) {
            var bad = new GraphRecipe<String>("bad", "bad", List.of(new GraphRecipe.Slot<>("T", 1), new GraphRecipe.Slot<>("absent", 1)), Map.of("T", 2L));
            var good = new GraphRecipe<String>("good", "good", List.of(new GraphRecipe.Slot<>("T", 1), new GraphRecipe.Slot<>("raw", 1)), Map.of("T", 2L));
            var stock = Map.of("T", 1L, "raw", 1L); var b = budget(131072);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(bad, good)), "T", 1, stock, preserve, true, b).catalysts(CatalystPolicy.MINIMAL);
            try {
                while (!work.step()) {}
                check(work.result().feasible() && work.result().patternTimes().equals(Map.of("good", 1L)),
                        "fully funded normalized production was lost behind the first missing source");
                var positive = (GraphPlan<?>) get(work, "positiveRefillVerified");
                check(positive != null && positive.feasible(), "normalization did not retain its complete feasible certificate");
                PlanVerifier.verify(work.result()); physical(work.result());
                for (var entry : work.result().initialExact().entrySet())
                    check(entry.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(entry.getKey(), 0L))) <= 0, "feasible normalized plan overspent stock");
                check(b.searchWork() < 8192, "feasible normalized word waited for unrelated search exhaustion");
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "normalized feasible word leaked workspace");
        }
    }

    private static void goalImportPublication() throws Exception {
        for (boolean completed : List.of(false, true)) {
            var grow = new GraphRecipe<String>("grow", "grow", List.of(new GraphRecipe.Slot<>("T", 1), new GraphRecipe.Slot<>("raw", 1)), Map.of("T", 2L));
            var stock = Map.of("T", 1L); var b = budget(131072);
            var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(grow)), "T", 1, stock, false, true, b).catalysts(CatalystPolicy.MINIMAL);
            try {
                // Drive the owner's goal-import adapter. No result, proof or
                // certificate is injected; the actual MME constructs/verifies
                // the physical word and the real normalizer restores its bill.
                Method begin = GraphPlanningWork.class.getDeclaredMethod("beginMissingPreview"); begin.setAccessible(true); begin.invoke(work);
                @SuppressWarnings("unchecked") var child = (MissingMaterialExpansion<String>) get(work, "missingExpansion");
                child.refineCycles(false).refineSources(false);
                while (get(work, "missingDeficits") == null) check(!work.step(), "goal import did not require target-stock rebasing");
                check(child.result().missing().containsKey("T"), "goal-builder zero-target convention was not exercised");
                var normalization = (RefillDeficitNormalization<?>) get(work, "missingDeficits");
                if (completed) while (!normalization.step()) {}
                long before = b.nodes(); var retained = work.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT));
                check(b.nodes() == before, "owner limit continued goal-import normalization");
                if (completed) {
                    check(retained.missing().equals(Map.of("raw", 1L)) && retained.patternTimes().equals(Map.of("grow", 1L)),
                            "completed normalized goal bill was lost or used zero-target stock convention");
                    physical(retained);
                } else check(retained.missingExact().isEmpty(), "unfinished goal normalization exported its raw bill");
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "goal import publication leaked workspace");
        }
    }

    private static void defaultStockPolicy() throws Exception {
        var ab = recipe("ab", Map.of("A", 1L, "R", 1L), Map.of("B", 1L));
        var ba = recipe("ba", Map.of("B", 1L), Map.of("A", 1L, "P", 1L));
        var seed = recipe("seed", Map.of("Q", 1L), Map.of("A", 1L));
        var b = budget(1_000_000);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(ab, ba, seed)), "P", 9,
                Map.of("Q", 20L, "R", 9L), true, true, b);
        try {
            while (!work.step()) {}
            check(work.result().feasible(), "default stock policy lost its existing bootstrap path");
            check(work.result().initial().getOrDefault("Q", 0L) == 1L && get(work, "catalystPolicy").equals(CatalystPolicy.STOCK),
                    "default stock policy manufactured extra parallel catalysts or silently changed policy");
            check(get(work, "positiveRefillVerified") == null && !(Boolean) get(work, "positiveRefillTried"),
                    "goal-only proposal bypassed the stock catalyst contract");
            PlanVerifier.verify(work.result()); physical(work.result());
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "stock-policy bootstrap leaked workspace");
    }

    private static void comparisonTimeout() throws Exception {
        var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        var b = new PlanningBudget(1, 131072, 16L << 20, () -> false, () -> {
            boolean comparing = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(f -> f.getClassName().equals(GraphPlanningWork.class.getName()) && f.getMethodName().equals("finishResult")) &&
                    Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f -> f.getClassName().equals(RefillFrontier.Comparison.class.getName()));
            if (comparing) timedOut.set(true);
            return timedOut.get() ? 2_000_000L : 0L;
        });
        var work = new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe("make", Map.of("raw", 1L), Map.of("T", 1L)))),
                "T", 1, Map.of(), false, true, b).catalysts(CatalystPolicy.MINIMAL);
        try {
            while (!work.step()) {}
            check(timedOut.get(), "completed-refill comparison timeout was not exercised");
            var retained = work.result();
            check(retained.conditionalPreview() && retained.missing().equals(Map.of("raw", 1L)),
                    "interrupted comparison lost its first complete incumbent");
            physical(retained);
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "comparison timeout leaked workspace");
    }

    public static void main(String[] args) throws Exception { originalSix(); ordinaryAndNaturalExit(GraphMode.FULL); ordinaryAndNaturalExit(GraphMode.ACYCLIC); noTargetSupplyShortcut(); migrationAndUnfinishedCutoff(); optionalMemoryDecline(); limitedFeasiblePriority(GraphMode.FULL); limitedFeasiblePriority(GraphMode.ACYCLIC); normalizationCutoff(GraphMode.FULL); normalizedFeasibleWins(); goalImportPublication(); defaultStockPolicy(); comparisonTimeout(); System.out.println("PrimaryPositiveRefillTest passed"); }
}
