// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Rounded composite counts, accepted-incumbent ownership and interruption. */
public final class RefillAcceptedPeriodsTest {
    private static int checks;
    record Fixture(GraphPlan<String> plan, Map<String, Long> stock, long needed) {}

    static Fixture fixture(int length, int period, long goal, long seed, long joint, boolean reverse, boolean wrappers) {
        var base = RefillNormalizedWordTest.fixture(length / 2, 2, 1, goal, seed, joint, false, reverse, 3);
        var word = new ArrayList<PlanStep>();
        long rounds = period * ((Math.max(goal + seed, joint) + period - 1) / period + 2);
        for (int round = 0; round < rounds; round++) {
            int cut = 1 + round % period;
            for (int part = 0; part < 2; part++) {
                var segment = new ArrayList<PlanStep>();
                for (int i = part == 0 ? 0 : cut; i < (part == 0 ? cut : length); i++)
                    segment.add(new PlanStep.Batch("r" + i, 1));
                PlanStep atom = new PlanStep.Sequence(segment);
                if (wrappers) atom = new PlanStep.Sequence(List.of(new PlanStep.Repeat(atom, 1)));
                word.add(new PlanStep.Repeat(atom, 1));
            }
        }
        word.add(new PlanStep.Batch("finish", 1));
        var p = RefillWordPruningTest.literalPlan(new ArrayList<>(base.plan().recipes().values()),
                new PlanStep.Sequence(word), base.plan().seeds(), base.stock(), Set.of());
        return new Fixture(p, base.stock(), base.needed());
    }
    public static void main(String[] args) throws Exception {
        families(); contracts(); interruptions(); handoff();
        System.out.println("RefillAcceptedPeriodsTest: assertions=" + checks);
    }
    private static void families() {
        int total = 0, atBound = 0;
        for (int length : new int[]{4, 8, 12}) for (int period : new int[]{2, 3, 4}) if (period < length)
        for (long goal : new long[]{1, period + 1}) for (long seed : new long[]{0, 2})
        for (long joint : new long[]{0, period + 2}) for (boolean reverse : List.of(false, true))
        for (boolean wrappers : List.of(false, true)) {
            var f = fixture(length, period, goal, seed, joint, reverse, wrappers);
            var b = budget(); GraphPlan<String> result;
            try (var owner = owner(f.plan, b)) {
                while (!owner.step()) {} long held = b.reservedBytes(), before = b.nodes();
                result = reduce(f.plan, f.stock, Set.of(), true, false, b, 8192, owner.summary());
                check(b.reservedBytes() == held && b.nodes() - before <= 8256, "Borrowed owner / quality quota");
            }
            check(b.reservedBytes() == 0 && b.cachePreparationWork() == 0, "Private lease/account leak");
            total++;
            if (result.initialExact().get("feed").equals(BigInteger.valueOf(f.needed))) atBound++;
            else {
                b = budget();
                try (var owner = owner(f.plan, b)) {
                    while (!owner.step()) {}
                    result = reduce(f.plan, f.stock, Set.of(), true, false, b, 16384, owner.summary());
                }
                check(b.reservedBytes() == 0, "Diagnostic workspace");
            }
            // Each round costs one feed and grows A/joint by one. The final
            // order and restart reserve impose max(goal+max(seed,3),joint).
            check(result.initialExact().get("feed").equals(BigInteger.valueOf(f.needed)),
                    "Missed period bound " + length + "/" + period + "/" + goal + "/" + seed + "/" + joint + "/" + wrappers);
            check(result.initialExact().get("A").equals(BigInteger.valueOf(3)), "Startup seed changed");
            check(result.initialExact().get("tool").equals(BigInteger.ONE), "Lost returned tool");
            for (int i = 0; i < length; i++) check(result.patternTimesExact().get("r" + i).equals(BigInteger.valueOf(f.needed)), "Split required cycle");
        }
        System.out.println("Accepted periods: default bound=" + atBound + "/" + total);
        check(total == 256 && atBound == total, "Default period quality regressed");
    }
    private static void contracts() throws Exception {
        // Five required rounds round up to six in the period-2 incumbent;
        // the period pass must still improve it while retaining three A.
        var f = fixture(4, 2, 2, 2, 0, false, false);
        var original = f.plan; var b = budget();
        try (var owner = owner(original, b)) {
            while (!owner.step()) {}
            try (var work = new RefillWordPruning<>(original, f.stock, Set.of(), true, false, b, 8192, owner.summary())) {
                reach(work, "PERIODS");
                check(work.result().initialExact().get("feed").equals(BigInteger.valueOf(6)), "No completed partial reduction");
                run(work, b);
                check(work.result().initialExact().get("feed").equals(BigInteger.valueOf(5)), "Follow-up did not improve accepted word");
                verify(original, work.result(), f.stock, Set.of());
            }
        }
        check(b.reservedBytes() == 0, "Accepted leases");
        var integral = fixture(4, 2, 1, 0, 0, false, false); b = budget();
        try (var owner = owner(integral.plan, b)) {
            while (!owner.step()) {}
            try (var work = new RefillWordPruning<>(integral.plan, integral.stock, Set.of(), true, false, b, 8192, owner.summary())) {
                while (!work.step()) check(!phase(work).startsWith("PERIOD"), "Integral active bound admitted extra work");
                check(work.result().initialExact().get("feed").equals(BigInteger.valueOf(4)), "Integral supply bound");
            }
        }
        check(b.reservedBytes() == 0, "Integral leases");

        original = RefillWordPruningTest.literalPlan(new ArrayList<>(f.plan.recipes().values()), f.plan.steps(), f.plan.seeds(), f.stock, Set.of("feed"));
        b = budget(); var result = reduce(original, f.stock, Set.of("feed"), true, false, b, 8192, null);
        check(!result.missingExact().containsKey("feed") && result.initialExact().get("feed").equals(BigInteger.valueOf(5)), "External follow-up supply");
        check(b.reservedBytes() == 0, "External leases");

        original = new GraphPlan<>(f.plan.target(), f.plan.amount(), f.plan.preserveSeeds(), f.plan.steps(), f.plan.recipes(),
                f.plan.initialExact(), f.plan.seeds(), f.plan.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0, 0);
        b = budget(); result = reduce(original, f.stock, Set.of(), true, false, b, 8192, null);
        check(result.result() == GraphPlan.Result.INCOMPLETE_MODEL && result.initialExact().get("feed").equals(BigInteger.valueOf(5)), "Promoted incomplete coverage");
        check(b.reservedBytes() == 0, "Incomplete leases");

        var recipes = new ArrayList<>(f.plan.recipes().values());
        var last = recipes.get(3); var outputs = new LinkedHashMap<>(last.outputs()); outputs.put("G", 1L);
        recipes.set(3, new GraphRecipe<>(last.id(), last.binding(), last.slots(), outputs));
        original = RefillWordPruningTest.literalPlan(recipes, f.plan.steps(), f.plan.seeds(), f.stock, Set.of());
        b = budget(); result = reduce(original, f.stock, Set.of(), true, false, b, 8192, null);
        check(result.patternTimesExact().equals(original.patternTimesExact()), "Lost forced target surplus");
        check(b.reservedBytes() == 0, "Forced credit leases");
        b = budget(); result = reduce(original, f.stock, Set.of(), false, false, b, 8192, null);
        // The loop itself supplies G, so ordinary mode can omit finish while
        // retaining A=4 after one round (above the restart reserve of three).
        check(result.initialExact().get("feed").equals(BigInteger.ONE), "Kept unrequested surplus");
        check(b.reservedBytes() == 0, "Ordinary credit leases");

        recipes = new ArrayList<>(f.plan.recipes().values());
        var first = recipes.get(0); var slots = new ArrayList<>(first.slots()); slots.add(new GraphRecipe.Slot<>("configuration", 1L, 0, true));
        recipes.set(0, new GraphRecipe<>(first.id(), first.binding(), slots, first.outputs()));
        original = RefillWordPruningTest.literalPlan(recipes, f.plan.steps(), f.plan.seeds(), f.stock, Set.of()); b = budget();
        check(reduce(original, f.stock, Set.of(), true, false, b, 8192, null) == original, "Changed configuration dispatch");
        check(b.reservedBytes() == 0, "Configuration leases");
    }
    private static void interruptions() throws Exception {
        var f = fixture(4, 2, 2, 2, 0, false, false);
        for (String at : List.of("PERIODS", "PERIOD_PRUNE")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var time = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100000, 64L << 20, cancelled::get, time::get);
            b.failureDetail("retained proof"); long held = 0;
            try (var owner = owner(f.plan, b)) {
                while (!owner.step()) {} long ownerBytes = b.reservedBytes();
                try (var work = new RefillWordPruning<>(f.plan, f.stock, Set.of(), true, false, b, 8192, owner.summary())) {
                    reach(work, at); var accepted = work.result();
                    check(accepted != f.plan && accepted.initialExact().get("feed").equals(BigInteger.valueOf(6)), "Missing accepted partial bill");
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> time.set(2000000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancelled.set(true);
                    }
                    try { run(work, b); check(!mode.equals("cancel"), "Ignored cancellation"); }
                    catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                    finally { b.release(held); }
                    check(work.result() == accepted, "Lost accepted bill on optional interruption");
                    verify(f.plan, work.result(), f.stock, Set.of());
                }
                check(b.reservedBytes() == ownerBytes, "Released borrowed owner / leaked child");
            }
            check(b.reservedBytes() == 0 && b.failureDetail().equals("retained proof"), "Lost proof / lease");
        }
        for (long cap : new long[]{0, 64, 512, 1024, 2048, 3072, 4096, 6144, 8192}) {
            var b = budget();
            reduce(f.plan, f.stock, Set.of(), true, false, b, cap, null);
            check(b.nodes() <= cap + 64 && b.reservedBytes() == 0, "Quality quota exit");
        }
    }
    private static void handoff() throws Exception {
        var f = fixture(4, 2, 2, 2, 0, false, false);
        for (String at : List.of("PERIODS", "PERIOD_PRUNE")) {
            var b = budget();
            try (var owner = owner(f.plan, b)) {
                while (!owner.step()) {} long held = b.reservedBytes();
                try (var work = new RefillWordPruning<>(f.plan, f.stock, Set.of(), true, false, b, 8192, owner.summary())) {
                    reach(work, at);
                    try (var scheduler = new PlanningScheduler(1, 4, 64, 1000000000)) {
                        var task = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                            public boolean advance(PlanningScheduler.Slice slice) { while (slice.nextUncharged()) if (work.step()) return true; return false; }
                            public GraphPlan<String> result() { return work.result(); }
                            public void close() { work.close(); }
                        }, b);
                        var result = task.get(10, TimeUnit.SECONDS); verify(f.plan, result, f.stock, Set.of());
                        check(result.initialExact().get("feed").equals(BigInteger.valueOf(5)), "Worker lost accepted period");
                    }
                }
                check(b.reservedBytes() == held, "Transferred borrowed owner");
            }
            check(b.reservedBytes() == 0, "Worker workspace leak");
        }
    }
    private static GraphPlan<String> reduce(GraphPlan<String> p, Map<String, Long> stock, Set<String> external, boolean force,
                                           boolean ignore, PlanningBudget b, long cap, SequenceSummary<String> known) {
        GraphPlan<String> result;
        try (var work = new RefillWordPruning<>(p, stock, external, force, ignore, b, cap, known)) { run(work, b); result = work.result(); }
        verify(p, result, stock, external); return result;
    }
    private static void run(RefillWordPruning<?> work, PlanningBudget b) {
        while (true) { long before = b.nodes(); boolean done = work.step(); check(b.nodes() - before <= 64, "Unbounded continuation"); if (done) return; }
    }
    private static void verify(GraphPlan<String> original, GraphPlan<String> result, Map<String, Long> stock, Set<String> external) {
        RefillWordPruningTest.literalVerify(result, stock, external);
        result.initialExact().forEach((k, n) -> check(n.compareTo(original.initialExact().getOrDefault(k, BigInteger.ZERO)) <= 0, "Increased withdrawal"));
        result.missingExact().forEach((k, n) -> check(n.compareTo(original.missingExact().getOrDefault(k, BigInteger.ZERO)) <= 0, "Increased shortage"));
        check(result.recipes().equals(original.recipes()) && result.seeds().equals(original.seeds()), "Changed binding/seeds");
    }
    private static String phase(RefillWordPruning<?> work) throws Exception {
        var field = RefillWordPruning.class.getDeclaredField("phase"); field.setAccessible(true); return field.get(work).toString();
    }
    private static void reach(RefillWordPruning<?> work, String name) throws Exception {
        for (int i = 0; !phase(work).equals(name); i++) check(i < 10000 && !work.step(), "No suspended " + name);
    }
    private static PlanVerification<String> owner(GraphPlan<String> p, PlanningBudget b) {
        return PlanVerification.refill(GraphPlan.ownedRefill(p, p.steps(), p.recipes(), p.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE, 0), b);
    }
    private static PlanningBudget budget() { return MissingMaterialExpansionTest.budget(100000); }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
