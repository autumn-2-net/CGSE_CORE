package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

public final class RegionWorkAudit {
    private static int checks;
    private static final ExecutorService[] workers = {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
    record Result(RegionSelection.Choice<String> choice, int steps, long ownWork) {}

    static List<GraphRecipe<String>> cycle(int size, int yield) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size; i++) recipes.add(new GraphRecipe<>("r" + i, "r" + i,
                List.of(new GraphRecipe.Slot<>("k" + i, 1)), Map.of("k" + ((i + 1) % size), (long) (i + 1 == size ? yield : 1))));
        return recipes;
    }

    static Result run(boolean counts, List<GraphRecipe<String>> recipes, long amount, boolean preserve, boolean force, int noise) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000_000_000L, 128L << 20, () -> false, System::nanoTime);
        var stock = Map.of("k0", 1L);
        var demand = Map.of("k0", BigInteger.valueOf(amount));
        var selection = counts ? null : new RegionSelection<>(new GraphCompiler.Region<>(recipes, true), demand, stock,
                "k0", amount, preserve, force, Set.of(), budget);
        var counted = counts ? new RegionCounts<>(recipes, demand, stock, Set.of(), "k0", amount, force, preserve, budget) : null;
        int steps = 0;
        long ownWork = 0;
        try {
            while (true) {
                Callable<long[]> step = () -> {
                    long before = budget.threadWork();
                    boolean done = counts ? counted.step() : selection.step();
                    return new long[]{done ? 1 : 0, budget.threadWork() - before};
                };
                long[] next = noise == 2 ? workers[steps & 1].submit(step).get() : step.call();
                ownWork += next[1];
                steps++;
                if (next[0] != 0) break;
                if (steps > 1_000_000) throw new AssertionError("nontermination");
                if (noise == 1) budget.charge(350_000);
                if (noise == 2) workers[steps & 1].submit(() -> budget.charge(350_000)).get();
            }
            return new Result(counts ? counted.result() : selection.result(), steps, ownWork);
        } finally {
            if (counts) counted.close(); else selection.close();
            check(budget.reservedBytes() == 0, "lease balance");
        }
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static void interrupted(boolean counts, boolean cancelled) {
        var budget = new PlanningBudget(0, cancelled ? 1_000_000 : 50, 128L << 20, () -> false, System::nanoTime);
        budget.reserve(128);
        var recipes = cycle(7, 2);
        var demand = Map.of("k0", BigInteger.valueOf(1000));
        var stock = Map.of("k0", 1L);
        var selection = counts ? null : new RegionSelection<>(new GraphCompiler.Region<>(recipes, true), demand, stock,
                "k0", 1000, true, true, Set.of(), budget);
        var counted = counts ? new RegionCounts<>(recipes, demand, stock, Set.of(), "k0", 1000, true, true, budget) : null;
        boolean stopped = false;
        try {
            for (int step = 0; step < 100; step++) {
                if (step == 3 && cancelled) budget.cancel();
                if (counts ? counted.step() : selection.step()) break;
                if (!cancelled) budget.charge(8);
            }
        } catch (CancellationException failure) {
            check(cancelled, "unexpected cancellation");
            stopped = true;
        } catch (PlanningBudget.Exhausted failure) {
            check(!cancelled, "unexpected budget limit");
            stopped = true;
        } finally {
            if (counts) { counted.close(); counted.close(); }
            else { selection.close(); selection.close(); }
            check(budget.reservedBytes() == 128, "interrupted lease balance");
            budget.release(128);
        }
        check(stopped, "global stop was ignored");
    }

    public static void main(String[] args) throws Exception {
        boolean control = args.length > 0 && args[0].equals("control");
        int mismatches = 0, cases = 0;
        try {
            for (boolean counts : List.of(false, true)) for (int size : List.of(2, 3, 7, 10))
                for (long amount : new long[]{2, 1000, 1_000_000_000_000L}) for (boolean preserve : List.of(false, true))
                    for (boolean force : List.of(false, true)) {
                        var recipes = cycle(size, 2);
                        Result direct = run(counts, recipes, amount, preserve, force, 0);
                        Result noisy = run(counts, recipes, amount, preserve, force, 1);
                        boolean same = Objects.equals(direct.choice(), noisy.choice()) && direct.steps() == noisy.steps() && direct.ownWork() == noisy.ownWork();
                        if (!same) mismatches++;
                        if (!control) check(same, "interleaving changed counts=" + counts + " size=" + size + " amount=" + amount + " preserve=" + preserve + " force=" + force + ": " + direct + " / " + noisy);
                        cases++;
                    }
            if (!control) for (boolean counts : List.of(false, true)) for (int size : List.of(3, 7)) {
                Result direct = run(counts, cycle(size, 2), 1000, true, true, 0);
                Result switched = run(counts, cycle(size, 2), 1000, true, true, 2);
                check(Objects.equals(direct.choice(), switched.choice()), "thread migration changed witness");
                check(direct.steps() == switched.steps(), "thread migration changed local cutoff");
            }
            if (!control) for (boolean counts : List.of(false, true)) for (boolean cancelled : List.of(false, true)) interrupted(counts, cancelled);
            System.out.println("cases=" + cases + "; assertions=" + checks + "; mismatches=" + mismatches + "; control=" + control);
            if (control && mismatches == 0) throw new AssertionError("control did not reproduce accounting bug");
        } finally {
            for (var worker : workers) worker.shutdownNow();
        }
    }
}
