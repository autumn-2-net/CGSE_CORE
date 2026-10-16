// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Structural oracles for optional, bounded discovery of undeclared state returns. */
public final class CapturedStateRecoveryTest {
    private static int checks, models;
    private static final class Rule implements StateFamily<String> {
        final String prefix;
        int transitions;
        Rule(int number) { prefix = "tool:" + number + ":"; }
        public String id() { return prefix; }
        public boolean accepts(String key) { return key.startsWith(prefix); }
        public long capacity(String key) { return Long.parseLong(key.substring(prefix.length())); }
        public String nominalAfterOne(String key) {
            transitions++; long n = capacity(key); return n == 1 ? "shell:" + prefix : prefix + (n - 1);
        }
    }

    public static void main(String[] args) {
        chains(); bounded(); limits();
        System.out.println("CapturedStateRecoveryTest: models=" + models + "; assertions=" + checks);
    }

    private static void chains() {
        var random = new Random(0x56ace714L);
        for (int depth = 1; depth <= 6; depth++) for (int order = 0; order < 10; order++) {
            var entries = new ArrayList<CapturedCatalog.Entry<String>>(); var rules = new ArrayList<Rule>();
            entries.add(entry("finish", List.of(ordinary("tool:0:2")), "product"));
            for (int i = 0; i < depth; i++) {
                var rule = new Rule(i); rules.add(rule);
                entries.add(entry("step:" + i, List.of(state(rule, 4), ordinary(i + 1 < depth ? "tool:" + (i + 1) + ":2" : "raw")), "waste:" + i));
            }
            for (int i = 0; i < 150; i++) entries.add(entry("noise:" + i, List.of(ordinary("absent:" + i)), "unused:" + i));
            if (order == 1) Collections.reverse(entries);
            else if (order > 1) Collections.shuffle(entries, random);
            var expected = entries.stream().filter(e -> !e.id().startsWith("noise:")).toList();
            var index = CapturedStateSupportTest.index(entries); var original = new LinkedHashMap<String, List<Integer>>();
            index.forEach((key, values) -> original.put(key, List.copyOf(values)));
            var retained = new CapturedStateProducers<String>();
            for (int pass = 0; pass < 3; pass++) {
                var b = budget(40_000_000, 8L << 20);
                try (var work = CapturedStateSupport.recover(entries, index, "product", Set.of(), b, pass == 0 ? null : retained, 20_000_000)) {
                    check(work != null, "Declined bounded implicit dependency chain");
                    while (!work.step()) {}
                    check(expected.equals(work.result()), "Implicit dependency fixed point depends on source order");
                    for (int i = 0; i < expected.size(); i++) check(expected.get(i) == work.result().get(i), "Changed original capture identity / priority");
                }
                check(index.equals(original), "Mutated canonical producer index");
                check(b.searchWork() == 0 && b.reservedBytes() == 0 && b.compilationWork() <= 262_144, "Recovery escaped its accounting");
            }
            for (var rule : rules) check(rule.transitions < 200, "Recursively enumerated unused states");
            models++;
        }
    }

    private static void bounded() {
        // The terminal shell is outside accepts(). Its exact one-step return
        // must still select the source; sixteen thousand other rules do not
        // authorize discarding the useful support when preparation runs out.
        for (boolean reverse : List.of(false, true)) {
            var rule = new Rule(-1); String terminal = "shell:" + rule.prefix;
            var entries = new ArrayList<CapturedCatalog.Entry<String>>();
            var use = entry("use", List.of(state(rule, 2), ordinary("raw")), "part");
            var finish = entry("finish", List.of(ordinary(terminal)), "product");
            entries.add(use); entries.add(finish);
            for (int i = 0; i < 16_384; i++) entries.add(entry("noise:" + i, List.of(state(new Rule(i), 2), ordinary("absent:" + i)), "unused:" + i));
            if (reverse) Collections.reverse(entries);
            var expected = entries.stream().filter(e -> e == use || e == finish).toList();
            var b = budget(40_000_000, 8L << 20);
            try (var work = CapturedStateSupport.recover(entries, CapturedStateSupportTest.index(entries), "product", Set.of(), b, null, 20_000_000)) {
                while (!work.step()) {}
                check(expected.equals(work.result()), "Bounded scan lost already discovered state support");
                check(b.diagnostics().contains("bounded;"), "Case did not reach the incomplete-support boundary");
            }
            check(rule.transitions < 16 && b.nodes() <= 262_144 && b.searchWork() == 0 && b.reservedBytes() == 0, "Bounded support reset quota or unfolded wear");
            models++;
        }
    }

    private static void limits() {
        var rule = new Rule(0); var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("finish", List.of(ordinary("tool:0:2")), "product"));
        entries.add(entry("use", List.of(state(rule, 4), ordinary("raw")), "part"));
        for (int i = 0; i < 10_000; i++) entries.add(entry("noise:" + i, List.of(), "unused:" + i));
        var index = CapturedStateSupportTest.index(entries);
        for (long maximum : new long[]{0, 4095, 4096, 16_384, 40_000, Long.MAX_VALUE}) {
            var b = budget(40_000_000, 8L << 20);
            var retained = new CapturedStateProducers<String>();
            try (var work = CapturedStateSupport.recover(entries, index, "product", Set.of(), b, retained, maximum)) {
                if (maximum < 4096) check(work == null, "Ignored parent remainder");
                else {
                    check(work != null, "Refused available remainder"); while (!work.step()) {}
                    var result = work.result();
                    if (result != null) check(result.stream().allMatch(e -> e == entries.get(0) || e == entries.get(1)), "Partial discovery invented a producer");
                    check(b.nodes() <= Math.min(262_144, maximum / 4), "Renewed parent preparation allowance");
                }
            }
            check(b.reservedBytes() == 0 && b.searchWork() == 0, "Limited recovery leaked / spent search quota");
            if (maximum <= 40_000)
                check(retained.get(rule, index, budget(40_000_000, 8L << 20)) == null, "Bounded support published an incomplete family projection");
        }
        for (long bytes : new long[]{1024, 2048, 2300, 2600, 4096}) {
            var b = budget(40_000_000, bytes);
            try (var work = CapturedStateSupport.recover(entries, index, "product", Set.of(), b, null, 20_000_000)) {
                if (work != null) { while (!work.step()) {} }
            }
            check(b.reservedBytes() == 0 && b.peakBytes() <= bytes, "Memory refusal leaked");
        }
        var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 40_000_000, 8L << 20, cancelled::get, System::nanoTime);
        try (var work = CapturedStateSupport.recover(entries, index, "product", Set.of(), b, null, 20_000_000)) {
            for (int i = 0; i < 50; i++) check(!work.step(), "Failed to yield large discovery");
            cancelled.set(true);
            try { work.step(); throw new AssertionError("Ignored cancellation"); }
            catch (java.util.concurrent.CancellationException expected) { checks++; }
        }
        check(b.reservedBytes() == 0, "Cancelled recovery leaked");
    }

    private static CapturedRecipe.Input<String> state(Rule rule, long n) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>(rule.prefix + n, 1), rule.prefix + (n - 1), false, false, rule)));
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String out) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(out, 1)), false, false));
    }
    private static PlanningBudget budget(long work, long bytes) { var b = new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); b.enableMetrics(); return b; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
