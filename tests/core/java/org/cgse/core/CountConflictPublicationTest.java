// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Oversized publication batches must not churn the coordinator cache on every yield. */
public final class CountConflictPublicationTest {
    private static final Method MERGE;
    static {
        try { MERGE = IntegerCountSearch.class.getDeclaredMethod("merge", List.class, boolean.class); MERGE.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void main(String[] args) throws Exception {
        for (int mode : new int[] {0, 2}) for (boolean refuse : new boolean[] {false, true}) repeatedYields(mode, refuse);
        cancelledPublication();
        System.out.println("Conflict publication: oversized batches, 400 unchanged yields, later proofs, auxiliary scope, optional refusal and cancellation passed");
    }

    private static void repeatedYields(int mode, boolean refuse) throws Exception {
        var budget = new PlanningBudget(0, 20_000_000, 64L << 20, () -> false, System::nanoTime);
        IntegerCountBranch<String> branch = null;
        try (var search = search(budget)) {
            Deque<IntegerCountBranch<String>> pending = pending(search);
            branch = pending.removeFirst();
            branch.auxiliaryMode = mode;
            var pool = (CountConflictPool) field(IntegerCountSearch.class, "choiceConflicts").get(search);
            var batch = new ArrayList<CountConflict>();
            // Every recipe has one unit of exclusive raw stock. Therefore
            // each xi >= 2 is a forbidden conjunction in this original model.
            for (int i = 0; i < 257; i++) batch.add(aboveStock(i));
            branch.learnedChoices.addAll(batch);
            long lease = refuse ? budget.availableBytes() : 0;
            check(budget.tryReserve(lease), "Refusal setup failed");
            try { MERGE.invoke(search, List.of(branch), true); }
            finally { budget.release(lease); }
            if (mode == 0 && !refuse) check(pool.snapshot().size() == 128 && number(pool, "evicted") > 0, "No actual cache pressure");
            else check(pool.isEmpty(), "Refused/auxiliary publication escaped its scope");
            long published = number(pool, "learned"), evicted = number(pool, "evicted"), work = budget.searchWork();
            var before = new HashSet<>(pool.snapshot());
            for (int i = 0; i < 100; i++) {
                check(pending.removeFirst() == branch, "Retained branch ownership changed");
                MERGE.invoke(search, List.of(branch), true);
                check(pending.size() == 1, "Duplicate retained continuation");
            }
            check(number(pool, "learned") == published && number(pool, "evicted") == evicted,
                    "Unchanged branch reinserted its evicted conflict batch");
            check(budget.searchWork() == work && new HashSet<>(pool.snapshot()).equals(before), "An empty publication redid cache work");
            check(branch.learnedChoices.isEmpty(), "Published outbox retained stale clauses");
            if (mode == 0) {
                // A genuinely new publication may offer an earlier evicted
                // fact again; there is no permanent global rejection cache.
                CountConflict next = batch.stream().filter(c -> !pool.snapshot().contains(c)).findFirst().orElseThrow();
                branch.learnedChoices.add(next);
                pending.removeFirst();
                MERGE.invoke(search, List.of(branch), true);
                check(pool.snapshot().contains(next), "Newly learned fact was permanently suppressed");
                check(number(pool, "learned") == published + 1, "Later publication not processed once");
            }
        } finally { if (branch != null) branch.close(); }
        check(budget.reservedBytes() == 0, "Publication lifetime leaked memory");
    }

    private static void cancelledPublication() throws Exception {
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 20_000_000, 64L << 20, cancelled::get, System::nanoTime);
        IntegerCountBranch<String> branch = null;
        try (var search = search(budget)) {
            branch = pending(search).removeFirst();
            branch.learnedChoices.add(aboveStock(0));
            cancelled.set(true);
            try { MERGE.invoke(search, List.of(branch), true); throw new AssertionError("Cancellation ignored"); }
            catch (InvocationTargetException failure) { if (!(failure.getCause() instanceof CancellationException)) throw failure; }
            check(!branch.learnedChoices.isEmpty(), "Unfinished publication was marked delivered");
        } finally { if (branch != null) branch.close(); }
        check(budget.reservedBytes() == 0, "Cancelled publication leaked memory");
    }

    private static IntegerCountSearch<String> search(PlanningBudget budget) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 257; i++) {
            String raw = "raw" + i;
            stock.put(raw, 1L);
            recipes.add(new GraphRecipe<>("r" + i, "r" + i, List.of(new GraphRecipe.Slot<>(raw, 1)), Map.of("goal", 1L)));
        }
        return new IntegerCountSearch<>(new GraphCompiler<>(recipes), "goal", 1, stock, Map.of(), Set.of(), Set.of(),
                false, true, budget, 0, null, false);
    }

    private static CountConflict aboveStock(int id) {
        return new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), BigInteger.TWO.negate())));
    }

    @SuppressWarnings("unchecked")
    private static Deque<IntegerCountBranch<String>> pending(IntegerCountSearch<String> search) throws Exception {
        return (Deque<IntegerCountBranch<String>>) field(IntegerCountSearch.class, "pending").get(search);
    }

    private static long number(CountConflictPool pool, String name) throws Exception { return field(CountConflictPool.class, name).getLong(pool); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
