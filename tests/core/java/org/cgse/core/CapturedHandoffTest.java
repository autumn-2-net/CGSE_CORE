// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.cgse.core.CapturedRecipeDomainTest.*;

/** Deterministic limit injection between production child completion and outer publication. */
public final class CapturedHandoffTest {

    public static void main(String[] args) throws Exception {
        for (var limit : List.of(PlanningBudget.Limit.SEARCH_LIMIT, PlanningBudget.Limit.MEMORY_LIMIT, PlanningBudget.Limit.TIMEOUT)) {
            completedCanonicalProof(limit);
            incompletePrefix(limit);
            incompleteCapture(limit);
            unfinishedProof(limit);
            unprovedMissingPreview(limit);
            verifiedWitness(limit, false);
            verifiedWitness(limit, true);
        }
        restrictedAllocationProof();
        restrictedFlowFailure();
        cancellation(false, false);
        cancellation(true, false);
        cancellation(false, true);
        cancellation(true, true);
        System.out.println("CapturedHandoffTest passed");
    }

    private static void completedCanonicalProof(PlanningBudget.Limit limit) throws Exception {
        // All original recipes consume absent raw resources. Quantity analysis
        // proves the original model impossible before optional missing preview.
        for (boolean reverse : new boolean[]{false, true}) {
            var clock = new AtomicLong();
            var budget = timedBudget(clock);
            var entries = reverse ? List.of(entry("b", "raw-b"), entry("a", "raw-a")) :
                    List.of(entry("a", "raw-a"), entry("b", "raw-b"));
            var catalog = new CapturedCatalog<>(entries);
            try (var work = catalog.begin("out", 1, Map.of(), false, true, budget)) {
                awaitProof(work);
                check(work.coverage().complete(), "canonical capture incomplete");
                check(!Boolean.TRUE.equals(read(work, "allocationView")), "unexpected support view");
                var result = work.limited(exhaust(budget, clock, limit));
                check(result.plan().result() == GraphPlan.Result.INFEASIBLE,
                        "completed canonical proof lost at limit handoff: " + limit + "/" + result.plan().result());
                check(result.plan().missingExact().isEmpty(), "unfinished missing preview escaped");
                check(work.limited(new PlanningBudget.Exhausted(limit)) == result, "completed result changed");
            }
            clean(budget, "canonical proof " + limit);
            // Changed stock and a new producer invalidate the old contradiction.
            var supplied = catalog.plan("out", 1, Map.of("raw-a", 1L), false, true, budget());
            check(supplied.plan().feasible(), "stock change inherited old proof");
            var changed = new ArrayList<>(entries);
            changed.add(entry("new-producer", "funded"));
            var expanded = new CapturedCatalog<>(changed).plan("out", 1, Map.of("funded", 1L), false, true, budget());
            check(expanded.plan().feasible(), "new producer inherited old proof");
        }
    }

    private static void incompletePrefix(PlanningBudget.Limit limit) throws Exception {
        var catalog = alternatives();
        var stock = Map.of("raw299", 1L);
        var clock = new AtomicLong();
        var budget = timedBudget(clock);
        try (var work = catalog.begin("out", 1, stock, false, true, budget)) {
            awaitProof(work);
            check(!work.coverage().complete() && work.coverage().domains().get(0).expanded().intValueExact() == 256,
                    "test did not reach the incomplete 256-prefix");
            check(child(work).limited(new PlanningBudget.Exhausted(limit)).result() == GraphPlan.Result.INFEASIBLE,
                    "prefix should have a real completed local proof");
            assertLimit(work.limited(exhaust(budget, clock, limit)), limit, "incomplete prefix");
        }
        clean(budget, "prefix");
        var complete = catalog.plan("out", 1, stock, false, true, budget());
        check(complete.plan().feasible() && complete.plan().patternTimes().containsKey("r#299"), "omitted witness is not executable");
        PlanVerifier.verify(complete.plan());
    }

    private static void incompleteCapture(PlanningBudget.Limit limit) throws Exception {
        for (boolean boundedRecipe : new boolean[]{false, true}) {
            var source = entry("r", "raw");
            if (boundedRecipe) source = new CapturedCatalog.Entry<>(source.id(), source.binding(),
                    new CapturedRecipe<>(source.recipe().inputs(), source.recipe().outputs(), true, true));
            var catalog = new CapturedCatalog<>(List.of(source), boundedRecipe);
            var clock = new AtomicLong();
            var budget = timedBudget(clock);
            try (var work = catalog.begin("out", 1, Map.of(), false, true, budget)) {
                awaitProof(work);
                check(!work.coverage().complete(), "omitted providers/candidates considered complete");
                assertLimit(work.limited(exhaust(budget, clock, limit)), limit, "incomplete capture");
            }
            clean(budget, "capture");
        }
    }

