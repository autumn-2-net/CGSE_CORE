// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-scan finite dependency closure is independent of the retained index walk. */
public final class CapturedStateSupportTest {
    private static int checks, models;
    private record Family(int kind) implements StateFamily<String> {
        public String id() { return "family:" + kind; }
        public boolean accepts(String key) { return key.startsWith("tool:" + kind + ":"); }
        public long capacity(String key) { return accepts(key) ? Long.MAX_VALUE : 0; }
        public String nominalAfterOne(String key) { throw new AssertionError("Support discovery must not unfold state transitions"); }
    }

    public static void main(String[] args) {
        var random = new Random(0x20346ef);
        for (int run = 0; run < 1200; run++) {
            var entries = new ArrayList<CapturedCatalog.Entry<String>>();
            int count = 6 + random.nextInt(24);
            for (int i = 0; i < count; i++) {
                var inputs = new ArrayList<CapturedRecipe.Input<String>>();
                for (int slot = 0, n = 1 + random.nextInt(2); slot < n; slot++) {
                    var choices = new ArrayList<CapturedRecipe.Candidate<String>>();
                    for (int c = 0, m = 1 + random.nextInt(3); c < m; c++) {
                        boolean state = random.nextInt(3) == 0; int kind = random.nextInt(3);
                        String key = state ? "tool:" + kind + ":" + random.nextInt(3) : "material:" + random.nextInt(count + 5);
                        String after = random.nextBoolean() ? null : random.nextBoolean() ? key : "tool:" + random.nextInt(3) + ":" + random.nextInt(3);
                        int shape = random.nextInt(10);
                        choices.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, shape == 0 ? 2 : 1), after,
                                shape == 1, shape == 2, state ? new Family(kind) : null));
                    }
                    inputs.add(new CapturedRecipe.Input<>(1 + random.nextInt(2), choices));
                }
                var output = new CapturedRecipe.Amount<>("material:" + i, 1);
                entries.add(new CapturedCatalog.Entry<>("source:" + i, "binding:" + i, new CapturedRecipe<>(inputs, List.of(output), false, false)));
            }
            var seeds = run % 3 == 0 ? Set.of("tool:1:1") : Set.<String>of();
            compare(entries, "material:" + random.nextInt(count), seeds);
            models++;
        }
        limits();
        System.out.println("CapturedStateSupportTest: models=" + models + "; assertions=" + checks);
    }

    private static void compare(List<CapturedCatalog.Entry<String>> entries, String target, Set<String> seeds) {
        var wanted = new HashSet<String>(seeds); wanted.add(target);
        var families = new HashSet<Integer>(); var selected = new HashSet<Integer>();
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < entries.size(); i++) {
                var recipe = entries.get(i).recipe(); var emitted = new HashSet<String>();
                for (var output : recipe.outputs()) if (output.amount() > 0) emitted.add(output.what());
                for (var input : recipe.inputs()) for (var choice : input.candidates()) if (choice.remaining() != null) emitted.add(choice.remaining());
                boolean relevant = false;
                for (String key : emitted) {
                    relevant |= wanted.contains(key);
                    if (key.startsWith("tool:")) relevant |= families.contains(Integer.parseInt(key.split(":")[1]));
                }
                if (!relevant || !selected.add(i)) continue;
                changed = true;
                for (var input : recipe.inputs()) for (var choice : input.candidates()) {
                    wanted.add(choice.stack().what());
                    if (choice.family() instanceof Family family && choice.stack().amount() == 1 && !choice.configuration() && !choice.reusable()) families.add(family.kind());
                }
            }
        } while (changed);
        var expected = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < entries.size(); i++) if (selected.contains(i)) expected.add(entries.get(i));
        var budget = budget(40_000_000, 8L << 20);
        try (var work = CapturedStateSupport.create(entries, index(entries), target, seeds, budget)) {
            check(work != null, "Small support not admitted"); int steps = 0;
            while (!work.step()) check(++steps < 20_000, "Retained scan restarted or failed to converge");
            if (expected.isEmpty() || expected.size() == entries.size()) check(work.result() == null, "Published an unchanged source set");
            else {
                check(expected.equals(work.result()), "Indexed closure differs from full finite fixed point");
                for (int i = 0; i < expected.size(); i++) check(expected.get(i) == work.result().get(i), "Copied / reordered original capture semantics");
            }
        }
        check(budget.reservedBytes() == 0 && budget.searchWork() == 0, "Compiler support leaked or consumed search allowance");
    }

    static Map<String, List<Integer>> index(List<CapturedCatalog.Entry<String>> entries) {
        var index = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < entries.size(); i++) {
            var recipe = entries.get(i).recipe(); if (recipe.size() == 0) continue;
            var emitted = new HashSet<String>();
            for (var output : recipe.outputs()) if (output.amount() > 0) emitted.add(output.what());
            for (var input : recipe.inputs()) for (var choice : input.candidates()) if (choice.remaining() != null) emitted.add(choice.remaining());
            for (String key : emitted) index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        return index;
    }

    private static void limits() {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        var state = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:0:0", 1), "tool:0:0", false, false, new Family(0))));
        entries.add(new CapturedCatalog.Entry<>("target", "target", new CapturedRecipe<>(List.of(state), List.of(new CapturedRecipe.Amount<>("target", 1)), false, false)));
        for (int i = 0; i < 10_000; i++) entries.add(new CapturedCatalog.Entry<>("noise:" + i, "noise:" + i,
                new CapturedRecipe<>(List.of(), List.of(new CapturedRecipe.Amount<>("noise:" + i, 1)), false, false)));
        var index = index(entries); var budget = budget(40_000_000, 8L << 20);
        try (var work = CapturedStateSupport.create(entries, index, "target", Set.of(), budget)) {
            while (!work.step()) {}
            check(work.result().equals(List.of(entries.get(0))), "Large unrelated producer index changed support");
        }
        check(budget.reservedBytes() == 0 && budget.peakBytes() < 8192, "Selector copied whole producer index / capture");
        check(index.size() == 10_002, "Selector mutated caller index");
        for (long bytes : new long[]{1024, 2048, 2300, 2600}) {
            budget = budget(40_000_000, bytes);
            try (var work = CapturedStateSupport.create(entries, index, "target", Set.of(), budget)) {
                if (work != null) { while (!work.step()) {} check(work.result() == null, "Tight workspace published partial support"); }
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Support memory refusal leaked");
        }
        budget = budget(16_384, 8L << 20);
        try (var work = CapturedStateSupport.create(entries, index, "target", Set.of(), budget)) {
            while (!work.step()) {}
            check(work.result() == null && budget.diagnostics().contains("reason=work_limit"), "Incomplete family scan became full support");
        }
        check(budget.reservedBytes() == 0 && budget.nodes() <= 1025, "Support renewed local work quota");
        var cancel = new AtomicBoolean();
        budget = new PlanningBudget(0, 40_000_000, 8L << 20, cancel::get, System::nanoTime);
        try (var work = CapturedStateSupport.create(entries, index, "target", Set.of(), budget)) {
            for (int i = 0; i < 50; i++) check(!work.step(), "Large scan completed without reading all indexed keys");
            cancel.set(true); boolean stopped = false;
            try { work.step(); } catch (java.util.concurrent.CancellationException expected) { stopped = true; }
            check(stopped, "Support scan ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancelled selector leaked");
        var wide = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < 129; i++) wide.add(new CapturedCatalog.Entry<>("producer:" + i, "producer:" + i,
                new CapturedRecipe<>(List.of(), List.of(new CapturedRecipe.Amount<>("target", 1)), false, false)));
        budget = budget(40_000_000, 8L << 20);
        try (var work = CapturedStateSupport.create(wide, index(wide), "target", Set.of(), budget)) {
            while (!work.step()) {}
            check(work.result() == null && budget.diagnostics().contains("reason=source_limit"), "Truncated a requested provider list");
        }
        check(budget.reservedBytes() == 0, "Wide support leaked");
    }

    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
