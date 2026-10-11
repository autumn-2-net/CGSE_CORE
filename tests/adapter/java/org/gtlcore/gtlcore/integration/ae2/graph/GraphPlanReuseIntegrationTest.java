package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.cgse.core.*;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real host RequestWork, current binding maps and emitted/used accounting. */
final class GraphPlanReuseIntegrationTest {
    private static Method candidate;
    private static Class<?> requestType;
    private record Run(AeGraphPlan plan, PlanningBudget budget) {}

    static void run(AEKey input, AEKey output) throws Exception {
        // The portable regression tree also supports older host checkouts.
        // Detect the actual new API; don't make the old-host compile depend on it.
        try { candidate = AeGraphPlan.class.getMethod("reuseCandidate"); }
        catch (NoSuchMethodException olderHost) {
            System.out.println("GraphPlanReuseIntegrationTest: SKIP (host predates explicit plan reuse)");
            return;
        }
        requestType = Class.forName(CraftingEngineRouter.class.getName() + "$RequestWork");
        var priorConfig = ConfigHolder.INSTANCE;
        try {
            ConfigHolder.INSTANCE = new ConfigHolder();
            ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging = false;
            ConfigHolder.INSTANCE.ae2GraphFallback = false;
            var firstBinding = pattern(input, output);
            var currentBinding = pattern(input, output);
            check(firstBinding != currentBinding && PatternFingerprint.of(firstBinding).equals(PatternFingerprint.of(currentBinding)),
                    "fresh equivalent binding setup");
            var first = solve(input, output, firstBinding, 10, Set.of(input), 3, CalculationStrategy.REPORT_MISSING_ITEMS, null);
            check(!first.plan.simulation() && candidate.invoke(first.plan) != null, "full initial request must export handle");
            check(first.plan.usedItems().get(input) == 6 && first.plan.emittedItems().get(input) == 0, "initial extraction setup");
            Object handle = candidate.invoke(first.plan);
            var current = solve(input, output, currentBinding, 4, Set.of(input), 3, CalculationStrategy.REPORT_MISSING_ITEMS, handle);
            check(reused(current), "new RequestWork must actually pass the opaque candidate to CGSE");
            check(current.plan.graph().steps() == first.plan.graph().steps(), "same immutable execution program");
            check(current.plan.bindings().get(PatternFingerprint.of(currentBinding)) == currentBinding,
                    "assembly must bind current prepared snapshot, not old pattern objects");
            check(current.plan.usedItems().get(input) == 4 && current.plan.emittedItems().get(input) == 2,
                    "emission must be recalculated from current stock");
            check(current.plan.patternTimes().get(currentBinding) == 3, "pattern counts use current binding");
            var removedEmission = solve(input, output, currentBinding, 4, Set.of(), 3,
                    CalculationStrategy.REPORT_MISSING_ITEMS, handle);
            check(!reused(removedEmission) && removedEmission.plan.simulation() && candidate.invoke(removedEmission.plan) == null,
                    "removed external supply must not retain previous emitted funding or handle");
            var reduced = solve(input, output, currentBinding, 4, Set.of(), 3, CalculationStrategy.CRAFT_LESS, handle);
            check(!reused(reduced) && !reduced.plan.simulation() && reduced.plan.finalOutput().amount() == 2 &&
                    candidate.invoke(reduced.plan) == null, "craft-less quantity cannot export original full request handle");
            var changedAmount = solve(input, output, currentBinding, 10, Set.of(input), 4,
                    CalculationStrategy.REPORT_MISSING_ITEMS, handle);
            check(!reused(changedAmount) && changedAmount.plan.finalOutput().amount() == 4, "new requested amount must search its own plan");
            var refresh = CraftingEngineRouter.class.getMethod("submissionRefresh",
                    appeng.api.networking.security.IActionSource.class, AeGraphPlan.class).invoke(null, null, current.plan);
            var handed = refresh.getClass().getDeclaredMethod("candidate");
            handed.setAccessible(true);
            check(handed.invoke(refresh) == candidate.invoke(current.plan), "confirmation refresh carries exactly this plan's handle");
            System.out.println("GraphPlanReuseIntegrationTest: PASS");
        } finally { ConfigHolder.INSTANCE = priorConfig; }
    }

    @SuppressWarnings("unchecked")
    private static Run solve(AEKey input, AEKey output, IPatternDetails binding, long stock, Set<AEKey> external,
                             long amount, CalculationStrategy strategy, Object handle) throws Exception {
        var budget = new PlanningBudget(0, 1_000_000, 32L << 20, () -> false, System::nanoTime);
        budget.enableMetrics();
        String id = PatternFingerprint.of(binding);
        var recipe = new GraphRecipe<AEKey>("make", id, List.of(new GraphRecipe.Slot<>(input, 2, 0)), Map.of(output, 1L));
        var compiler = new GraphCompiler<>(List.of(recipe));
        var catalog = new CapturedPatternCatalog(List.of(), 1);
        var prepared = CapturedPatternCatalog.class.getDeclaredField("prepared");
        prepared.setAccessible(true);
        prepared.set(catalog, new CapturedPatternCatalog.Prepared(compiler, Map.of(id, binding)));
        var structure = new GtlPatternCatalog.Structure(catalog, Set.of(input, output), Set.of(), Set.of(), false, Map.of(), 0);
        var snapshot = new GtlPatternCatalog.Snapshot(structure, Map.of(input, stock), external, 0, false);
        var constructor = requestType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var parameters = constructor.getParameterTypes();
        check(parameters.length == 10 && parameters[9] == candidate.getReturnType(), "unexpected candidate constructor signature");
        var request = new GraphPlanningRequest(budget);
        var work = (PlanningScheduler.Work<ICraftingPlan>) constructor.newInstance(null,
                CompletableFuture.completedFuture(snapshot), output, amount, strategy, budget, null, true, request, handle);
        try (var scheduler = new PlanningScheduler(2, 4, 128, 2_000_000)) {
            var result = (AeGraphPlan) scheduler.submit(work, budget).get(10, TimeUnit.SECONDS);
            check(budget.reservedBytes() == 0, "adapter request leaked memory");
            if (!result.simulation()) PlanVerifier.verify(result.graph());
            return new Run(result, budget);
        }
    }

    private static IPatternDetails pattern(AEKey input, AEKey output) {
        var nbt = new CompoundTag();
        var inputs = new ListTag();
        var outputs = new ListTag();
        inputs.add(GenericStack.writeTag(new GenericStack(input, 2)));
        outputs.add(GenericStack.writeTag(new GenericStack(output, 1)));
        nbt.put("in", inputs);
        nbt.put("out", outputs);
        return new AEProcessingPattern(AEItemKey.of(Items.PAPER, nbt));
    }

    private static boolean reused(Run run) { return run.budget.diagnostics().matches(".*plan_reuse@[0-9]+: accepted;.*"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