    private static void unfinishedProof(PlanningBudget.Limit limit) throws Exception {
        var clock = new AtomicLong();
        var budget = timedBudget(clock);
        try (var work = new CapturedCatalog<>(List.of(entry("r", "raw"))).begin("out", 1, Map.of(), false, true, budget)) {
            awaitChild(work);
            check(work.coverage().complete(), "unfinished test requires full coverage");
            check(!Boolean.TRUE.equals(read(child(work), "quantityBlocked")), "proof unexpectedly completed");
            assertLimit(work.limited(exhaust(budget, clock, limit)), limit, "unfinished proof");
        }
        clean(budget, "unfinished");
    }

    private static void verifiedWitness(PlanningBudget.Limit limit, boolean prefix) throws Exception {
        var catalog = prefix ? alternatives() : new CapturedCatalog<>(List.of(entry("r", "raw")));
        var stock = Map.of(prefix ? "raw0" : "raw", 1L);
        var clock = new AtomicLong();
        var budget = timedBudget(clock);
        try (var work = catalog.begin("out", 1, stock, false, true, budget)) {
            // Advance the actual child directly to its publication boundary;
            // the parent has not yet had its next turn to accept the witness.
            var child = awaitChild(work);
            while (!child.step()) { }
            check(child.result().feasible(), "child did not verify its witness");
            var result = work.limited(exhaust(budget, clock, limit));
            check(result.plan().result() == GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, "completed witness lost");
            check(result.coverage().complete() != prefix, "witness scope changed");
            PlanVerifier.verify(result.plan());
            check(result.plan().initial().equals(stock), "witness inventory changed during handoff");
            work.close();
            check(work.result() == result, "closing lost published witness");
        }
        clean(budget, "witness " + limit);
    }

    private static void unprovedMissingPreview(PlanningBudget.Limit limit) throws Exception {
        var clock = new AtomicLong();
        var budget = timedBudget(clock);
        try (var work = new CapturedCatalog<>(List.of(entry("a", "raw-a"), entry("b", "raw-b")))
                .begin("out", 1, Map.of(), false, true, budget)) {
            var child = awaitChild(work);
            for (int i = 0; i < 200000 && read(child, "candidate") == null; i++)
                check(!child.step(), "child completed before initial missing preview");
            var candidate = (GraphPlan<?>) read(child, "candidate");
            check(candidate != null && !candidate.feasible() && !candidate.missingExact().isEmpty(), "no unproved missing candidate");
            check(!Boolean.TRUE.equals(read(child, "quantityBlocked")), "missing candidate already has full proof");
            assertLimit(work.limited(exhaust(budget, clock, limit)), limit, "unproved missing candidate");
        }
        clean(budget, "unproved missing candidate");
    }

