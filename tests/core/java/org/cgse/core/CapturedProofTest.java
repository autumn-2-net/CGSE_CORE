// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.cgse.core.CapturedRecipeDomainTest.*;

public final class CapturedProofTest {

    private static final long WORK = 20_000_000, MEMORY = 128L << 20;

    public static void main(String[] args) throws Exception {
        lateAndTampered();
        mixedOrdinalOracle();
        coverageClosure();
        physicalReturnsAndSharedStock();
        compressedPrograms();
        productionAndMandatorySeeds();
        resourceIdentityAndLimits();
        System.out.println("CapturedProofTest passed");
    }

    private static void lateAndTampered() throws Exception {
        var recipe = new CapturedRecipe<>(List.of(input("a", 20, 1), input("b", 20, 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var catalog = new CapturedCatalog<>(List.of(entry("r#nested", recipe)));
        CapturedProof.Certificate proof;
        var budget = budget();
        var work = catalog.begin("out", 1, Map.of("a19", 1L, "b19", 1L), false, true, budget);
        try (work) { while (!work.step()) {} }
        check(budget.reservedBytes() == 0, "solver ownership after export boundary");
        proof = work.certificate(WORK, MEMORY);
        good(proof);
        check(!proof.coverage().complete() && proof.coverage().revision() == 2, "missing restricted scope");
        Path path = Path.of("build/test-artifacts/captured-proof/late.cgd");
        Files.createDirectories(path.getParent()); CapturedProof.write(path, proof);
        var copy = CapturedProof.read(path);
        check(proof.equals(copy), "portable round trip lost capture or witness");
        good(copy); CapturedProof.main(new String[]{path.toString()});
        Path second = path.resolveSibling("late-again.cgd"); CapturedProof.write(second, copy);
        check(Arrays.equals(Files.readAllBytes(path), Files.readAllBytes(second)), "non-deterministic encoding");
        var domain = proof.coverage().domains().get(0);
        invalidCoverage(replace(proof, new CapturedCatalog.Coverage(2, proof.coverage().domains(), true), proof.witness()));
        invalidCoverage(replace(proof, coverage(new CapturedCatalog.DomainCoverage(domain.id(), domain.expanded(), domain.total().add(BigInteger.ONE), domain.additional(), true)), proof.witness()));
        invalidCoverage(replace(proof, coverage(new CapturedCatalog.DomainCoverage(domain.id(), domain.expanded(), domain.total(), List.of(BigInteger.ONE), true)), proof.witness()));
        invalidCoverage(replace(proof, coverage(new CapturedCatalog.DomainCoverage(domain.id(), domain.expanded(), domain.total(), List.of(BigInteger.valueOf(399), BigInteger.valueOf(399)), true)), proof.witness()));
        invalidExecution(replace(proof, coverage(new CapturedCatalog.DomainCoverage(domain.id(), domain.expanded(), domain.total(), List.of(), true)), proof.witness()));
        var concrete = proof.witness().recipes().values().iterator().next();
        var badRecipe = new GraphRecipe<>(concrete.id(), "foreign-binding", concrete.slots(), concrete.outputs());
        invalidExecution(replace(proof, proof.coverage(), witness(proof, Map.of(concrete.id(), badRecipe), proof.witness().program(), proof.witness().initial())));
        var wrongOrdinal = new GraphRecipe<>("r#nested#398", concrete.binding(), concrete.slots(), concrete.outputs());
        invalidExecution(replace(proof, proof.coverage(), witness(proof, Map.of(wrongOrdinal.id(), wrongOrdinal),
                List.of(new CapturedProof.Instruction(CapturedProof.Kind.BATCH, wrongOrdinal.id(), List.of(), 1)), proof.witness().initial())));
        invalidExecution(replace(proof, proof.coverage(), witness(proof, proof.witness().recipes(), proof.witness().program(), Map.of())));
        var request = proof.request();
        invalidExecution(new CapturedProof.Certificate(proof.snapshot(), new CapturedProof.Request(request.target(), request.amount(), Map.of(), request.external(), request.requiredSeeds(), request.preserve(), request.forceCraft()), proof.coverage(), proof.witness()));
        invalidExecution(replace(proof, proof.coverage(), witness(proof, proof.witness().recipes(),
                List.of(new CapturedProof.Instruction(CapturedProof.Kind.REPEAT, null, List.of(0), 1)), proof.witness().initial())));
        byte[] bytes = Files.readAllBytes(path);
        Path broken = path.resolveSibling("broken.cgd");
        Files.write(broken, Arrays.copyOf(bytes, bytes.length - 1)); ioFailure(() -> CapturedProof.read(broken));
        Files.write(broken, Arrays.copyOf(bytes, bytes.length + 1)); ioFailure(() -> CapturedProof.read(broken));
        byte[] huge = bytes.clone(); ByteBuffer.wrap(huge).putInt(9, Integer.MAX_VALUE);
        Files.write(broken, huge); ioFailure(() -> CapturedProof.read(broken));
    }

    private static void mixedOrdinalOracle() {
        int checked = 0;
        for (int choices = 2; choices <= 6; choices++) for (int copies = 2; copies <= 6; copies++) {
            var candidates = new ArrayList<CapturedRecipe.Candidate<String>>();
            for (int i = 0; i < choices; i++) candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("k" + i, i + 1), "can" + i, false));
            var recipe = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(copies, candidates)), List.of(new CapturedRecipe.Amount<>("out", 1)), false, false);
            var source = entry("r", recipe);
            var expected = new ArrayList<int[]>();
            for (int i = 0; i < choices; i++) { int[] single = new int[choices]; single[i] = copies; expected.add(single); }
            enumerate(new int[choices], 0, copies, expected);
            var budget = budget();
            try (var domain = recipe.domain(budget)) {
                check(domain.size().intValueExact() == expected.size(), "oracle cardinality");
                for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
                    var resolved = domain.next();
                    List<GraphRecipe.Slot<String>> expectedSlots = new ArrayList<>();
                    Map<String, Long> expectedOutputs = new LinkedHashMap<>(); expectedOutputs.put("out", 1L);
                    Map<String, Long> stock = new LinkedHashMap<>();
                    for (int at = 0; at < choices; at++) if (expected.get(ordinal)[at] > 0) {
                        long n = expected.get(ordinal)[at];
                        expectedSlots.add(new GraphRecipe.Slot<>("k" + at, n * (at + 1), 0));
                        stock.put("k" + at, n * (at + 1)); expectedOutputs.put("can" + at, n);
                    }
                    check(resolved.slots().equals(expectedSlots) && resolved.outputs().equals(expectedOutputs), "independent ordinal oracle " + ordinal);
                    var graph = new GraphRecipe<>("r#" + ordinal, "binding-r", expectedSlots, expectedOutputs);
                    var certificate = synthetic(List.of(source), "out", 1, stock, false, true, graph, new PlanStep.Batch(graph.id(), 1), stock, Map.of(), Map.of());
                    good(certificate); checked++;
                }
            }
        }
        check(checked > 500, "mixed oracle did not exercise enough variants");
    }

    private static void enumerate(int[] counts, int at, int left, List<int[]> values) {
        if (at == counts.length - 1) {
            counts[at] = left;
            if (Arrays.stream(counts).filter(n -> n > 0).count() > 1) values.add(counts.clone());
            return;
        }
        for (int n = left; n >= 0; n--) { counts[at] = n; enumerate(counts, at + 1, left - n, values); }
    }

    private static void coverageClosure() {
        var main = entry("main", new CapturedRecipe<>(List.of(input("a", 2, 1)), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false));
        var later = entry("upstream", new CapturedRecipe<>(List.of(input("raw", 1, 1)), List.of(new CapturedRecipe.Amount<>("a1", 1)), true, false));
        var noise = entry("unrelated", new CapturedRecipe<>(List.of(input("unused", 300, 1)), List.of(new CapturedRecipe.Amount<>("unneeded", 1)), true, false));
        var work = new CapturedCatalog<>(List.of(main, later, noise)).begin("out", 1, Map.of(), false, true, budget());
        try (work) { while (!work.step()) {} }
        var proof = work.certificate(WORK, MEMORY);
        check(proof.witness() == null && proof.coverage().complete(), "complete missing preview must export scope only");
        var replay = CapturedProof.verify(proof, WORK, MEMORY);
        check(replay.coverage() == CountProof.Verdict.VERIFIED && replay.execution() == CountProof.Verdict.INCOMPLETE && !replay.witnessPresent(), "missing preview claimed a theorem");
        invalidCoverage(replace(proof, new CapturedCatalog.Coverage(1, proof.coverage().domains().stream().filter(d -> !d.id().equals("upstream")).toList(), true), null));
        var snapshot = new CapturedProof.Snapshot(proof.snapshot().resources(), proof.snapshot().patterns(), false);
        invalidCoverage(new CapturedProof.Certificate(snapshot, proof.request(), proof.coverage(), null));
        // A provider known only through a late physical container return is relevant.
        var returnCandidate = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("filled", 1), "out", false);
        var returning = entry("drain", new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(returnCandidate))), List.of(new CapturedRecipe.Amount<>("bonus", 1)), true, false));
        var returned = new CapturedCatalog<>(List.of(returning)).begin("out", 1, Map.of(), false, true, budget());
        try (returned) { while (!returned.step()) {} }
        var returns = returned.certificate(WORK, MEMORY);
        invalidCoverage(replace(returns, new CapturedCatalog.Coverage(1, List.of(), true), null));
    }

    private static void physicalReturnsAndSharedStock() {
        var input = new CapturedRecipe.Input<>(2, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("filled", 3), "can", false)));
        var token = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("config", 1), null, true, true)));
        var captured = new CapturedRecipe<>(List.of(input, token), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var stock = Map.of("filled", 6L, "config", 1L);
        var work = new CapturedCatalog<>(List.of(entry("r", captured))).begin("can", 2, stock, false, true, budget());
        try (work) { while (!work.step()) {} }
        check(work.result().plan().feasible(), "return test did not solve");
        var proof = work.certificate(WORK, MEMORY); good(proof);
        var concrete = proof.witness().recipes().values().iterator().next();
        var outputs = new LinkedHashMap<>(concrete.outputs()); outputs.merge(proof.request().target(), 1L, Long::sum);
        invalidExecution(replace(proof, proof.coverage(), witness(proof,
                Map.of(concrete.id(), new GraphRecipe<>(concrete.id(), concrete.binding(), concrete.slots(), outputs)), proof.witness().program(), proof.witness().initial())));
        var shared = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("same", 1), null, false))),
                new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("same", 1), null, false)))), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var sharedGraph = new GraphRecipe<>("r#0", "binding-r", List.of(new GraphRecipe.Slot<>("same", 1, 0), new GraphRecipe.Slot<>("same", 1, 1)), Map.of("out", 1L));
        var sharedProof = synthetic(List.of(entry("r", shared)), "out", 1, Map.of("same", 1L), false, true,
                sharedGraph, new PlanStep.Batch("r#0", 1), Map.of("same", 1L), Map.of(), Map.of());
        invalidExecution(sharedProof);
    }

    private static void compressedPrograms() throws Exception {
        var recipe = new CapturedRecipe<String>(List.of(), List.of(new CapturedRecipe.Amount<>("out", 1)), true, false);
        var graph = new GraphRecipe<String>("r#0", "binding-r", List.of(), Map.of("out", 1L));
        PlanStep step = new PlanStep.Batch("r#0", Long.MAX_VALUE);
        for (int depth = 0; depth < 60; depth++) step = new PlanStep.Sequence(List.of(step, step));
        var proof = synthetic(List.of(entry("r", recipe)), "out", Long.MAX_VALUE, Map.of(), false, true, graph, step, Map.of(), Map.of(), Map.of());
        check(proof.witness().program().size() == 61, "shared witness was expanded"); good(proof);
        Path path = Path.of("build/test-artifacts/captured-proof/shared.cgd"); CapturedProof.write(path, proof); good(CapturedProof.read(path));
        check(Files.size(path) < 4096, "exponential archive");
        step = new PlanStep.Batch("r#0", 1);
        for (int depth = 0; depth < 10_000; depth++) step = new PlanStep.Repeat(step, 1);
        var deep = synthetic(List.of(entry("r", recipe)), "out", 1, Map.of(), false, true, graph, step, Map.of(), Map.of(), Map.of());
        good(deep); CapturedProof.write(path, deep); good(CapturedProof.read(path));
    }

    private static void productionAndMandatorySeeds() {
        var captured = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("a", 2), null, false)))),
                List.of(new CapturedRecipe.Amount<>("a", 3)), true, false);
        var graph = new GraphRecipe<>("r#0", "binding-r", List.of(new GraphRecipe.Slot<>("a", 2, 0)), Map.of("a", 3L));
        var proof = synthetic(List.of(entry("r", captured)), "a", 3, Map.of("a", 2L), false, true, graph, new PlanStep.Batch("r#0", 1), Map.of("a", 2L), Map.of(), Map.of());
        check(CapturedProof.verify(proof, WORK, MEMORY).execution() == CountProof.Verdict.INCOMPLETE, "gross turnover approved without stronger force proof");
        var mandatory = synthetic(List.of(entry("r", captured)), "a", 1, Map.of("a", 2L), true, true, graph, new PlanStep.Batch("r#0", 1), Map.of("a", 2L), Map.of(), Map.of("a", 3L));
        invalidExecution(mandatory);
        var noRuns = synthetic(List.of(entry("r", captured)), "a", 1, Map.of("a", 2L), false, true, graph, new PlanStep.Batch("r#0", 0), Map.of("a", 2L), Map.of(), Map.of());
        invalidExecution(noRuns);
        var freeGoal = new CapturedProof.Request(proof.request().target(), 3, Map.of(), Set.of(proof.request().target()), Map.of(), false, true);
        good(new CapturedProof.Certificate(proof.snapshot(), freeGoal, proof.coverage(), proof.witness()));
    }

    private static void resourceIdentityAndLimits() {
        record Key(int id) { @Override public String toString() { return "same display name"; } }
        var a = new Key(0); var b = new Key(1); var target = new Key(2);
        var recipe = new CapturedRecipe<>(List.of(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(a, 1), null, false))),
                new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(b, 1), null, false)))), List.of(new CapturedRecipe.Amount<>(target, 1)), true, false);
        var work = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("r", "binding-r", recipe))).begin(target, 1, Map.of(a, 1L, b, 1L), false, true, budget());
        expect(IllegalStateException.class, () -> work.certificate(WORK, MEMORY));
        try (work) { while (!work.step()) {} }
        var proof = work.certificate(WORK, MEMORY);
        good(proof); check(proof.snapshot().resources() == 3, "display-name collision merged resources");
        expect(CapturedProof.Limit.class, () -> work.certificate(1, MEMORY));
        expect(CapturedProof.Limit.class, () -> work.certificate(WORK, 1));
        check(CapturedProof.verify(proof, 1, MEMORY).coverage() == CountProof.Verdict.INCOMPLETE, "replay work cutoff");
        check(CapturedProof.verify(proof, WORK, 1).coverage() == CountProof.Verdict.INCOMPLETE, "replay memory cutoff");
        good(work.certificate(WORK, MEMORY));
    }

    private static CapturedProof.Certificate synthetic(List<CapturedCatalog.Entry<String>> entries, String target, long amount,
                                                       Map<String, Long> stock, boolean preserve, boolean force,
                                                       GraphRecipe<String> graph, PlanStep steps, Map<String, Long> initial,
                                                       Map<String, Long> seeds, Map<String, Long> requiredSeeds) {
        var domains = new ArrayList<CapturedCatalog.DomainCoverage>();
        for (var entry : entries) try (var domain = entry.recipe().domain(budget())) {
            domains.add(new CapturedCatalog.DomainCoverage(entry.id(), domain.size(), domain.size(), List.of(), true));
        }
        var plan = new GraphPlan<>(target, amount, preserve, steps, Map.of(graph.id(), graph), initial, seeds, Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        return CapturedProof.capture(entries, true, target, amount, stock, Set.of(), requiredSeeds, preserve, force,
                new CapturedCatalog.Result<>(plan, new CapturedCatalog.Coverage(1, domains, true)), WORK, MEMORY);
    }

    private static <K> CapturedCatalog.Entry<K> entry(String id, CapturedRecipe<K> recipe) { return new CapturedCatalog.Entry<>(id, "binding-" + id, recipe); }
    private static CapturedCatalog.Coverage coverage(CapturedCatalog.DomainCoverage domain) { return new CapturedCatalog.Coverage(2, List.of(domain), false); }
    private static CapturedProof.Certificate replace(CapturedProof.Certificate proof, CapturedCatalog.Coverage coverage, CapturedProof.Witness witness) {
        return new CapturedProof.Certificate(proof.snapshot(), proof.request(), coverage, witness);
    }
    private static CapturedProof.Witness witness(CapturedProof.Certificate proof, Map<String, GraphRecipe<Integer>> recipes,
                                                 List<CapturedProof.Instruction> program, Map<Integer, BigInteger> initial) {
        return new CapturedProof.Witness(recipes, program, initial, proof.witness().seeds());
    }
    private static void good(CapturedProof.Certificate proof) {
        var replay = CapturedProof.verify(proof, WORK, MEMORY);
        check(replay.coverage() == CountProof.Verdict.VERIFIED && replay.execution() == CountProof.Verdict.VERIFIED && replay.witnessPresent(), "independent captured replay failed: " + replay);
    }
    private static void invalidCoverage(CapturedProof.Certificate proof) { check(CapturedProof.verify(proof, WORK, MEMORY).coverage() == CountProof.Verdict.INVALID, "accepted false coverage"); }
    private static void invalidExecution(CapturedProof.Certificate proof) { check(CapturedProof.verify(proof, WORK, MEMORY).execution() == CountProof.Verdict.INVALID, "accepted false execution"); }
    private interface IOAction { void run() throws IOException; }
    private static void ioFailure(IOAction action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Malformed archive accepted");
    }
}
