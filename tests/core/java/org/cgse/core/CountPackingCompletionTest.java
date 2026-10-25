// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Captured packing service keeps its owner, finite allowance and independent ledgers. */
public final class CountPackingCompletionTest {
    private static int checks;

    private record Fixture(List<GraphRecipe<String>> recipes, Map<String, Long> stock, long amount) {}

    public static void main(String[] args) throws Exception {
        Fixture fixture = fixture();
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(fixture.recipes), "goal", fixture.amount,
                fixture.stock, Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            CountPackingCore core = null;
            for (int steps = 0; core == null; steps++) {
                check(steps < 100000 && budget.nodes() < 18_000_000, "No real expanded owner; work=" + budget.nodes());
                boolean done = search.step();
                core = search.activePackingCompletion();
                if (done && core == null) {
                    check(search.paused(), "Fixture finished before expanded ownership");
                    search.resumeBounded(1_000_000);
                }
            }
            check(!search.infeasible() && search.result() == null, "Partial owner became a conclusion");
            innerOwnership(search, core, budget);
            outerOwnership(fixture, search, core, budget);
        }
        check(budget.reservedBytes() == 0, "Owner preparation leaked");
        System.out.println("Packing completion: real expanded owner, finite dispatch, separate ledgers, proof exclusion, expiry and cancellation passed; checks=" + checks);
    }

    @SuppressWarnings("unchecked")
    private static void innerOwnership(IntegerCountSearch<String> search, CountPackingCore core,
                                       PlanningBudget budget) throws Exception {
        var pending = (Deque<IntegerCountBranch<String>>) get(search, "pending");
        var deferred = (Deque<IntegerCountBranch<String>>) get(search, "deferred");
        var branch = (IntegerCountBranch<String>) get(search, "packingCompletionObserved");
        check(branch != null && branch.viewSearch.activePackingCompletion() == core, "No observed real owner");
        // An ordinary unopened sibling competes for the same finite search.
        // Its real row/domain initialization remains owned by the parent queue.
        var sibling = new IntegerCountBranch<>(branch.model, branch.execution, branch.target, branch.amount,
                branch.stock, branch.seeds, branch.external, branch.preserve, branch.force,
                budget, branch.started, branch.current);
        pending.addLast(sibling);
        check(pending.size() + deferred.size() > 1, "Fixture must retain ordinary competing branches");
        long afterScan = budget.nodes(), memory = budget.reservedBytes();
        for (int i = 0; i < 10; i++) check(search.activePackingCompletion() == null, "Discovery ignored its cooldown");
        check(budget.nodes() == afterScan && budget.reservedBytes() == memory, "Repeated discovery charged or allocated");

        // An observation is not ownership: removal from both queues invalidates it.
        pending.remove(branch); deferred.remove(branch);
        check(!search.preferPackingCompletion(core, budget.nodes() + 262144), "Stale observed queue member admitted");
        deferred.addLast(branch);
        rescan(search, budget);
        check(get(search, "packingCompletionObserved") == branch, "A sole deferred owner was invisible");

        branch.proofTask = true;
        check(rescan(search, budget) == null, "Proof branch advertised candidate-only service");
        branch.proofTask = false;
        branch.viewSearch.mode(CountPortfolioPolicy.Mode.PROOF);
        check(rescan(search, budget) == null, "Proof portfolio advertised candidate-only service");
        branch.viewSearch.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
        check(rescan(search, budget) == core, "Restoring witness mode lost the retained owner");
        search.clearPackingCompletion();
        check(get(search, "packingCompletionObserved") == null, "Clearing an unadopted observation retained its branch");
        check(rescan(search, budget) == core, "Cleanup permanently suppressed an eligible owner");

        long until = budget.nodes() + 262144;
        check(search.preferPackingCompletion(core, until) && search.ownsPackingCompletion(core), "Finite owner grant declined");
        check(!search.preferPackingCompletion(core, until + 1_000_000) &&
                (long) get(search, "packingCompletionUntil") == until, "Repeated grant renewed the deadline");
        var views = branch.viewSearch;
        branch.viewSearch = null;
        check(!search.ownsPackingCompletion(core) && search.activePackingCompletion() == null,
                "A detached owner still received identity-based service");
        branch.viewSearch = views;
        long ordinary = (long) get(search, "frontierWork"), continuation = (long) get(search, "continuationWork");
        long raw = (long) get(search, "work"), global = budget.nodes(), local = core.work();
        long localLimit = (long) get(core, "maximumWork");
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int turns = 0; core.work() == local && turns < 32; turns++) {
                var wave = (List<IntegerCountBranch<String>>) invoke(search, "take", new Class<?>[]{int.class, int.class}, 4, 8);
                check(wave.size() == 1 && wave.get(0) == branch, "Captured owner lost dispatch to ordinary siblings");
                set(search, "dispatched", wave);
                worker.submit(() -> branch.run(1024, List.of(), List.of(), List.of(), null, () -> false)).get();
                long paid = branch.work, beforeMerge = (long) get(search, "work");
                invoke(search, "merge", new Class<?>[]{List.class, boolean.class}, wave, true);
                set(search, "dispatched", List.of());
                check((long) get(search, "work") == beforeMerge + paid, "Owned dispatch did not charge actual raw work");
                check((long) get(search, "frontierWork") == ordinary &&
                        (long) get(search, "continuationWork") == continuation, "Extra service created ordinary scheduling debt");
                check((long) get(search, "packingCompletionUntil") == until, "Dispatch renewed the deadline");
            }
        } finally { worker.shutdownNow(); }
        check(core.work() > local && (long) get(search, "work") > raw && budget.nodes() > global,
                "Retained owner made no charged progress");
        check((long) get(core, "maximumWork") == localLimit, "Extra service raised the owner's original cap");
        search.clearPackingCompletion();
        check(!search.ownsPackingCompletion(core) && get(search, "packingCompletionObserved") == null,
                "Clearing ownership retained an observed branch");
    }

    private static CountPackingCore rescan(IntegerCountSearch<?> search, PlanningBudget budget) {
        budget.charge(65_536);
        long before = budget.nodes();
        var result = search.activePackingCompletion();
        check(budget.nodes() > before, "Eligible owner scan was uncharged");
        return result;
    }

    private static void outerOwnership(Fixture fixture, IntegerCountSearch<String> search,
                                       CountPackingCore core, PlanningBudget budget) throws Exception {
        // Reuse the live, independently prepared owner. Only coordinator phase and
        // completed competing-work observations are controlled here; no solution,
        // conflict or solver domain is injected into the search.
        if (budget.nodes() < 16_777_216) budget.charge(16_777_216 - budget.nodes());
        budget.charge(65_536);
        long coreLimit = (long) get(core, "maximumWork");
        var work = new GraphPlanner<>(new GraphCompiler<>(fixture.recipes))
                .begin("goal", fixture.amount, fixture.stock, false, true, budget);
        try {
            set(work, "phase", 14); set(work, "countSearch", search);
            set(work, "stockCountTurn", true); set(work, "stockCountWork", 29L);
            set(work, "stockCountStarted", budget.searchWork() - 17);
            check(!work.step(), "Ownership hook manufactured a completed plan");
            check((boolean) get(work, "packingCompletionAttempted") &&
                    get(work, "packingCompletionOwner") == core && search.ownsPackingCompletion(core),
                    "Coordinator did not retain the actual owner");
            long until = (long) get(work, "packingCompletionUntil");
            check(until > budget.nodes() && until - budget.nodes() <= 8_388_608 &&
                    until - budget.nodes() <= core.expandedCompletionWork(), "Outer window exceeded its captured owner allowance");
            long settled = (long) get(work, "stockCountWork");
            check(!(boolean) get(work, "stockCountTurn") && settled >= 46 && settled < 4096,
                    "Prior stock credit was not settled before the extra window");
            long before = budget.nodes();
            for (int i = 0; i < 3; i++) {
                check(!work.step(), "Fixture finished before the finite-window checks");
                check((long) get(work, "packingCompletionUntil") == until &&
                        (long) get(work, "stockCountWork") == settled, "Extra service renewed a grant or debited stock credit");
            }
            check(budget.nodes() > before && (long) get(core, "maximumWork") == coreLimit,
                    "Outer service was free or changed the local solver budget");
            // A competing owner may consume the last window units. The captured
            // request deadline must expire without renewing this one's allowance.
            budget.charge(Math.max(0, until - budget.nodes()));
            work.step();
            check(get(work, "packingCompletionOwner") == null &&
                    (long) get(work, "packingCompletionUntil") == 0 && !search.ownsPackingCompletion(core),
                    "Expired owner retained preferential service");
            check(core.expandedCompletionWork() > 0, "Expiry fixture must keep a live owner to test nonrenewal");
            for (int i = 0; i < 2; i++) work.step();
            check((boolean) get(work, "packingCompletionAttempted") && get(work, "packingCompletionOwner") == null,
                    "A later observation renewed the single outer opportunity");
            budget.cancel();
            try { work.step(); throw new AssertionError("Cancelled outer completion continued"); }
            catch (CancellationException expected) { checks++; }
        } finally { work.close(); }
        check(get(search, "packingCompletionObserved") == null && get(search, "packingCompletionDispatched") == null &&
                get(search, "packingCompletionOwner") == null, "Closed request retained an owner reference");
    }

    /** Independently replay the checked-in Boolean witness as raw input/output recipes. */
    private static Fixture fixture() throws Exception {
        String input = String.join("\n", Files.readAllLines(Path.of("tests/core/data/packing-rounding-100.txt"))
                .stream().filter(line -> !line.startsWith("#")).toList());
        try (var scanner = new Scanner(input)) {
            int n = scanner.nextInt(), m = scanner.nextInt();
            long[][] rows = new long[m][n]; long[] rhs = new long[m]; int demand = -1;
            for (int r = 0; r < m; r++) {
                rhs[r] = scanner.nextLong();
                boolean negative = true, positive = true;
                for (int i = 0; i < n; i++) {
                    rows[r][i] = scanner.nextLong(); negative &= rows[r][i] < 0; positive &= rows[r][i] > 0;
                }
                check(positive || negative, "Fixture must use capacity or delivery rows");
                if (negative) { check(demand == -1, "Multiple delivery rows"); demand = r; }
            }
            check(demand >= 0, "No delivery row");
            var stock = new LinkedHashMap<String, Long>();
            for (int r = 0; r < m; r++) if (r != demand) stock.put("cap" + r, rhs[r]);
            var recipes = new ArrayList<GraphRecipe<String>>();
            for (int i = 0; i < n; i++) {
                var inputs = new LinkedHashMap<String, Long>();
                for (int r = 0; r < m; r++) if (r != demand) inputs.put("cap" + r, rows[r][i]);
                inputs.put("once" + i, 1L); stock.put("once" + i, 1L);
                recipes.add(HistoricalFixtures.r("r" + i, inputs, Map.of("goal", -rows[demand][i])));
            }
            var replay = new HashMap<String, BigInteger>();
            stock.forEach((key, value) -> replay.put(key, BigInteger.valueOf(value)));
            for (int i = 0; i < n; i++) {
                int count = scanner.nextInt(); check(count == 0 || count == 1, "Non-Boolean oracle");
                if (count == 0) continue;
                for (var entry : recipes.get(i).inputs().entrySet()) {
                    BigInteger after = replay.getOrDefault(entry.getKey(), BigInteger.ZERO).subtract(BigInteger.valueOf(entry.getValue()));
                    check(after.signum() >= 0, "Original witness cannot execute input prefix");
                    replay.put(entry.getKey(), after);
                }
                recipes.get(i).outputs().forEach((key, value) -> replay.merge(key, BigInteger.valueOf(value), BigInteger::add));
            }
            check(!scanner.hasNext() && replay.get("goal").compareTo(BigInteger.valueOf(-rhs[demand])) >= 0,
                    "Original witness does not deliver requested target");
            return new Fixture(recipes, stock, -rhs[demand]);
        }
    }

    private static Object get(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