    private static void restrictedAllocationProof() throws Exception {
        var clock = new AtomicLong();
        var budget = timedBudget(clock);
        try (var work = alternatives().begin("out", 1, Map.of("raw299", 1L), Set.of(), Map.of("raw299", 1L), false, true, budget)) {
            for (int i = 0; i < 200000; i++) {
                if (child(work) != null && Boolean.TRUE.equals(read(work, "allocationView"))) break;
                if (work.step()) throw new AssertionError("allocation support handoff not reached");
            }
            var child = child(work);
            check(child != null && Boolean.TRUE.equals(read(work, "allocationView")), "not an allocation view");
            // The stock allocation proposed spending the mandatory seed. Its
            // genuine restricted count proof cannot close the full capture.
            for (int i = 0; i < 200000 && !Boolean.TRUE.equals(read(child, "quantityBlocked")); i++)
                if (child.step()) throw new AssertionError("support proof not retained before publication");
            check(Boolean.TRUE.equals(read(child, "quantityBlocked")), "no completed support proof");
            check(child.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)).result() == GraphPlan.Result.INFEASIBLE,
                    "support proof not INFEASIBLE");
            assertLimit(work.limited(exhaust(budget, clock, PlanningBudget.Limit.SEARCH_LIMIT)), PlanningBudget.Limit.SEARCH_LIMIT,
                    "allocation support");
        }
        clean(budget, "allocation support");
    }

    private static void restrictedFlowFailure() throws Exception {
        var main = new CapturedRecipe<>(List.of(input("raw", 300, 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var seed = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("out", 1), null, false);
        var supply = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(seed))),
                List.of(new CapturedRecipe.Amount<>("raw299", 2)), true, false);
        var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "r", main),
                new CapturedCatalog.Entry<>("supply", "supply", supply)));
        var clock = new AtomicLong();
        var budget = timedBudget(clock);
        try (var work = catalog.begin("out", 1, Map.of(), false, true, budget)) {
            for (int i = 0; i < 200000; i++) {
                if (read(work, "flowWitness") != null) break;
                if (work.step()) throw new AssertionError("restricted flow handoff not reached");
            }
            @SuppressWarnings("unchecked")
            var flow = (CapturedFlowWitness<String>) read(work, "flowWitness");
            check(flow != null && Boolean.TRUE.equals(read(work, "allocationView")), "not a recovered flow view");
            while (!flow.step()) { }
            check(flow.result() == null, "startup-free flow falsely executed");
            // Balanced recipe counts alone do not prove executable startup.
            assertLimit(work.limited(exhaust(budget, clock, PlanningBudget.Limit.SEARCH_LIMIT)), PlanningBudget.Limit.SEARCH_LIMIT,
                    "recovered flow");
        }
        clean(budget, "flow");
    }

    private static void cancellation(boolean witness, boolean duringHarvest) throws Exception {
        var armed = new AtomicInteger();
        var clock = new AtomicLong();
        var budget = new PlanningBudget(1, 20_000_000, 512L << 20,
                () -> armed.get() > 0 && armed.decrementAndGet() == 0, clock::get);
        var stock = witness ? Map.of("raw", 1L) : Map.<String, Long>of();
        var work = new CapturedCatalog<>(List.of(entry("r", "raw"))).begin("out", 1, stock, false, true, budget);
        try {
            if (witness) {
                var child = awaitChild(work);
                while (!child.step()) { }
                check(child.result().feasible(), "cancel witness not complete");
            } else awaitProof(work);
            var exhausted = exhaust(budget, clock, PlanningBudget.Limit.TIMEOUT);
            if (duringHarvest) armed.set(2); // first outer checkpoint passes, second observes cancellation
            else budget.cancel();
            expect(CancellationException.class, () -> work.limited(exhausted));
            expect(IllegalStateException.class, work::result);
        } finally {
            work.close();
            work.close();
        }
        clean(budget, "cancellation");
    }

    private static GraphPlanningWork<String> awaitProof(CapturedCatalog<String>.Work work) throws Exception {
        for (int i = 0; i < 200000; i++) {
            var child = child(work);
            if (child != null && Boolean.TRUE.equals(read(child, "quantityBlocked"))) return child;
            if (work.step()) throw new AssertionError("outer finished before proof handoff: " + work.result().plan().result());
        }
        throw new AssertionError("proof boundary not reached");
    }

    private static GraphPlanningWork<String> awaitChild(CapturedCatalog<String>.Work work) throws Exception {
        for (int i = 0; i < 200000; i++) {
            var child = child(work);
            if (child != null) return child;
            if (work.step()) throw new AssertionError("outer completed before child");
        }
        throw new AssertionError("child not reached");
    }

    private static PlanningBudget timedBudget(AtomicLong clock) {
        return new PlanningBudget(1, 20_000_000, 512L << 20, () -> false, clock::get);
    }

    private static PlanningBudget.Exhausted exhaust(PlanningBudget budget, AtomicLong clock, PlanningBudget.Limit kind) {
        var exhausted = new AtomicReference<PlanningBudget.Exhausted>();
        // The production child has completed a proof/witness, while a sibling
        // sharing its budget hits an independent cap before parent publication.
        CompletableFuture.runAsync(() -> {
            try {
                if (kind == PlanningBudget.Limit.SEARCH_LIMIT) budget.charge(budget.remainingWork() + 1);
                else if (kind == PlanningBudget.Limit.MEMORY_LIMIT) budget.reserve(Long.MAX_VALUE);
                else if (kind == PlanningBudget.Limit.TIMEOUT) { clock.set(2_000_000); budget.checkpoint(); }
                else throw new AssertionError("unsupported fault kind");
            } catch (PlanningBudget.Exhausted limit) { exhausted.set(limit); }
        }).join();
        check(exhausted.get() != null && exhausted.get().limit() == kind, "wrong injected limit");
        return exhausted.get();
    }

    private static void assertLimit(CapturedCatalog.Result<String> result, PlanningBudget.Limit limit, String name) {
        check(result.plan().result().name().equals(limit.name()), name + " promoted local failure: " + result.plan().result());
        check(result.plan().missingExact().isEmpty(), name + " retained a restricted missing preview");
    }

    private static void clean(PlanningBudget budget, String scope) {
        check(budget.reservedBytes() == 0, scope + " memory leak: " + budget.reservedBytes());
    }

    @SuppressWarnings("unchecked")
    private static GraphPlanningWork<String> child(CapturedCatalog<String>.Work work) throws Exception {
        return (GraphPlanningWork<String>) read(work, "solving");
    }

    private static Object read(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static CapturedCatalog.Entry<String> entry(String id, String input) {
        var candidate = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(input, 1), null, false);
        var recipe = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(candidate))),
                List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        return new CapturedCatalog.Entry<>(id, id, recipe);
    }

    private static CapturedCatalog<String> alternatives() {
        var recipe = new CapturedRecipe<>(List.of(input("raw", 300, 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        return new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "r", recipe)));
    }
}
