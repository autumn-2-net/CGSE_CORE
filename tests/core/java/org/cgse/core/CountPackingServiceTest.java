// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

/** Real complementary frontiers must survive service, worker moves and cancellation. */
public final class CountPackingServiceTest {
    public static void main(String[] args) throws Exception {
        handoff(false);
        handoff(true);
        System.out.println("Packing service: retained stacks, charged work, fixed ceilings, completed trial, cancellation and cleanup passed");
    }

    private static void handoff(boolean cancel) throws Exception {
        var random = new Random(4917);
        var weights = new long[11][100];
        var stock = new LinkedHashMap<String, Long>();
        for (int r = 0; r < 10; r++) {
            long total = 0;
            for (int i = 0; i < 100; i++) {
                weights[r][i] = 1 + random.nextInt(100);
                weights[10][i] += weights[r][i];
                if (i % 2 == 0) total += weights[r][i];
            }
            stock.put("cap" + r, total);
        }
        long amount = 0;
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 100; i++) {
            var inputs = new LinkedHashMap<String, Long>();
            for (int r = 0; r < 10; r++) inputs.put("cap" + r, weights[r][i]);
            inputs.put("once" + i, 1L);
            stock.put("once" + i, 1L);
            recipes.add(HistoricalFixtures.r("r" + i, inputs, Map.of("goal", weights[10][i])));
            if (i % 2 == 0) amount += weights[10][i];
        }
        // Executing the even recipes funds every primitive input and delivers
        // exactly the target. The scheduling assertions below are not a proof
        // of infeasibility if either restricted search remains undecided.
        var budget = new PlanningBudget(0, 4_000_000, 256L << 20, () -> false, System::nanoTime);
        var worker = Executors.newSingleThreadExecutor();
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(recipes), "goal", amount, stock,
                Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            @SuppressWarnings("unchecked")
            var branch = ((Deque<IntegerCountBranch<String>>) get(search, "pending")).getFirst();
            while (branch.viewStage != 10) {
                check(branch.state == IntegerCountBranch.State.OPEN, "Fixture closed before complementary service");
                branch.run(1, List.of(), List.of(), List.of(), null, () -> false);
            }
            var packing = branch.packing;
            var views = branch.viewSearch;
            long ordinaryWork = packing.work(), totalWork = budget.nodes();
            var arms = (List<?>) get(views, "searches");
            CountPackingCore core = null;
            for (Object arm : arms) if (get(arm, "specialist") instanceof CountPackingCore value) core = value;
            check(core != null && core.initialOpportunity(), "Missing owned initial packing frontier");
            long coreWork = core.work(), maximum = (long) get(core, "maximumWork");
            while (branch.viewStage == 10) {
                worker.submit(() -> branch.run(1, List.of(), List.of(), List.of(), null, () -> false)).get();
                check(branch.state == IntegerCountBranch.State.OPEN, "Fixture closed during the handoff");
            }
            check(branch.packing == packing && branch.viewSearch == views && packing.work() == ordinaryWork,
                    "Complementary service replaced or advanced the parked ordinary stack");
            check(core.work() > coreWork && budget.nodes() > totalWork && (long) get(core, "maximumWork") == maximum,
                    "Service was free, made no progress, or renewed a local ceiling");
            long before = budget.threadWork();
            while (packing.work() == ordinaryWork) branch.run(1, List.of(), List.of(), List.of(), null, () -> false);
            check(packing.work() - ordinaryWork <= budget.threadWork() - before && branch.packing == packing,
                    "Ordinary work was replayed, uncharged or discarded");
            long credit = (long) get(branch, "packingServiceCredit");
            check(credit > 0 && credit <= budget.nodes() - totalWork,
                    "Actual early service was lost or counted twice");
            var grant = IntegerCountBranch.class.getDeclaredMethod("packingQuantum", long.class);
            grant.setAccessible(true);
            long globalWork = budget.nodes(), reserved = budget.reservedBytes();
            while ((long) get(branch, "packingServiceCredit") > 0) {
                long previous = (long) get(branch, "packingServiceCredit");
                long quantum = views.packingContinuationQuantum(CountPortfolioPolicy.MAX_QUANTUM);
                check(quantum > 0, "Fixture retired before the service ledger check");
                long actual = (long) grant.invoke(branch, CountPortfolioPolicy.MAX_QUANTUM);
                long remaining = (long) get(branch, "packingServiceCredit");
                check(remaining < previous && remaining >= 0 && actual >= 0 &&
                        actual + previous - remaining == quantum,
                        "Early service was granted again or offset more than once");
            }
            check((long) grant.invoke(branch, CountPortfolioPolicy.MAX_QUANTUM) > 0 &&
                    budget.nodes() == globalWork && budget.reservedBytes() == reserved,
                    "Service credit refunded global work, allocated memory or permanently withheld later visits");

