// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wide refill witnesses, real scheduler handoffs, and interrupted private builders. */
public final class RefillContinuationTest {
    private static int checks;
    private static final Field PHASE;
    static {
        try { PHASE = MissingMaterialExpansion.class.getDeclaredField("phase"); PHASE.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static String phase(MissingMaterialExpansion<?> expansion) {
        try { return PHASE.get(expansion).toString(); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    static List<GraphRecipe<String>> alternatives(int count) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < count; i++) recipes.add(r("r" + i, Map.of("raw", i == count - 1 ? 1L : 2L), Map.of("A", 1L)));
        recipes.add(r("finish", Map.of("A", 1L), Map.of("G", 1L))); return recipes;
    }
    static List<GraphRecipe<String>> wide(int size) {
        var costly = new LinkedHashMap<String, Long>(); var cheap = new LinkedHashMap<String, Long>();
        for (int i = 0; i < size; i++) { costly.put("raw" + i, 2L); cheap.put("raw" + i, 1L); }
        // A returned tool occurs in both halves of the support union.
        costly.put("tool", 1L); cheap.put("tool", 1L);
        var outputs = new LinkedHashMap<String, Long>(); outputs.put("A", 1L); outputs.put("tool", 1L);
        return List.of(r("costly", costly, outputs), r("cheap", cheap, outputs), r("finish", Map.of("A", 1L), Map.of("G", 1L)));
    }
    static List<GraphRecipe<String>> chain(int size) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size; i++) recipes.add(r("r" + i, Map.of("k" + (i + 1), 1L), Map.of("k" + i, 1L)));
        recipes.add(r("finish", Map.of("k0", 1L), Map.of("G", 1L))); return recipes;
    }
    static GraphPlan<String> suffix(List<GraphRecipe<String>> recipes) {
        return MissingMaterialExpansionTest.partial(recipes.get(recipes.size() - 1), Map.of());
    }
    private static PlanningBudget budget(long work, long bytes, AtomicBoolean cancelled) {
        return new PlanningBudget(0, work, bytes, cancelled::get, System::nanoTime);
    }
    private static MissingMaterialExpansion<String> expansion(GraphCompiler<String> compiler, GraphPlan<String> suffix, PlanningBudget budget) {
        return new MissingMaterialExpansion<>(compiler, suffix, Map.of(), Set.of(), Set.of(), true, false, budget);
    }
    private static boolean step(MissingMaterialExpansion<String> expansion, PlanningBudget budget) {
        long before = budget.nodes(); String phase = phase(expansion); var retained = expansion.result(); boolean done = expansion.step();
        // The general final verifier has its own continuations. This assertion
        // covers the refill builder and rank pass, not unrelated verifier loops.
        if (!phase.equals("VERIFY")) check(budget.nodes() - before <= 64, "Unbounded refill phase " + phase);
        if (!done && expansion.result() != null) {
            check(phase(expansion).equals("DONE") || phase(expansion).equals("PRUNE"), "Partial bill became observable");
            // Optional whole-word refinement retains the completed bill. Its
            // visibility must not expose any partially rewritten requirements.
            if (retained != expansion.result()) MissingMaterialExpansionTest.verify(expansion.result(), Map.of());
        }
        return done;
    }
    private static GraphPlan<String> solve(List<GraphRecipe<String>> recipes) {
        var b = budget(4_000_000, 64L << 20, new AtomicBoolean()); GraphPlan<String> plan;
        try (var e = expansion(new GraphCompiler<>(recipes), suffix(recipes), b)) {
            while (!step(e, b)) {} plan = e.result();
            var initial = new LinkedHashMap<>(plan.initialExact()); var missing = new LinkedHashMap<>(plan.missingExact());
            var counts = new LinkedHashMap<>(plan.patternTimesExact());
            check(e.step() && e.result() == plan, "Completed publication restarted"); e.close(); e.close();
            check(plan.initialExact().equals(initial) && plan.missingExact().equals(missing) && plan.patternTimesExact().equals(counts), "Close mutated transferred builders");
        }
        check(b.reservedBytes() == 0, "Completed refill retained workspace");
        MissingMaterialExpansionTest.verify(plan, Map.of()); return plan;
    }
    public static void main(String[] args) throws Exception {
        semantics(); isolation(); lifecycle();
        for (String phase : List.of("SOURCE", "SUPPORT", "COMPARE", "APPLY", "STEPS", "DEFICITS")) {
            scheduler(phase, false); scheduler(phase, true);
        }
        System.out.println("RefillContinuationTest: assertions=" + checks);
    }
    private static void semantics() {
        var plan = solve(alternatives(12000));
        check(plan.missing().equals(Map.of("raw", 1L)), "Stopped before the best late source");
        check(plan.patternTimesExact().keySet().equals(Set.of("r11999", "finish")), "Changed stable source comparison");
        plan = solve(wide(4096));
        check(plan.missing().size() == 4097 && plan.missing().values().stream().allMatch(n -> n == 1), "Wide support comparison truncated");
        check(plan.patternTimesExact().keySet().equals(Set.of("cheap", "finish")), "Did not compare the full requirement vector");
        plan = solve(chain(4096));
        check(plan.missing().equals(Map.of("k4096", 1L)) && plan.patternTimesExact().size() == 4097, "Prefix publication lost execution steps");
        var finish = r("finish", Map.of("A", 1L), Map.of("G", 1L));
        var original = MissingMaterialExpansionTest.partial(finish, Map.of());
        var b = budget(100_000, 1L << 20, new AtomicBoolean());
        try (var e = expansion(new GraphCompiler<>(List.of(finish)), original, b)) {
            while (!step(e, b)) {} check(e.result() == original, "No-prefix result was rebuilt");
        }
        check(b.reservedBytes() == 0, "No-prefix exit leaked");
    }
    private static void immutable(Runnable change) {
        try { change.run(); throw new AssertionError("Published collection is mutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void isolation() {
        var plan = solve(wide(129));
        immutable(() -> plan.initialExact().put("raw0", BigInteger.TEN));
        immutable(() -> plan.missingExact().entrySet().iterator().next().setValue(BigInteger.TEN));
        immutable(() -> plan.recipes().clear());
        var sequence = (PlanStep.Sequence) plan.steps();
        immutable(() -> sequence.children().clear());
        immutable(() -> sequence.children().set(0, new PlanStep.Batch("bad", 1)));
        immutable(() -> { var it = sequence.children().iterator(); it.next(); it.remove(); });
        var children = new ArrayList<PlanStep>(sequence.children());
        var publicSequence = new PlanStep.Sequence(children); children.clear();
        check(publicSequence.equals(sequence), "Public sequence constructor stopped copying caller lists");
        try { new PlanStep.Sequence(Arrays.asList((PlanStep) null)); throw new AssertionError("Public sequence accepted null"); }
        catch (NullPointerException expected) { checks++; }
        var initial = new LinkedHashMap<>(plan.initialExact()); var missing = new LinkedHashMap<>(plan.missingExact());
        var recipes = new LinkedHashMap<>(plan.recipes()); var seeds = new LinkedHashMap<String, Long>(); seeds.put("seed", 1L);
        var copied = new GraphPlan<>("G", 1, true, plan.steps(), recipes, initial, seeds, missing, plan.result(), 0, 0);
        initial.clear(); missing.clear(); recipes.clear(); seeds.clear();
        check(copied.initialExact().equals(plan.initialExact()) && copied.missingExact().equals(plan.missingExact()) &&
                copied.recipes().equals(plan.recipes()) && copied.seeds().equals(Map.of("seed", 1L)), "Public plan retained caller maps");
    }
    private static void lifecycle() {
        var recipes = wide(193); var compiler = new GraphCompiler<>(recipes); var suffix = suffix(recipes);
        int boundaries = 0; var phases = new HashSet<String>();
        var b = budget(1_000_000, 4L << 20, new AtomicBoolean());
        try (var e = expansion(compiler, suffix, b)) {
            do { phases.add(phase(e)); boundaries++; } while (!step(e, b));
        }
        check(phases.containsAll(Set.of("SUPPORT", "COMPARE", "APPLY", "DEFICITS", "VERIFY")), "Fixture missed suspended candidate/publication phases");
        for (int stop = 0; stop < boundaries; stop++) {
            var cancel = new AtomicBoolean(); b = budget(1_000_000, 4L << 20, cancel);
            try (var e = expansion(compiler, suffix, b)) {
                for (int i = 0; i < stop; i++) check(!step(e, b), "Cancellation boundary passed completion");
                var retained = e.result();
                cancel.set(true);
                try { e.step(); throw new AssertionError("Ignored cancellation"); }
                catch (CancellationException expected) { check(e.result() == retained, "Cancellation published a partial bill"); }
                e.close();
            }
            check(b.reservedBytes() == 0, "Cancelled retained candidates leaked");
        }
        for (long work : new long[] {1, 64, 512, 2048, 4096, 8192, 32768}) {
            b = budget(work, 4L << 20, new AtomicBoolean());
            try (var e = expansion(compiler, suffix, b)) {
                try { while (!step(e, b)) {} MissingMaterialExpansionTest.verify(e.result(), Map.of()); }
                catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.SEARCH_LIMIT && e.result() == null, "Work limit published unfinished refill"); }
            }
            check(b.reservedBytes() == 0, "Work limit leaked proposals");
        }
        for (long bytes : new long[] {1, 512, 8192, 65536, 100_000, 128_000, 160_000, 256_000, 1L << 20}) {
            b = budget(1_000_000, bytes, new AtomicBoolean());
            try (var e = expansion(compiler, suffix, b)) {
                try { while (!step(e, b)) {} MissingMaterialExpansionTest.verify(e.result(), Map.of()); }
                catch (PlanningBudget.Exhausted expected) { check(expected.limit() == PlanningBudget.Limit.MEMORY_LIMIT && e.result() == null, "Memory limit published unfinished refill"); }
                e.close();
            }
            check(b.reservedBytes() == 0 && b.peakBytes() <= bytes, "Memory limit leaked proposal workspace");
        }
    }
    private static void scheduler(String at, boolean cancel) throws Exception {
        var recipes = at.equals("SOURCE") ? alternatives(3000) : at.equals("STEPS") ? chain(2048) : wide(2048);
        var cancelled = new AtomicBoolean(); var b = budget(4_000_000, 32L << 20, cancelled);
        try (var e = expansion(new GraphCompiler<>(recipes), suffix(recipes), b)) {
            for (int i = 0; !phase(e).equals(at); i++) check(i < 100_000 && !e.step(), "Never reached scheduler fixture " + at);
            long before = b.nodes(); var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) {
                            started = true; entered.countDown();
                            try { if (!released.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test handoff timed out"); }
                            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                        }
                        while (slice.nextUncharged()) if (e.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return e.result(); }
                    public void close() { e.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Heavy refill did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long observed;
                    public boolean advance(PlanningScheduler.Slice slice) { observed = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return observed; }
                }, budget(1000, 1L << 20, new AtomicBoolean()));
                released.countDown();
                check(marker.get(10, TimeUnit.SECONDS) <= 64, "Refill " + at + " monopolized the scheduler");
                try {
                    var plan = future.get(10, TimeUnit.SECONDS); check(!cancel && plan != null, "Cancelled refill returned a plan");
                    MissingMaterialExpansionTest.verify(plan, Map.of());
                } catch (CancellationException expected) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException failure) { check(cancel && failure.getCause() instanceof CancellationException, "Unexpected scheduler failure " + failure); }
            } finally { released.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduled refill leaked workspace");
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
