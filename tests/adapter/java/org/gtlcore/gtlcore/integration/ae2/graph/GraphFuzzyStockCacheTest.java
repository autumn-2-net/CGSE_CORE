package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.cgse.core.GraphCompiler;
import org.cgse.core.GraphPlan;
import org.cgse.core.GraphPlanningWork;
import org.cgse.core.PlanVerifier;
import org.cgse.core.PlanningBudget;
import org.cgse.core.PlanningScheduler;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Stock churn must retain compiled domains without retaining phantom stock. */
final class GraphFuzzyStockCacheTest {
    static void run() throws Exception {
        AEKey a = variant("declared"), b = variant("captured"), c = variant("new");
        AEKey target = AEItemKey.of(Items.BOOK);
        boolean[] frozen = {false};
        int[] validityChecks = {0};
        IPatternDetails pattern = new IPatternDetails() {
            private void guard() {
                if (frozen[0]) throw new AssertionError("stock-only refresh re-entered live pattern capture");
            }
            @Override public AEItemKey getDefinition() { guard(); return AEItemKey.of(Items.STICK); }
            @Override public GenericStack[] getOutputs() { guard(); return new GenericStack[] {new GenericStack(target, 1)}; }
            @Override public boolean supportsPushInputsToExternalInventory() { guard(); return false; }
            @Override public IInput[] getInputs() {
                guard();
                return new IInput[] {new IInput() {
                    @Override public GenericStack[] getPossibleInputs() { guard(); return new GenericStack[] {new GenericStack(a, 1)}; }
                    @Override public long getMultiplier() { guard(); return 1; }
                    @Override public boolean isValid(AEKey key, Level level) {
                        guard(); validityChecks[0]++;
                        return key.equals(a) || key.equals(b) || key.equals(c);
                    }
                    @Override public AEKey getRemainingKey(AEKey key) { guard(); return null; }
                }};
            }
        };

        var initialStock = counter(b, 2);
        var catalog = capture(pattern, initialStock);
        Set<AEKey> capturedKeys = fuzzy(initialStock, a);
        var structure = new GtlPatternCatalog.Structure(catalog, Set.of(a, b, target), Set.of(a),
                Set.copyOf(capturedKeys), false, Map.of(), 0);
        check(capturedKeys.equals(Set.of(b)), "only physically discovered variant initially present");
        frozen[0] = true;
        try (var scheduler = new PlanningScheduler(1, 4, 128, 1_000_000)) {
            var prepared = build(scheduler, catalog);
            check(prepared.compiler().producers(target).size() == 2, "declared and discovered input alternatives encoded");
            int capturedCallbacks = validityChecks[0];
            for (long count : new long[] {2, 5, 0, 3, 0, 0, 1}) {
                var quantities = counter(b, count);
                Set<AEKey> current = fuzzy(quantities, a);
                check(structure.coversFuzzyKeys(current), "known variant disappearance must retain captured compiler");
                check(structure.fuzzyKeys().equals(capturedKeys), "zero stock must not shrink captured key coverage");
                check(build(scheduler, structure.catalog()) == prepared, "same prepared compiler and structural caches retained");
                var result = solve(scheduler, prepared.compiler(), target, count == 0 ? Map.of() : Map.of(b, count));
                if (count == 0) {
                    check(!result.feasible(), "retained zero-stock input must not become phantom available material");
                } else {
                    check(result.feasible() && result.initialExact().getOrDefault(b, java.math.BigInteger.ZERO).signum() > 0 &&
                            !result.initialExact().containsKey(a), "current positive stock funds exactly the captured variant");
                    PlanVerifier.verify(result);
                }
            }
            check(validityChecks[0] == capturedCallbacks, "stock quantities cannot trigger live recapture");

            Set<AEKey> newKeys = fuzzy(counter(c, 1), a);
            check(!structure.coversFuzzyKeys(newKeys), "previously unseen NBT variant still invalidates capture");
            check(!solve(scheduler, prepared.compiler(), target, Map.of(c, 1L)).feasible(),
                    "old compiler really lacks the new physical alternative");
            frozen[0] = false;
            var replacement = capture(pattern, counter(c, 1));
            frozen[0] = true;
            var rebuilt = build(scheduler, replacement);
            var withNewKey = solve(scheduler, rebuilt.compiler(), target, Map.of(c, 1L));
            check(withNewKey.feasible() && withNewKey.initialExact().containsKey(c),
                    "recapture incorporates new input identity and enables its real stock");
            PlanVerifier.verify(withNewKey);

            catalog.invalidate();
            try {
                build(scheduler, structure.catalog());
                throw new AssertionError("fuzzy coverage bypassed disproved binding invalidation");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause() instanceof GraphSnapshotChanged, "binding invalidation remains authoritative");
            }
        }
        System.out.println("GraphFuzzyStockCacheTest: PASS (unchanged, quantity changes, zero crossings, new variants, invalidation)");
    }

    private static CapturedPatternCatalog capture(IPatternDetails pattern, KeyCounter stock) {
        var values = PatternFingerprint.capture(pattern);
        var budget = new PlanningBudget(0, 100_000, () -> false);
        var work = new GtlPatternCatalog.CandidateCapture(pattern, values, stock, null, budget);
        while (!work.step()) {}
        return new CapturedPatternCatalog(List.of(new CapturedPatternCatalog.Entry(pattern, values, 0, work.result())), work.result().size());
    }

    private static CapturedPatternCatalog.Prepared build(PlanningScheduler scheduler, CapturedPatternCatalog catalog) throws Exception {
        var budget = new PlanningBudget(0, 100_000, 32L << 20, () -> false, System::nanoTime);
        return scheduler.submit(catalog.build(budget), budget).get(10, TimeUnit.SECONDS);
    }

    private static GraphPlan<AEKey> solve(PlanningScheduler scheduler, GraphCompiler<AEKey> compiler, AEKey target,
                                        Map<AEKey, Long> stock) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        return scheduler.submit(new GraphPlanningWork<>(compiler, target, 1, stock, false, true, budget), budget).get(10, TimeUnit.SECONDS);
    }

    private static KeyCounter counter(AEKey key, long count) {
        var counter = new KeyCounter();
        if (count > 0) counter.add(key, count);
        return counter;
    }

    private static Set<AEKey> fuzzy(KeyCounter stock, AEKey template) {
        var result = new HashSet<AEKey>();
        GtlPatternCatalog.includeFuzzyKeys(result, stock, template);
        return result;
    }

    private static AEItemKey variant(String name) {
        var tag = new CompoundTag();
        tag.putString("variant", name);
        return AEItemKey.of(Items.PAPER, tag);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