            // A completed restricted miss is a service observation, not a new
            // row or an infeasibility claim. Consume its cost once, including
            // a partial final batch, then restore ordinary extra service.
            long failedCost = 2 * CountPortfolioPolicy.MAX_QUANTUM + 13;
            set(core, "failedRoundingWork", failedCost);
            long offered = views.packingContinuationQuantum(CountPortfolioPolicy.MAX_QUANTUM);
            check(views.packingContinuationQuantum(CountPortfolioPolicy.MAX_QUANTUM) == offered,
                    "Inspecting an eligible frontier consumed cooldown feedback");
            long paid = 0;
            for (int i = 0; i < 3; i++) {
                long actual = (long) grant.invoke(branch, CountPortfolioPolicy.MAX_QUANTUM);
                paid += offered - actual;
                check(views.takePackingCooldown() == 0, "Failed attempt was reported more than once");
            }
            check(paid == failedCost && (long) get(branch, "packingServiceCredit") == 0 &&
                    (long) grant.invoke(branch, CountPortfolioPolicy.MAX_QUANTUM) == offered,
                    "Measured cooldown was rounded to whole turns or withheld service permanently");
            check(branch.viewSearch == views && core.remainingAllowance() > 0 &&
                    budget.nodes() == globalWork && budget.reservedBytes() == reserved,
                    "Cooldown changed the frontier, actual work, memory or original allowance");
            set(branch, "packingServiceCredit", Long.MAX_VALUE - 7);
            set(core, "failedRoundingWork", failedCost + 100);
            check((long) grant.invoke(branch, CountPortfolioPolicy.MAX_QUANTUM) == 0 &&
                    (long) get(branch, "packingServiceCredit") == Long.MAX_VALUE - offered,
                    "Combining service feedback overflowed the long allowance");
            set(branch, "packingServiceCredit", 0L);

            // A completed first exchange ends this extra service, not the
            // retained repair's ordinary LP/portfolio eligibility. Only the
            // scheduling observation changes; rows and search state do not.
            boolean attempted = (boolean) get(core, "scoutAttempted"), scouting = (boolean) get(core, "scouting");
            set(core, "scoutAttempted", true);
            set(core, "scouting", false);
            check(views.packingScoutQuantum(Long.MAX_VALUE) == 0 && views.packingContinuationQuantum() > 0,
                    "Closed trial kept extra service or lost ordinary continuation");
            set(core, "scoutAttempted", attempted);
            set(core, "scouting", scouting);
            check(views.packingScoutQuantum(Long.MAX_VALUE) > 0, "Live initial frontier was discarded");
            if (cancel) {
                budget.cancel();
                try { branch.run(1, List.of(), List.of(), List.of(), null, () -> false); throw new AssertionError("Ignored cancellation"); }
                catch (CancellationException expected) {}
            }
        } finally { worker.shutdownNow(); }
        check(budget.reservedBytes() == 0, "Complementary packing ownership leaked");
    }

    private static Object get(Object owner, String name) throws Exception { var field = field(owner, name); return field.get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner, name).set(owner, value); }
    private static Field field(Object owner, String name) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
