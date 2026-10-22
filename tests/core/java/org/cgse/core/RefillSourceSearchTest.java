// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Complete-path refill comparison, independently checked by literal execution. */
public final class RefillSourceSearchTest {
    private static int checks;
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
    private static PlanningBudget budget() { return MissingMaterialExpansionTest.budget(4_000_000); }
    private static List<GraphRecipe<String>> routes(int depth, long demand, boolean differentRaw, boolean coproduct, boolean tool) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (String branch : List.of("X", "Y")) {
            var in = new LinkedHashMap<String, Long>(); in.put(branch + "0", 1L);
            var out = new LinkedHashMap<String, Long>(); out.put("A", 1L);
            if (tool) { in.put("tool", 1L); out.put("tool", 1L); }
            if (coproduct && branch.equals("X")) out.put("B", 1L);
            recipes.add(r("use" + branch, in, out));
            for (int i = 0; i < depth; i++) recipes.add(r(branch + i,
                    Map.of(i + 1 == depth ? differentRaw && branch.equals("Y") ? "other" : "raw" : branch + (i + 1),
                            i + 1 == depth && branch.equals("X") ? 100L : 1L), Map.of(branch + i, 1L)));
        }
        var demandInputs = new LinkedHashMap<String, Long>(); demandInputs.put("A", demand);
        if (coproduct) demandInputs.put("B", demand);
        recipes.add(r("finish", demandInputs, Map.of("G", 1L)));
        return recipes;
    }
    private static GraphPlan<String> solve(List<GraphRecipe<String>> recipes, Map<String, Long> stock, String entry) {
        var compiler = new GraphCompiler<>(recipes); var b = budget(); GraphPlan<String> result;
        if (entry.equals("main")) result = new GraphPlanner<>(compiler).plan("G", 1, stock, false, true, b);
        else if (entry.equals("fallback")) result = GraphFallback.plan(compiler, "G", 1, stock, Set.of(), Map.of(), false, true, b);
        else {
            var finish = recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow();
            try (var expansion = new MissingMaterialExpansion<>(compiler, MissingMaterialExpansionTest.partial(finish, stock),
                    stock, Set.of(), Set.of(), true, false, b)) {
                while (!expansion.step()) {}
                result = expansion.result();
            }
        }
        MissingMaterialExpansionTest.verify(result, stock);
        check(b.reservedBytes() == 0, "Entry workspace leaked");
        return result;
    }
    private static GraphPlan<String> oldPlan(List<GraphRecipe<String>> recipes, Map<String, Long> stock) {
        var finish = recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow();
        long demand = finish.inputs().get("A");
        var steps = List.<PlanStep>of(new PlanStep.Batch("X0", demand),
                new PlanStep.Batch("useX", demand), new PlanStep.Batch("finish", 1));
        var used = recipes.stream().filter(r -> List.of("X0", "useX", "finish").contains(r.id())).toList();
        return RefillWordPruningTest.literalPlan(used, new PlanStep.Sequence(steps), Map.of(), stock, Set.of());
    }
    private static GraphPlan<String> refine(List<GraphRecipe<String>> recipes, GraphPlan<String> original,
                                            Map<String, Long> stock, Set<String> external, Set<String> excluded, boolean force) {
        var b = budget(); GraphPlan<String> result;
        try (var work = new RefillSourceSearch<>(new GraphCompiler<>(recipes), original, stock, external, excluded, force, false, b)) {
            while (!work.step()) {}
            result = work.result();
        }
        check(b.reservedBytes() == 0, "Source workspace leaked");
        RefillWordPruningTest.literalVerify(result, stock, external);
        for (var e : result.missingExact().entrySet())
            check(e.getValue().compareTo(original.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Missing bill regressed");
        return result;
    }
    public static void main(String[] args) throws Exception {
        paths(); externalConfigurations(); combinedChoices(); restrictions(); nestedScope(); cyclicPreferences(); exactCounts(); randomPaths(); lifecycle(); publishedLimits(); scheduler(false); scheduler(true);
        System.out.println("RefillSourceSearchTest: assertions=" + checks);
    }
    private static void paths() {
        for (int depth : new int[]{1, 3, 8}) for (long demand : new long[]{1, 7, 31})
            for (boolean reverse : List.of(false, true)) for (String entry : List.of("expand", "fallback", "main")) {
                var recipes = routes(depth, demand, false, false, false); if (reverse) Collections.reverse(recipes);
                var answer = solve(recipes, Map.of(), entry);
                check(answer.missing().equals(Map.of("raw", demand)), entry + "/" + depth + "/" + demand + "/" + reverse + ": " + answer.missing());
                check(answer.missingBoundary("raw").source() == GraphPlan.MissingSource.TERMINAL_UNDER_CURRENT_COVERAGE, "Terminal provenance");
            }
        for (boolean tool : List.of(false, true)) for (long held : new long[]{0, 1, 5}) {
            var recipes = routes(1, 7, false, false, tool);
            Map<String, Long> stock = tool ? Map.of("raw", held, "tool", 1L) : Map.of("raw", held);
            var answer = solve(recipes, stock, "fallback");
            check(answer.missing().equals(Map.of("raw", 7 - held)), "Stock/tool changed terminal normalization");
            if (tool) check(answer.initial().get("tool") == 1, "Returned tool was multiplied");
        }
        var recipes = routes(1, 1, true, false, false);
        var original = oldPlan(recipes, Map.of());
        check(refine(recipes, original, Map.of(), Set.of(), Set.of(), true) == original, "Incomparable raw materials changed priority");
        recipes = routes(1, 3, false, true, false); original = oldPlan(recipes, Map.of());
        check(refine(recipes, original, Map.of(), Set.of(), Set.of(), true) == original, "Local saving discarded useful coproducts");
        var direct = List.of(r("finish", Map.of("raw", 100L), Map.of("G", 1L)),
                r("alternative", Map.of("Y", 1L), Map.of("G", 1L)), r("makeY", Map.of("raw", 1L), Map.of("Y", 1L)));
        for (String entry : List.of("expand", "fallback", "main"))
            check(solve(direct, Map.of(), entry).missing().equals(Map.of("raw", 1L)), "Single-batch target skipped source normalization: " + entry);
        for (int depth : new int[]{2, 4, 17}) for (String entry : List.of("expand", "fallback", "main")) {
            var unequal = routes(1, 3, false, false, false);
            unequal.set(3, r("Y0", Map.of("Y1", 1L), Map.of("Y0", 1L)));
            for (int i = 1; i < depth; i++) unequal.add(r("Y" + i,
                    Map.of(i + 1 == depth ? "raw" : "Y" + (i + 1), 1L), Map.of("Y" + i, 1L)));
            check(solve(unequal, Map.of(), entry).missing().equals(Map.of("raw", 3L)), "First-ready rank hid deeper route: " + entry + "/" + depth);
        }
    }
    private static void externalConfigurations() {
        for (boolean reusable : List.of(false, true)) for (boolean externalConfig : List.of(false, true)) {
            var recipes = routes(1, 3, false, false, false);
            var configured = recipes.get(0);
            var slots = new ArrayList<>(configured.slots());
            slots.add(new GraphRecipe.Slot<>("configuration", 1, 1, true, reusable));
            var outputs = new LinkedHashMap<>(configured.outputs());
            if (reusable) outputs.put("configuration", 1L);
            recipes.set(0, new GraphRecipe<>(configured.id(), configured.binding(), slots, outputs));
            var original = oldPlan(recipes, Map.of("configuration", 3L));
            var result = refine(recipes, original, Map.of("configuration", 3L),
                    externalConfig ? Set.of("configuration") : Set.of(), Set.of(), true);
            if (externalConfig || reusable)
                check(result.missingExact().equals(Map.of("raw", BigInteger.valueOf(3))), "External/read-only configuration blocked a valid source improvement");
            else check(result == original, "Finite per-push configuration lost its guarded scope");
        }
    }
    private static void restrictions() {
        var recipes = routes(1, 1, false, false, false); var original = oldPlan(recipes, Map.of());
        check(refine(recipes, original, Map.of(), Set.of(), Set.of("useY"), true) == original, "Used excluded source");
        check(refine(recipes, original, Map.of(), Set.of("Y0"), Set.of(), true).feasible(), "External source ignored");
        var incompatible = new LinkedHashMap<>(original.recipes()); incompatible.put("useY", r("useY", Map.of("foreign", 1L), Map.of("A", 1L)));
        var bound = GraphPlan.ownedRefill(original, original.steps(), incompatible, original.initialExact(), original.missingExact(), original.result(), 0);
        check(refine(recipes, bound, Map.of(), Set.of(), Set.of(), true) == bound, "Changed old physical binding");
        var conditional = GraphPlan.ownedRefill(original, original.steps(), original.recipes(), original.initialExact(), original.missingExact(),
                GraphPlan.Result.INCOMPLETE_MODEL, 0);
        var answer = refine(recipes, conditional, Map.of(), Set.of(), Set.of(), true);
        check(answer.conditionalPreview() && answer.seedOptimality() == null, "Invented coverage or optimality");
        var seeded = new GraphPlan<>("G", 1, true, original.steps(), original.recipes(), Map.of("raw", 102L),
                Map.of("raw", 2L), Map.of("raw", 102L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        answer = refine(recipes, seeded, Map.of(), Set.of(), Set.of(), true);
        check(answer.missing().equals(Map.of("raw", 3L)) && answer.seeds().equals(seeded.seeds()), "Spent final seeds");
        var changed = new ArrayList<>(recipes);
        changed.set(1, r("X0", Map.of("raw", 100L), Map.of("X0", 1L, "G", 9L)));
        original = oldPlan(changed, Map.of());
        answer = refine(changed, original, Map.of("G", 100L), Set.of(), Set.of(), true);
        check(SequenceSummary.of(answer.steps(), answer.recipes()).delta("G").compareTo(BigInteger.TEN) >= 0, "Lost forced target credit");
        changed = new ArrayList<>(recipes);
        changed.set(0, new GraphRecipe<>("useX", "useX", List.of(new GraphRecipe.Slot<>("X0", 1),
                new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("A", 1L)));
        original = oldPlan(changed, Map.of());
        check(refine(changed, original, Map.of(), Set.of(), Set.of(), true) == original, "Changed per-push word");
        // A cyclic alternate does not eliminate its real startup loan.
        changed = new ArrayList<>(recipes); changed.set(3, r("Y0", Map.of("A", 1L), Map.of("Y0", 1L)));
        original = oldPlan(changed, Map.of());
        check(refine(changed, original, Map.of(), Set.of(), Set.of(), true) == original, "Turned a closed cycle into a free route");
        // An unused but captured per-push binding can be late in the map.
        var bindings = new LinkedHashMap<>(original.recipes());
        bindings.put("late-config", new GraphRecipe<>("late-config", "late-config",
                List.of(new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("unused", 1L)));
        var guarded = GraphPlan.ownedRefill(original, original.steps(), bindings, original.initialExact(), original.missingExact(), original.result(), 0);
        check(refine(recipes, guarded, Map.of(), Set.of(), Set.of(), true) == guarded, "Skipped late per-push binding");
    }
    @SuppressWarnings("unchecked")
    private static void nestedScope() throws Exception {
        var compiler = new GraphCompiler<>(routes(1, 1, false, false, false)); var b = budget();
        var constructor = GraphPlanningWork.class.getDeclaredConstructor(GraphCompiler.class, Object.class, long.class, Map.class,
                Set.class, Map.class, boolean.class, boolean.class, PlanningBudget.class, Set.class, int.class);
        constructor.setAccessible(true);
        var work = (GraphPlanningWork<String>) constructor.newInstance(compiler, "G", 1L, Map.of(), Set.of(), Map.of(), false, true, b, Set.of(), 1);
        try {
            while (!work.step()) {}
            MissingMaterialExpansionTest.verify(work.result(), Map.of());
            check(!b.diagnostics().contains("refill_sources"), "A seed subrequest spent later search allowance on source normalization");
            check(work.result().missing().equals(Map.of("raw", 100L)), "Unexpected nested source policy");
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "Nested workspace");
    }
    private static void cyclicPreferences() {
        var recipes = List.of(r("finish", Map.of("A", 1L), Map.of("G", 1L)),
                r("recycle", Map.of("G", 2L), Map.of("A", 1L)));
        var compiler = new GraphCompiler<>(recipes); var b = budget();
        var goals = new GraphPlan<>("G", 1, false, new PlanStep.Sequence(List.of()), Map.of(),
                Map.of("G", 1L), Map.of(), Map.of("G", 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        try (var order = new RefillProducerOrder<>(compiler, Set.of("G"), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            try (var work = MissingMaterialExpansion.sourceAlternative(compiler, goals, Map.of(), Set.of(), Set.of(), true, false, b,
                    order, BigInteger.ONE, Map.of("G", "finish", "A", "recycle"))) {
                while (!work.step()) check(b.nodes() < 1000, "Cyclic preference renewed its rank exception");
                check(work.prefixProduction().signum() < 0 && work.result() == goals, "Closed turnover was not rejected");
            }
        }
        check(b.reservedBytes() == 0, "Closed trial workspace");
    }
    private static void combinedChoices() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var used = new ArrayList<GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>();
        var finishInputs = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 3; i++) {
            String a = "A" + i, x = "X" + i, y = "Y" + i, raw = "raw" + i;
            var make = r("make" + x, Map.of(raw, 100L), Map.of(x, 1L));
            var use = r("use" + x, Map.of(x, 1L), Map.of(a, 1L));
            recipes.addAll(List.of(make, use, r("make" + y, Map.of(raw, 1L), Map.of(y, 1L)), r("use" + y, Map.of(y, 1L), Map.of(a, 1L))));
            used.addAll(List.of(make, use)); steps.add(new PlanStep.Batch(make.id(), 1)); steps.add(new PlanStep.Batch(use.id(), 1));
            finishInputs.put(a, 1L);
        }
        var finish = r("finish", finishInputs, Map.of("G", 1L)); recipes.add(finish); used.add(finish); steps.add(new PlanStep.Batch("finish", 1));
        var original = RefillWordPruningTest.literalPlan(used, new PlanStep.Sequence(steps), Map.of(), Map.of(), Set.of());
        var answer = refine(recipes, original, Map.of(), Set.of(), Set.of(), true);
        check(answer.missing().equals(Map.of("raw0", 1L, "raw1", 1L, "raw2", 1L)), "Accepted source choices did not compose");
        // New supply is the objective; fully owned supplies can cost more.
        recipes = new ArrayList<>(routes(1, 1, false, false, false));
        recipes.set(3, r("Y0", Map.of("held", 100L), Map.of("Y0", 1L)));
        original = oldPlan(recipes, Map.of("held", 100L));
        answer = refine(recipes, original, Map.of("held", 100L), Set.of(), Set.of(), true);
        check(answer.feasible() && answer.initial().get("held") == 100, "New-supply objective confused with total stock use");
    }
    private static void exactCounts() {
        var recipes = routes(1, 1, false, false, false);
        BigInteger n = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
        var used = MissingMaterialExpansionTest.index(recipes);
        var steps = new PlanStep.Sequence(List.of(PlanStep.batch("X0", n), PlanStep.batch("useX", n), PlanStep.batch("finish", n)));
        // A large final seed makes the goal itself larger than long without flattening repeats.
        var plan = new GraphPlan<>("G", Long.MAX_VALUE, true, steps, used, Map.of("raw", n.multiply(BigInteger.valueOf(100))),
                Map.of("G", Long.MAX_VALUE), Map.of("raw", n.multiply(BigInteger.valueOf(100))), GraphPlan.Result.MISSING_INPUT, 0, 0);
        // Only actual old bindings belong to the retained word.
        used.remove("useY"); used.remove("Y0");
        plan = new GraphPlan<>("G", Long.MAX_VALUE, true, steps, used, plan.initialExact(), plan.seeds(), plan.missingExact(), plan.result(), 0, 0);
        var b = budget();
        try (var work = new RefillSourceSearch<>(new GraphCompiler<>(recipes), plan, Map.of(), Set.of(), Set.of(), true, false, b)) {
            while (!work.step()) {}
            var answer = work.result();
            check(answer.missingExact().equals(Map.of("raw", n)), "Clipped exact count or forced production");
            PlanVerifier.verify(GraphPlan.ownedRefill(answer, answer.steps(), answer.recipes(), answer.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE, 0));
        }
        check(b.reservedBytes() == 0, "Exact count workspace");
    }
    private static void randomPaths() {
        var random = new Random(20261020);
        for (int trial = 0; trial < 80; trial++) {
            long demand = 1 + random.nextInt(12), cheap = 1 + random.nextInt(6), costly = cheap + 1 + random.nextInt(20);
            var recipes = routes(1, demand, false, false, false);
            recipes.set(1, r("X0", Map.of("raw", costly), Map.of("X0", 1L)));
            recipes.set(3, r("Y0", Map.of("raw", cheap), Map.of("Y0", 1L)));
            var stock = Map.of("raw", (long) random.nextInt((int) (cheap * demand)));
            var original = oldPlan(recipes, stock);
            Collections.shuffle(recipes, random);
            var answer = refine(recipes, original, stock, Set.of(), Set.of(), true);
            // Independent conservation: every A costs at least cheap raw.
            check(answer.missing().equals(Map.of("raw", cheap * demand - stock.get("raw"))), "Random terminal bound " + trial);
        }
    }
    private static String phase(Object work) throws Exception {
        var field = work.getClass().getDeclaredField("phase"); field.setAccessible(true); return field.get(work).toString();
    }
    private static void lifecycle() throws Exception {
        var recipes = routes(1, 3, false, false, false); var original = oldPlan(recipes, Map.of());
        for (String stop : List.of("ORIGINAL", "RANK", "EXPAND", "COMPARE"))
            for (String mode : List.of("work", "time", "memory", "cancel")) {
                var cancelled = new AtomicBoolean(); var clock = new AtomicLong();
                var b = new PlanningBudget(1, 100_000, 1L << 20, cancelled::get, clock::get); long held = 0;
                try (var work = new RefillSourceSearch<>(new GraphCompiler<>(recipes), original, Map.of(), Set.of(), Set.of(), true, false, b)) {
                    for (int i = 0; !phase(work).equals(stop); i++) check(i < 1000 && !work.step(), "Never reached " + stop);
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancelled.set(true);
                    }
                    try {
                        while (!work.step()) {}
                        check(!mode.equals("cancel"), "Swallowed cancellation");
                        MissingMaterialExpansionTest.verify(work.result(), Map.of());
                        check(work.result() == original || mode.equals("memory"), "Cutoff discarded incumbent");
                    } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                } finally { b.release(held); }
                check(b.reservedBytes() == 0, "Interrupted workspace " + stop + "/" + mode);
            }
    }
    private static void publishedLimits() throws Exception {
        var recipes = routes(1, 3, false, false, false); var original = oldPlan(recipes, Map.of());
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var cancelled = new AtomicBoolean(); var clock = new AtomicLong();
            var b = new PlanningBudget(1, 100_000, 1L << 20, cancelled::get, clock::get); long held = 0;
            try (var work = new RefillSourceSearch<>(new GraphCompiler<>(recipes), original, Map.of(), Set.of(), Set.of(), true, false, b)) {
                while (work.result() == original) check(!work.step(), "No improved publication");
                var retained = work.result();
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancelled.set(true);
                }
                try { while (!work.step()) {} check(!mode.equals("cancel") && work.result() == retained, "Lost completed improvement"); }
                catch (CancellationException expected) { check(mode.equals("cancel") && work.result() == retained, "Cancellation changed publication"); }
                MissingMaterialExpansionTest.verify(retained, Map.of());
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Published cutoff workspace");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var recipes = routes(1, 3, false, false, false); var original = oldPlan(recipes, Map.of());
        var cancelled = new AtomicBoolean(); var b = new PlanningBudget(0, 100_000, 1L << 20, cancelled::get, System::nanoTime);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var work = new RefillSourceSearch<>(new GraphCompiler<>(recipes), original, Map.of(), Set.of(), Set.of(), true, false, b);
             var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                boolean started;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (!started) {
                        started = true; entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Gate"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    while (slice.nextUncharged()) if (work.step()) return true;
                    return false;
                }
                public GraphPlan<String> result() { return work.result(); }
            }, b);
            check(entered.await(5, TimeUnit.SECONDS), "Worker did not start");
            var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                public boolean advance(PlanningScheduler.Slice slice) { if (cancel) cancelled.set(true); return true; }
                public Long result() { return b.nodes(); }
            }, budget());
            // A slice can start a child just below its 64-work boundary. Each
            // child yields in at most 64 visits, so at most one turn overshoots.
            release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 128, "Source scan monopolized worker");
            try { check(future.get(10, TimeUnit.SECONDS).missing().equals(Map.of("raw", 3L)) && !cancel, "Scheduled result"); }
            catch (CancellationException expected) { check(cancel, "Unexpected cancel"); }
            catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Worker failure"); }
        } finally { release.countDown(); }
        check(b.reservedBytes() == 0, "Scheduled workspace");
    }
}
