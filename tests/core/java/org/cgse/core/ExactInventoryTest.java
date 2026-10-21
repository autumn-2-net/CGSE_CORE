package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Finite input above one transfer: no saturation, invented emission or cached shortage. */
public final class ExactInventoryTest {
    private static final BigInteger MAX = ExactAmounts.LONG_MAX;

    public static void main(String[] args) throws Exception {
        var recipe = new GraphRecipe<String>("make", "make", List.of(new GraphRecipe.Slot<>("raw", 2)), Map.of("target", 1L));
        var compiler = new GraphCompiler<>(List.of(recipe));
        var exact = PlanningInventory.exactSnapshot(Map.of("raw", MAX.multiply(BigInteger.TWO)));
        check(exact.get("raw") == Long.MAX_VALUE, "one-transfer view");
        check(PlanningInventory.exactAmount(exact, "raw").equals(MAX.multiply(BigInteger.TWO)), "exact input");
        check(PlanningInventory.snapshot(exact) == exact, "snapshot retains exact input");
        check(ExactAmounts.copy(exact).get("raw").equals(MAX.multiply(BigInteger.TWO)), "exact copy");
        var combined = PlanningInventory.availability(exact, Map.of("raw", 7L));
        check(PlanningInventory.exactAmount(combined, "raw").equals(MAX.multiply(BigInteger.TWO).add(BigInteger.valueOf(7))), "forecast sum");
        var shortage = PlanningInventory.exactSnapshot(Map.of("raw", MAX.multiply(BigInteger.TWO).subtract(BigInteger.ONE)));
        check(!PlanningInventory.sameAmounts(exact, shortage), "equal capped views do not equate inventories");
        for (boolean fallback : new boolean[]{false, true}) {
            var complete = solve(compiler, exact, fallback);
            funded(complete, exact);
            check(complete.feasible(), "finite wide stock completes");
            check(complete.initialExact().get("raw").equals(MAX.multiply(BigInteger.TWO)), "full physical input");
            var missing = solve(compiler, shortage, fallback);
            check(!missing.feasible() && missing.missingExact().equals(Map.of("raw", BigInteger.ONE)), "exact one-item deficit");
        }
        var chain = new GraphCompiler<>(List.of(
                new GraphRecipe<>("ore", "ore", List.of(new GraphRecipe.Slot<>("ore", 6)), Map.of("raw", 1L)), recipe));
        var ore = PlanningInventory.exactSnapshot(Map.of("ore", MAX.multiply(BigInteger.valueOf(12))));
        var plan = solve(chain, ore, false);
        check(plan.feasible(), "DAG with wide demand and finite stock"); funded(plan, ore);
        var captured = new CapturedRecipe<String>(List.of(new CapturedRecipe.Input<>(1,
                List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("raw", 2), null, false)))),
                List.of(new CapturedRecipe.Amount<>("target", 1)), true, false);
        var catalog = new CapturedCatalog<>(List.of(new CapturedCatalog.Entry<>("make", "make", captured)), true);
        try (var work = catalog.begin("target", Long.MAX_VALUE, exact, false, true, budget())) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "captured domain preserves wide stock"); funded(work.result().plan(), exact);
            var proof = work.certificate(1_000_000, 32L << 20);
            var path = java.nio.file.Path.of("build/test-artifacts/exact-inventory/wide.cgd");
            java.nio.file.Files.createDirectories(path.getParent());
            CapturedProof.write(path, proof);
            var copy = CapturedProof.read(path);
            check(PlanningInventory.sameAmounts(proof.request().stock(), copy.request().stock()), "proof codec retains exact inventory");
            check(CapturedProof.verify(copy, 1_000_000, 32L << 20).execution() == CountProof.Verdict.VERIFIED,
                    "independent portable proof verifies wide funding");
        }
        var reused = new GraphPlanningWork<>(compiler, "target", Long.MAX_VALUE, shortage, false, true, budget())
                .reuseCandidate(solve(compiler, exact, false));
        try {
            var work = reused;
            while (!work.step()) {}
            check(!work.result().feasible() && work.result().missingExact().get("raw").equals(BigInteger.ONE), "reuse revalidates uncapped stock");
        } finally { reused.close(); }
        // Existing target stock is not newly manufactured output.
        var targetStock = PlanningInventory.exactSnapshot(Map.of("target", MAX.multiply(BigInteger.TEN)));
        var forced = new GraphPlanningWork<>(compiler, "target", 1, targetStock, false, true, budget());
        try {
            var work = forced;
            while (!work.step()) {}
            check(!work.result().feasible(), "wide finished goods cannot satisfy forced production");
        } finally { forced.close(); }
        System.out.println("ExactInventoryTest PASS: wide DAG, finite deficit, fallback, captured input, reuse and production");
    }

    private static GraphPlan<String> solve(GraphCompiler<String> compiler, Map<String, Long> stock, boolean fallback) {
        if (fallback) return GraphFallback.plan(compiler, "target", Long.MAX_VALUE, stock, Set.of(), Map.of(), false, true, budget());
        var work = new GraphPlanningWork<>(compiler, "target", Long.MAX_VALUE, stock, false, true, budget());
        try {
            while (!work.step()) {}
            return work.result();
        } finally { work.close(); }
    }

    private static PlanningBudget budget() { return new PlanningBudget(0, 8_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void funded(GraphPlan<String> plan, Map<String, Long> stock) {
        PlanVerifier.verify(plan);
        plan.initialExact().forEach((key, amount) -> check(amount.compareTo(PlanningInventory.exactAmount(stock, key)) <= 0, "finite funding " + key));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
