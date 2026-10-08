package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic background work between quota capture and the next local operation. */
public final class SyncQuotaConcurrencyRegression {
    private static final long CAP = 4_000_000, BACKGROUND = 500_000;

    private static final class Gate implements AutoCloseable {
        final PlanningBudget budget;
        final long work;
        final CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicBoolean fired = new AtomicBoolean();
        final Thread worker;
        volatile Throwable failure;
        Gate(PlanningBudget budget, long work) {
            this.budget = budget;
            this.work = work;
            worker = new Thread(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Gate did not open");
                    budget.charge(work);
                } catch (Throwable error) { failure = error; }
                finally { done.countDown(); }
            }, "unrelated-count-branch");
            worker.start();
        }
        void enter() {
            if (!fired.compareAndSet(false, true)) return;
            start.countDown();
            try {
                if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Background charge stuck");
            } catch (InterruptedException error) { throw new AssertionError(error); }
            if (failure != null && !(failure instanceof PlanningBudget.Exhausted)) throw new AssertionError(failure);
        }
        public void close() {
            start.countDown();
            try { worker.join(10_000); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            if (worker.isAlive()) throw new AssertionError("Worker leaked");
            if (!fired.get()) throw new AssertionError("Missing interference");
        }
    }

    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static PlanningBudget budget(long limit) { return new PlanningBudget(0, limit, 64L << 20, () -> false, System::nanoTime); }

    static PlanStep flow(long background, long limit) {
        var budget = budget(limit);
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("out", recipe("out", Map.of("A", 1L), Map.of("B", 1L)));
        recipes.put("back", recipe("back", Map.of("B", 1L), Map.of("A", 1L)));
        recipes.put("goal", recipe("goal", Map.of("A", 1L), Map.of("T", 1L)));
        PlanStep original = new PlanStep.Sequence(List.of(new PlanStep.Batch("goal", 1), new PlanStep.Batch("out", 1), new PlanStep.Batch("back", 1)));
        try (var gate = new Gate(budget, background)) {
            Map<String, GraphRecipe<String>> gated = new AbstractMap<>() {
                public Set<Entry<String, GraphRecipe<String>>> entrySet() { return recipes.entrySet(); }
                public GraphRecipe<String> get(Object key) { gate.enter(); return recipes.get(key); }
            };
            PlanStep result = PlanFlowPruning.optimize(original, gated, budget);
            if (budget.nodes() < background) throw new AssertionError("Global work not charged");
            if (budget.reservedBytes() != 0) throw new AssertionError("Pruning memory leaked");
            return result;
        }
    }

    static boolean fuel(long background) {
        var budget = budget(CAP);
        var recipes = List.of(
            recipe("return1", Map.of("C1", 1L, "F1", 1L), Map.of("C1", 1L, "T1", 1L)),
            recipe("burn1", Map.of("C1", 1L), Map.of("T1", 1L)),
            recipe("return2", Map.of("C2", 1L, "F2", 1L), Map.of("C2", 1L, "T2", 1L)),
            recipe("burn2", Map.of("C2", 1L), Map.of("T2", 1L)),
            recipe("finish", Map.of("T1", 1L, "T2", 1L), Map.of("G", 1L)));
        var stock = Map.of("C1", 1L, "C2", 1L, "F1", 1L, "F2", 1L);
        try (var model = RecipeCountModel.forShell(recipes, Map.of("G", BigInteger.ONE), stock, Set.of(), budget);
             var gate = new Gate(budget, background)) {
            Collection<GraphRecipe<String>> gated = new AbstractCollection<>() {
                public int size() { return recipes.size(); }
                public Iterator<GraphRecipe<String>> iterator() {
                    var source = recipes.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return source.hasNext(); }
                        public GraphRecipe<String> next() { gate.enter(); return source.next(); }
                    };
                }
            };
            var result = CountRecoveryFuel.compile(model, gated, budget);
            if (budget.nodes() < background) throw new AssertionError("Global work not charged");
            return result != null && result.recipes().size() == 3;
        }
    }

    static int interfaces(long background) throws Exception {
        var budget = budget(CAP);
        var recipes = List.of(
            recipe("start", Map.of("A", 1L), Map.of("B", 1L)),
            recipe("alternate", Map.of("X", 1L), Map.of("B", 1L)),
            recipe("return", Map.of("B", 1L), Map.of("A", 1L, "T", 1L)),
            recipe("chain1", Map.of("R", 1L), Map.of("D", 1L)),
            recipe("chain2", Map.of("D", 1L), Map.of("P", 1L)));
        var stock = Map.of("A", 1L, "B", 1L, "R", 1L);
        try (var gate = new Gate(budget, background)) {
            var gated = new AbstractMap<String, Long>() {
                boolean armed;
                public Set<Entry<String, Long>> entrySet() { return stock.entrySet(); }
                public Long get(Object key) { if (armed) gate.enter(); return stock.get(key); }
                public Long getOrDefault(Object key, Long fallback) { if (armed) gate.enter(); return stock.getOrDefault(key, fallback); }
            };
            try (var model = RecipeCountModel.forShell(recipes, Map.of("T", BigInteger.ONE, "A", BigInteger.ONE, "P", BigInteger.ONE), gated, Set.of(), budget);
                 var execution = new CountExecution<>(model, budget);
                 var branch = new IntegerCountBranch<>(model, execution, "T", 1, gated, Map.of(), Set.of(), true, true, budget, System.nanoTime(), List.of())) {
                gated.armed = true;
                try (var recovery = new CountRecovery<>(branch)) {
                    Field field = CountRecovery.class.getDeclaredField("bodies");
                    field.setAccessible(true);
                    Map<?, ?> bodies = (Map<?, ?>) field.get(recovery);
                    int result = (int) bodies.keySet().stream().filter(id -> id.toString().startsWith("@recovery/interface/")).count();
                    if (budget.nodes() < background) throw new AssertionError("Global work not charged");
                    return result;
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length == 0 || args[0].equals("fixed");
        var plainFlow = flow(0, CAP);
        var concurrentFlow = flow(BACKGROUND, CAP);
        boolean pruning = plainFlow.equals(concurrentFlow);
        if (!(plainFlow instanceof PlanStep.Sequence s) || s.children().size() != 1) throw new AssertionError("Control did not remove redundant turnover: " + plainFlow);
        boolean plainFuel = fuel(0), concurrentFuel = fuel(BACKGROUND);
        if (!plainFuel) throw new AssertionError("Fuel control did not compile");
        int plainInterfaces = interfaces(0), concurrentInterfaces = interfaces(BACKGROUND);
        if (plainInterfaces == 0) throw new AssertionError("Interface control did not discover an open return");
        boolean globalLimit = false;
        try { flow(BACKGROUND, 100_000); }
        catch (PlanningBudget.Exhausted exhausted) { globalLimit = exhausted.limit() == PlanningBudget.Limit.SEARCH_LIMIT; }
        System.out.println("flow_equal=" + pruning + "; fuel=" + plainFuel + "/" + concurrentFuel + "; interfaces=" + plainInterfaces + "/" + concurrentInterfaces + "; global_limit=" + globalLimit);
        if (!globalLimit) throw new AssertionError("Global limit ignored");
        if (fixed && (!pruning || !concurrentFuel || plainInterfaces != concurrentInterfaces)) throw new AssertionError("Unrelated work consumed local quota");
        if (!fixed && (pruning || concurrentFuel || plainInterfaces == concurrentInterfaces)) throw new AssertionError("Baseline failed to expose all three bugs");
    }
}
