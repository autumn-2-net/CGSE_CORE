// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Final validation feedback must preserve positive-only continuations and their owners. */
public final class StockViewFeedbackTest {
    public static void main(String[] args) throws Exception {
        rejectedProposalContinues();
        sameViewChangesSource();
        sameSourcesDifferentExecution();
        validationConsumesViewQuota();
        productionCutoffIsInconclusive();
        cancellationAndClose();
        candidateMetrics();
        largeCatalogOuterValidation(false);
        largeCatalogOuterValidation(true);
        concurrentCandidateMetrics();
        System.out.println("Stock view feedback: retained siblings/source repair, proof cutoff, cancellation and candidate metrics passed");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static GraphCompiler<String> compiler() {
        return new GraphCompiler<>(List.of(recipe("first", Map.of("ore", 1L), Map.of("C", 1L)),
                recipe("second", Map.of("ore", 1L), Map.of("C", 1L))));
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
    }

    private static GraphStockViewPortfolio<String> portfolio(GraphCompiler<String> compiler, PlanningBudget budget) {
        return new GraphStockViewPortfolio<>(compiler, "C", 1, Map.of("ore", 1L), Set.of(), Map.of(), Set.of(),
                false, true, CatalystPolicy.STOCK, budget, System.nanoTime());
    }

    private static void rejectedProposalContinues() {
        var compiler = compiler();
        var budget = budget();
        int rejected = 0;
        try (var work = portfolio(compiler, budget)) {
            for (int steps = 0; steps < 100_000; steps++) {
                if (!work.step()) continue;
                if (work.paused()) { work.resume(100_000); continue; }
                var plan = work.result();
                check(plan != null, "Rejecting one witness exhausted other source views");
                long before = budget.nodes();
                check(work.step() && work.result() == plan && budget.nodes() == before, "Unacknowledged proposal was rerun");
                verify(plan, budget);
                if (plan.patternTimes().containsKey("second")) {
                    check(rejected > 1, "Sibling proposals with identical counts were suppressed");
                    check(budget.reservedBytes() > 0, "Proposal prematurely closed its continuation");
                    work.feedback(CandidateFeedback.ACCEPTED);
                    check(budget.reservedBytes() == 0, "Accepted portfolio leaked reservations");
                    // Local rejection must not invalidate the catalog or its compiler caches.
                    var other = new GraphPlanningWork<>(compiler, "C", 1, Map.of("ore", 1L), false, true, budget);
                    try {
                        while (!other.step()) {}
                        check(other.result().feasible(), "View-local rejection escaped into a global conflict");
                    } finally { other.close(); }
                    return;
                }
                rejected++;
                check(rejected <= 20, "Rejected view repeated instead of repairing sources");
                work.feedback(CandidateFeedback.INCONCLUSIVE);
            }
            throw new AssertionError("No continuation witness");
        } finally {
            check(budget.reservedBytes() == 0, "Portfolio leaked after final close");
        }
    }

    private static void sameViewChangesSource() {
        var budget = budget();
        try (var work = new GraphStockViewWork<>(compiler(), "C", 1, Map.of("ore", 1L), Set.of(), Map.of(), Set.of(),
                false, true, CatalystPolicy.STOCK, budget, System.nanoTime(), 0, () -> null)) {
            while (!work.step()) {}
            check(work.result().patternTimes().containsKey("first"), "Wrong initial source");
            work.feedback(CandidateFeedback.REJECTED);
            while (!work.step()) {}
            check(work.result() != null && work.result().patternTimes().containsKey("second"), "Same view lost its source-repair continuation");
            verify(work.result(), budget);
            work.feedback(CandidateFeedback.ACCEPTED);
            try {
                work.feedback(CandidateFeedback.REJECTED);
                throw new AssertionError("Closed view was resurrected");
            } catch (IllegalStateException expected) {}
        }
        check(budget.reservedBytes() == 0, "Source repair leaked reservations");
    }

    private static void productionCutoffIsInconclusive() {
        var first = recipe("seed", Map.of("C", 1L), Map.of("A", 1L));
        var main = recipe("make", Map.of("A", 2L, "B", 3L), Map.of("A", 1L, "C", 3L));
        var plan = new GraphPlan<>("C", 3, false,
                new PlanStep.Sequence(List.of(new PlanStep.Batch("seed", 1), new PlanStep.Batch("make", 1))),
                Map.of("seed", first, "make", main), Map.of("A", 1L, "B", 3L, "C", 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var budget = budget();
        try (var verification = new PlanVerification<>(plan, budget)) {
            while (!verification.step()) {}
            var low = new PlanningBudget(0, 1000, () -> false);
            try (var proof = new ForceCraftProof<>(plan, verification, Map.of(), low)) {
                while (!proof.step()) {}
                check(proof.outcome() == ForceCraftProof.Outcome.WORK_LIMIT && proof.feedback() == CandidateFeedback.INCONCLUSIVE,
                        "A proof cutoff became a rejected/global-negative witness");
            }
            check(low.reservedBytes() == 0, "Cutoff leaked");
            try (var proof = new ForceCraftProof<>(plan, verification, Map.of(), budget)) {
                while (!proof.step()) {}
                check(proof.feedback() == CandidateFeedback.ACCEPTED, "Productive seed-consuming cycle rejected");
            }
        }
        check(budget.reservedBytes() == 0, "Proof leaked");
    }

    private static void sameSourcesDifferentExecution() {
        var recipes = List.of(recipe("seed", Map.of("C", 1L), Map.of("A", 1L)),
                recipe("make", Map.of("A", 2L, "B", 3L), Map.of("A", 1L, "C", 3L)));
        var budget = budget();
        try (var work = new GraphStockViewWork<>(new GraphCompiler<>(recipes), "C", 3, Map.of("A", 1L, "B", 6L, "C", 1L),
                Set.of(), Map.of(), Set.of(), false, true, CatalystPolicy.STOCK, budget, System.nanoTime(), 0, () -> null)) {
            while (!work.step()) {}
            check(work.result() != null, "Missing initial cyclic proposal");
            verify(work.result(), budget);
            work.feedback(CandidateFeedback.INCONCLUSIVE);
            while (!work.step()) {}
            check(work.result() != null, "Rejected witness discarded another execution of the same sources");
            verify(work.result(), budget);
        }
        check(budget.reservedBytes() == 0, "Same-source execution retry leaked");
    }

    private static void validationConsumesViewQuota() {
        var budget = budget();
        try (var work = portfolio(compiler(), budget)) {
            while (!work.step()) {}
            int origin = work.proposalView();
            // An expensive final proof is real request work and also consumes
            // this positive-only strategy's quota, not a fresh sibling's turn.
            budget.charge(300_000);
            work.feedback(CandidateFeedback.INCONCLUSIVE);
            long charged = budget.nodes();
            work.validationWork(origin, 300_000);
            check(budget.nodes() == charged, "Validation was charged twice to the request");
            check(work.step() && work.paused() && work.result() == null, "Rejected witnesses bypassed the portfolio quota");
            work.resume(100_000);
            while (!work.step()) {}
            check(work.result() != null, "Quota handoff lost the remaining views");
        }
        check(budget.reservedBytes() == 0, "Validation quota handoff leaked");
    }

    private static void cancellationAndClose() {
        for (int stop = 0; stop < 100; stop++) {
            var budget = budget();
            var work = portfolio(compiler(), budget);
            for (int step = 0; step < stop && !work.step(); step++) {}
            budget.cancel();
            try {
                if (work.result() == null) work.step();
                else work.feedback(CandidateFeedback.ACCEPTED);
                throw new AssertionError("Cancelled candidate was published");
            } catch (java.util.concurrent.CancellationException expected) {
                // Cancellation during construction or pending validation shares the same owner.
            } finally {
                work.close();
                work.close();
            }
            check(budget.reservedBytes() == 0, "Cancellation/duplicate close leaked at step " + stop);
        }
    }

    private static void candidateMetrics() {
        for (boolean enabled : new boolean[] { false, true }) {
            var budget = budget();
            if (enabled) budget.enableMetrics();
            var work = new GraphPlanningWork<>(compiler(), "C", 1, Map.of("ore", 1L), false, true, budget);
            try {
                while (!work.step()) {}
                check(work.result().feasible(), "Metrics changed solve behavior");
            } finally { work.close(); }
            var metrics = budget.candidates();
            if (enabled) {
                var counts = metrics.origins().get(PlanningBudget.CandidateOrigin.SOURCE_GRAPH);
                check(counts != null && counts.proposed() == 1 && counts.accepted() == 1 && counts.abandoned() == 0,
                        "Candidate was not settled exactly once");
                check(counts.validationWork() > 0 && counts.validationNanos() > 0 && metrics.firstVerifiedSearchWork() > 0 &&
                        metrics.firstVerifiedCompilationWork() > 0 && metrics.firstVerifiedElapsedNanos() >= 0, "Missing verified-work observation");
            } else check(metrics.origins().isEmpty() && metrics.firstVerifiedSearchWork() == -1, "Disabled telemetry retained candidates");
            check(budget.reservedBytes() == 0, "Metrics test leaked");
        }
    }

    private static void largeCatalogOuterValidation(boolean pressure) {
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        recipes.add(recipe("goal", Map.of("X", 10L), Map.of("C", 1L)));
        for (int i = 0; i < 8200; i++) recipes.add(recipe("unfunded" + i, Map.of("absent" + i, 1L), Map.of("X", 1L)));
        if (pressure) {
            recipes.add(2, recipe("funded", Map.of("Y", 1L), Map.of("X", 2L)));
            recipes.add(3, recipe("activate", Map.of("X", 1L, "ore", 1L), Map.of("Y", 1L)));
        } else recipes.add(2, recipe("funded", Map.of("ore", 1L), Map.of("X", 10L)));
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        budget.enableMetrics();
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "C", 1, Map.of("X", 1L, "ore", 20L), false, true, budget);
        long occupied = 0;
        try {
            while (!work.step()) {
                if (pressure && occupied == 0 && budget.candidates().origins().containsKey(PlanningBudget.CandidateOrigin.STOCK_VIEW)) {
                    occupied = budget.availableBytes() - 512;
                    budget.reserve(occupied);
                }
            }
            budget.release(occupied);
            occupied = 0;
            check(work.result().feasible(), "Stock continuation failed outside full-count admission: " + budget.diagnostics());
            verify(work.result(), budget);
            var counts = budget.candidates().origins().get(PlanningBudget.CandidateOrigin.STOCK_VIEW);
            check(counts != null && counts.accepted() == 1, "Outer validator lost stock-view origin: " + budget.candidates() + " " + budget.diagnostics());
            if (pressure) check(budget.diagnostics().contains("released_for_final_validation"), "No final-validator memory handoff exercised");
            check(budget.failureDetail().isEmpty(), "Recovered optional-memory failure left a terminal cause");
        } finally { work.close(); budget.release(occupied); }
        check(budget.reservedBytes() == 0, "Large stock-view validation leaked");
    }

    private static void verify(GraphPlan<String> plan, PlanningBudget budget) {
        try (var verification = new PlanVerification<>(plan, budget)) {
            while (!verification.step()) {}
            try (var production = new ForceCraftProof<>(plan, verification, Map.of(), budget)) {
                while (!production.step()) {}
                check(production.proved(), "Source proposal did not satisfy physical production");
            }
        }
    }

    private static void concurrentCandidateMetrics() throws Exception {
        var budget = budget();
        budget.enableMetrics();
        var compiler = compiler();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) jobs.add(() -> {
                var work = new GraphPlanningWork<>(compiler, "C", 1, Map.of("ore", 1L), false, true, budget);
                try {
                    while (!work.step()) {}
                    return work.result().feasible();
                } finally { work.close(); }
            });
            for (var future : pool.invokeAll(jobs)) check(future.get(), "Concurrent request failed");
        } finally { pool.shutdownNow(); }
        var counts = budget.candidates().origins().get(PlanningBudget.CandidateOrigin.SOURCE_GRAPH);
        check(counts.proposed() == 32 && counts.accepted() == 32 && counts.abandoned() == 0,
                "Parallel candidate settlements were lost/doubled: " + counts);
        check(budget.reservedBytes() == 0, "Concurrent candidate verification leaked");
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
