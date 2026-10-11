// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.cgse.core.CapturedRecipe.InputMode.*;
import static org.cgse.core.CapturedRecipeDomainTest.*;

public final class CapturedInputModeTest {

    private static final long WORK = 20_000_000, MEMORY = 128L << 20;

    public static void main(String[] args) throws Exception {
        legacyAndExplicit();
        exhaustiveSmallDomains();
        longDomains();
        allocationAndPlanning();
        mixedAndWholeSlots();
        flowAndLargeBatchRecovery();
        returnsAndReadArcs();
        limits();
        oldAndNewArchives();
        System.out.println("CapturedInputModeTest passed");
    }

    private static void legacyAndExplicit() {
        check(input("k", 2, 3).mode() == LEGACY, "two-argument constructor changed");
        for (boolean external : new boolean[]{false, true}) for (long copies : new long[]{3, 10}) {
            var legacy = recipe(copies, 2, LEGACY, external);
            var whole = recipe(copies, 2, INDIVISIBLE, external);
            var mixed = recipe(copies, 2, MIXED, external);
            check(legacy.size() == (!external && copies <= 9 ? copies + 1 : 2), "legacy selection changed");
            check(whole.size() == 2 && mixed.size() == copies + 1, "explicit mode still depends on external/9");
            try (var domain = whole.domain(budget())) {
                expect(IllegalArgumentException.class, () -> domain.ordinal(List.of(Map.of(0, copies - 1, 1, 1L))));
            }
        }
        expect(NullPointerException.class, () -> new CapturedRecipe.Input<>(1, input("k", 2, 1).candidates(), null));
    }

    private static void exhaustiveSmallDomains() {
        int checked = 0;
        for (int n = 2; n <= 5; n++) for (int copies = 1; copies <= 12; copies++) {
            var expected = new ArrayList<int[]>();
            for (int i = 0; i < n; i++) { int[] single = new int[n]; single[i] = copies; expected.add(single); }
            enumerate(new int[n], 0, copies, expected);
            var recipe = recipe(copies, n, MIXED, true);
            var budget = budget();
            var expansion = recipe.expand(budget());
            try (var domain = recipe.domain(budget)) {
                check(domain.size().equals(BigInteger.valueOf(expected.size())), "small cardinality");
                for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
                    var resolved = domain.next();
                    var allocations = new LinkedHashMap<Integer, Long>();
                    var slots = new ArrayList<GraphRecipe.Slot<String>>();
                    for (int at = 0; at < n; at++) if (expected.get(ordinal)[at] > 0) {
                        long amount = expected.get(ordinal)[at];
                        allocations.put(at, amount); slots.add(new GraphRecipe.Slot<>("k" + at, amount, 0));
                    }
                    check(resolved.slots().equals(slots), "small vector ordering " + n + "/" + copies + "/" + ordinal);
                    check(domain.ordinal(List.of(allocations)).intValueExact() == ordinal, "small rank round trip");
                    if (ordinal < CapturedRecipe.MAX_VARIANTS) check(expansion.next().equals(resolved), "fast prefix differs from full domain");
                    if (copies >= 10 && ordinal % 17 == 0) good(certificate(recipe, BigInteger.valueOf(ordinal)));
                    checked++;
                }
                check(!expansion.hasNext(), "fast prefix exceeds advertised size");
            }
            check(budget.reservedBytes() == 0, "small domain ownership");
        }
        check(checked > 5000, "small oracle did not cover enough variants");
        System.out.println("CapturedInputModeTest small-domain oracle variants=" + checked);
    }

    private static void longDomains() {
        long peakWork = 0, peakBytes = 0;
        for (int n : new int[]{2, 3, 8}) for (long copies : new long[]{10, 1_000_000_000_000L, Long.MAX_VALUE}) {
            var recipe = recipe(copies, n, MIXED, true);
            var budget = budget();
            try (var domain = recipe.domain(budget)) {
                BigInteger expected = BigInteger.ONE;
                for (int i = 1; i < n; i++) expected = expected.multiply(BigInteger.valueOf(copies).add(BigInteger.valueOf(i))).divide(BigInteger.valueOf(i));
                check(domain.size().equals(expected) && domain.captureComplete(), "long cardinality was truncated");
                for (var ordinal : List.of(BigInteger.ZERO, BigInteger.valueOf(n), expected.divide(BigInteger.TWO), expected.subtract(BigInteger.ONE))) {
                    var resolved = domain.resolve(ordinal);
                    long total = 0;
                    var allocation = new LinkedHashMap<Integer, Long>();
                    for (var slot : resolved.slots()) {
                        total = Math.addExact(total, slot.amount());
                        allocation.put(Integer.parseInt(slot.key().substring(1)), slot.amount());
                    }
                    check(total == copies && domain.ordinal(List.of(allocation)).equals(ordinal), "long rank round trip");
                    good(certificate(recipe, ordinal));
                }
                check(domain.coveredPrefix().signum() == 0, "random access claimed complete enumeration");
                check(budget.compilationWork() < 100_000 && budget.peakBytes() < 1_000_000, "long copies were enumerated/reserved individually");
                peakWork = Math.max(peakWork, budget.compilationWork());
                peakBytes = Math.max(peakBytes, budget.peakBytes());
            }
            check(budget.reservedBytes() == 0, "long domain ownership");
        }
        var expansion = recipe(Long.MAX_VALUE, 2, MIXED, true).expand(budget());
        int emitted = 0;
        while (expansion.hasNext()) { expansion.next(); emitted++; }
        check(emitted == 256, "long first window failed to terminate");
        System.out.println("CapturedInputModeTest long-domain peak_work=" + peakWork + " peak_bytes=" + peakBytes);
    }

    private static void allocationAndPlanning() {
        for (long copies : new long[]{10, 1_000_000_000_000L, Long.MAX_VALUE}) {
            var stock = Map.of("k0", copies / 2, "k1", copies - copies / 2);
            var mixed = recipe(copies, 2, MIXED, true);
            var budget = budget();
            try (var domain = mixed.domain(budget); var allocation = new CapturedInputAllocation<>(mixed, domain, stock, Set.of(), budget)) {
                while (!allocation.step()) { }
                check(!allocation.results().isEmpty(), "explicit long mixed stock allocation declined");
                var resolved = domain.resolve(allocation.result());
                var consumed = new LinkedHashMap<String, Long>();
                resolved.slots().forEach(slot -> consumed.merge(slot.key(), slot.amount(), Math::addExact));
                check(consumed.equals(stock), "long allocation changed copies");
            }
            check(budget.reservedBytes() == 0, "allocation ownership");
            var solved = catalog(mixed).plan("out", 1, stock, false, true, budget());
            check(solved.plan().feasible(), "explicit mixed order not solved: " + copies + "/" + solved.plan().result());
            PlanVerifier.verify(solved.plan());
            var whole = catalog(recipe(copies, 2, INDIVISIBLE, true)).plan("out", 1, stock, false, true, budget());
            check(!whole.plan().feasible(), "indivisible capture reused mixed witness");
            var legacy = catalog(recipe(copies, 2, LEGACY, false)).plan("out", 1, stock, false, true, budget());
            check(!legacy.plan().feasible(), "legacy >9 unexpectedly mixed");
            check(catalog(mixed).plan("out", 1, stock, false, true, budget()).plan().feasible(), "changed-mode failure poisoned next mixed request");
        }
    }

    private static void returnsAndReadArcs() {
        var candidates = List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("a", 2), "can-a", true, true),
                new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("b", 3), "can-b", true, true));
        var recipe = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(10, candidates, MIXED)),
                List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var budget = budget();
        try (var domain = recipe.domain(budget)) {
            var ordinal = domain.ordinal(List.of(Map.of(0, 4L, 1, 6L)));
            var value = domain.resolve(ordinal);
            check(value.slots().equals(List.of(new GraphRecipe.Slot<>("a", 8, 0, true, true), new GraphRecipe.Slot<>("b", 18, 0, true, true))), "read/config arcs changed");
            check(value.outputs().equals(Map.of("a", 8L, "b", 18L, "can-a", 4L, "can-b", 6L, "out", 1L)), "candidate returns changed");
            good(certificate(recipe, ordinal));
            try (var allocation = new CapturedInputAllocation<>(recipe, domain, Map.of("a", 8L, "b", 18L), Set.of(), budget)) {
                while (!allocation.step()) { }
                check(allocation.results().isEmpty(), "linear stock view admitted dispatch/read semantics");
            }
        }
        check(budget.reservedBytes() == 0, "read arc ownership");
        var solved = catalog(recipe).plan("out", 1, Map.of("a", 8L, "b", 18L), false, true, budget());
        check(solved.plan().feasible(), "canonical mixed read-arc route unavailable");
        PlanVerifier.verify(solved.plan());
    }

    private static void mixedAndWholeSlots() {
        var choices = input("k", 2, 1).candidates();
        var captured = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(10, choices, MIXED),
                new CapturedRecipe.Input<>(2, choices, INDIVISIBLE)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var stock = Map.of("k0", 5L, "k1", 7L);
        var result = catalog(captured).plan("out", 1, stock, false, true, budget());
        check(result.plan().feasible(), "independent per-slot semantics did not compose");
        PlanVerifier.verify(result.plan());
        for (var recipe : result.plan().recipes().values())
            check(recipe.slots().stream().filter(slot -> slot.inputSlot() == 1).count() == 1, "indivisible slot was mixed");
        var budget = budget();
        try (var domain = captured.domain(budget)) {
            check(domain.size().intValueExact() == 22, "mixed-radix size lost whole slot");
            for (int ordinal = 0; ordinal < 22; ordinal++) good(certificate(captured, BigInteger.valueOf(ordinal)));
        }
        check(budget.reservedBytes() == 0, "multi-slot domain ownership");
        var conflict = catalog(captured).plan("out", 1, Map.of("k0", 5L, "k1", 5L), false, true, budget());
        check(!conflict.plan().feasible(), "shared material counted separately for each slot");
    }

    private static void flowAndLargeBatchRecovery() {
        var main = recipe(1000, 2, MIXED, true);
        var raw = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw", 1), null, false);
        var supply = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(raw))),
                List.of(new CapturedRecipe.Amount<>("k0", 500)), true, false);
        var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "binding", main),
                new CapturedCatalog.Entry<>("supply", "supply", supply)));
        var budget = budget();
        var result = catalog.plan("out", 1, Map.of("raw", 1L, "k1", 500L), false, true, budget);
        check(result.plan().feasible() && result.plan().patternTimes().containsKey("r#501"), "long mixed flow did not restore required ordinal");
        check(!result.coverage().complete(), "flow fell back to full enumeration");
        PlanVerifier.verify(result.plan());
        check(budget.reservedBytes() == 0, "flow ownership");

        var recipe = recipe(Long.MAX_VALUE, 2, MIXED, true);
        budget = budget();
        try (var domain = recipe.domain(budget);
             var recovery = new CapturedAllocationRecovery<>(recipe, domain, BigInteger.TWO,
                     List.of(Map.of(0, BigInteger.valueOf(Long.MAX_VALUE - 1), 1, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))), budget)) {
            while (!recovery.step()) { }
            check(recovery.complete(), "long marginal recovery unfinished");
            check(recovery.batches().equals(Map.of(BigInteger.TWO, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE)),
                    "large integral marginals were truncated or split per copy");
        }
        check(budget.reservedBytes() == 0, "long recovery ownership");
    }

    private static void limits() {
        var large = recipe(Long.MAX_VALUE, 1000, MIXED, true);
        // A 54k-bit cardinality and its product need separate retained storage,
        // plus multiplication/division temporaries admitted before computation.
        for (long cap : new long[]{4096, 10000, 20000}) {
            var lowMemory = new PlanningBudget(0, WORK, cap, () -> false, System::nanoTime);
            try { large.domain(lowMemory); throw new AssertionError("unadmitted long cardinality workspace"); }
            catch (PlanningBudget.Exhausted limit) { check(limit.limit() == PlanningBudget.Limit.MEMORY_LIMIT, "wrong long-domain limit"); }
            check(lowMemory.reservedBytes() == 0 && lowMemory.compilationWork() == 1,
                    "long binomial allocated before workspace admission");
        }
        var tiny = new PlanningBudget(0, 16, MEMORY, () -> false, System::nanoTime);
        expect(PlanningBudget.Exhausted.class, () -> large.domain(tiny));
        check(tiny.reservedBytes() == 0, "interrupted cardinality retained memory");
        var admitted = new PlanningBudget(0, WORK, 1L << 20, () -> false, System::nanoTime);
        try (var domain = large.domain(admitted)) {
            long magnitudeBytes = (domain.size().bitLength() + 7L) / 8;
            check(admitted.reservedBytes() >= 2 * magnitudeBytes, "choice cardinality omitted from retained bytes");
            long before = admitted.reservedBytes();
            long sibling = admitted.availableBytes() - 512;
            admitted.reserve(sibling);
            try {
                expect(PlanningBudget.Exhausted.class, () -> domain.resolve(BigInteger.valueOf(1000)));
                expect(PlanningBudget.Exhausted.class, () -> domain.ordinal(List.of(Map.of(0, Long.MAX_VALUE - 1, 1, 1L))));
                check(admitted.reservedBytes() == before + sibling, "failed long scratch admission retained state");
            } finally { admitted.release(sibling); }
        }
        check(admitted.reservedBytes() == 0, "long scratch ownership");
        var longProof = certificate(large, BigInteger.ZERO);
        for (long cap : new long[]{4096, 10000}) {
            var replay = CapturedProof.verify(longProof, WORK, cap);
            check(replay.coverage() == CountProof.Verdict.INCOMPLETE && replay.execution() == CountProof.Verdict.INCOMPLETE,
                    "replay arithmetic allocation cutoff became a verdict");
        }
        good(longProof);
        var budget = budget();
        try (var domain = large.domain(budget)) {
            budget.cancel();
            expect(CancellationException.class, () -> domain.resolve(BigInteger.valueOf(1000)));
        }
        check(budget.reservedBytes() == 0, "cancelled long domain ownership");
        var candidate = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("overflow", 2), null, false);
        var overflow = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(Long.MAX_VALUE, List.of(candidate), MIXED)),
                List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        budget = budget();
        try (var domain = overflow.domain(budget)) {
            long before = budget.reservedBytes();
            expect(ArithmeticException.class, domain::next);
            check(domain.coveredPrefix().signum() == 0 && budget.reservedBytes() == before, "overflow advanced domain/retained failed variant");
        }
        check(budget.reservedBytes() == 0, "overflow ownership");
    }

    private static void oldAndNewArchives() throws Exception {
        Path directory = Path.of("build/test-artifacts/captured-input-mode"); Files.createDirectories(directory);
        var proof = certificate(recipe(Long.MAX_VALUE, 3, MIXED, true), BigInteger.valueOf(Long.MAX_VALUE));
        Path path = directory.resolve("explicit.cgd");
        CapturedProof.write(path, proof);
        check(CapturedProof.read(path).equals(proof), "new archive lost mode");
        good(CapturedProof.read(path));
        var pattern = proof.snapshot().patterns().get(0);
        var original = pattern.inputs().get(0);
        var changed = new CapturedProof.Pattern(pattern.id(), pattern.binding(),
                List.of(new CapturedRecipe.Input<>(original.multiplier(), original.candidates(), INDIVISIBLE)),
                pattern.outputs(), pattern.external(), pattern.complete());
        var tampered = new CapturedProof.Certificate(new CapturedProof.Snapshot(proof.snapshot().resources(), List.of(changed), true),
                proof.request(), proof.coverage(), proof.witness());
        check(CapturedProof.verify(tampered, WORK, MEMORY).coverage() == CountProof.Verdict.INVALID, "mode tampering accepted old coverage");
        Path old = directory.resolve("legacy-v1.cgd");
        writeLegacyArchive(old);
        var legacy = CapturedProof.read(old);
        check(legacy.snapshot().patterns().get(0).inputs().get(0).mode() == LEGACY, "v1 not decoded as LEGACY");
        check(CapturedProof.verify(legacy, WORK, MEMORY).coverage() == CountProof.Verdict.VERIFIED, "old coverage no longer replays");
        CapturedProof.write(path, legacy);
        check(CapturedProof.read(path).equals(legacy), "old archive upgrade changed its meaning");
    }

    /** A minimal independent CGD1 writer; no new encoder is used for backward-compatibility evidence. */
    private static void writeLegacyArchive(Path path) throws Exception {
        try (var out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(0x43474431); out.writeInt(2); out.writeBoolean(true); out.writeInt(1);
            text(out, "r"); text(out, "binding"); out.writeBoolean(false); out.writeBoolean(true); out.writeInt(1);
            out.writeLong(3); out.writeInt(1); out.writeInt(0); out.writeLong(1); out.writeInt(-1); out.writeBoolean(false); out.writeBoolean(false);
            out.writeInt(1); out.writeInt(1); out.writeLong(1);
            out.writeInt(1); out.writeLong(1); out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeBoolean(false); out.writeBoolean(true);
            out.writeLong(1); out.writeBoolean(true); out.writeInt(1); text(out, "r"); integer(out, BigInteger.ONE); integer(out, BigInteger.ONE);
            out.writeBoolean(true); out.writeInt(0); out.writeBoolean(false);
        }
    }

    private static void text(DataOutputStream out, String text) throws Exception {
        out.writeUTF(text);
    }
    private static void integer(DataOutputStream out, BigInteger value) throws Exception {
        byte[] bytes = value.toByteArray(); out.writeInt(bytes.length); out.write(bytes);
    }

    private static void enumerate(int[] counts, int at, int left, List<int[]> values) {
        if (at == counts.length - 1) {
            counts[at] = left;
            if (Arrays.stream(counts).filter(n -> n > 0).count() > 1) values.add(counts.clone());
        } else for (int n = left; n >= 0; n--) { counts[at] = n; enumerate(counts, at + 1, left - n, values); }
    }

    private static CapturedRecipe<String> recipe(long copies, int n, CapturedRecipe.InputMode mode, boolean external) {
        return new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(copies, input("k", n, 1).candidates(), mode)),
                List.of(new CapturedRecipe.Amount<>("out", 1)), external, false);
    }
    private static CapturedCatalog<String> catalog(CapturedRecipe<String> recipe) {
        return new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "binding", recipe)));
    }
    private static CapturedProof.Certificate certificate(CapturedRecipe<String> recipe, BigInteger ordinal) {
        var budget = budget();
        try (var domain = recipe.domain(budget)) {
            var resolved = domain.resolve(ordinal);
            var graph = new GraphRecipe<>("r#" + ordinal, "binding", resolved.slots(), resolved.outputs());
            var stock = new LinkedHashMap<String, Long>();
            for (var slot : resolved.slots()) stock.merge(slot.key(), slot.amount(), Math::addExact);
            var plan = new GraphPlan<>("out", 1, false, new PlanStep.Batch(graph.id(), 1), Map.of(graph.id(), graph),
                    stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            PlanVerifier.verify(plan);
            var coverage = new CapturedCatalog.Coverage(1, List.of(new CapturedCatalog.DomainCoverage("r", BigInteger.ZERO,
                    domain.size(), List.of(ordinal), true)), false);
            return CapturedProof.capture(List.of(new CapturedCatalog.Entry<>("r", "binding", recipe)), true,
                    "out", 1, stock, Set.of(), Map.of(), false, true, new CapturedCatalog.Result<>(plan, coverage), WORK, MEMORY);
        }
    }
    private static void good(CapturedProof.Certificate proof) {
        var result = CapturedProof.verify(proof, WORK, MEMORY);
        check(result.coverage() == CountProof.Verdict.VERIFIED && result.execution() == CountProof.Verdict.VERIFIED, "independent mode replay: " + result);
    }
}
