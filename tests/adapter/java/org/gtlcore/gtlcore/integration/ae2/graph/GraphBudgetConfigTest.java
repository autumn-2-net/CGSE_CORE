package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.cgse.core.*;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Items;
import dev.toma.configuration.config.format.ConfigFormats;
import dev.toma.configuration.config.format.YamlFormat;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Current nested YAML, legacy category migration and the actual host request budget wiring. */
final class GraphBudgetConfigTest {
    static void run(AEKey input, AEKey output) throws Exception {
        var previous = ConfigHolder.INSTANCE;
        try {
            migration();
            configuredAccounts();
            retainedCacheLimit(input, output);
            routedFallback(input, output, 1);
            routedFallback(input, output, 10_000);
            System.out.println("Graph budget configuration: YAML migration/priority/defaults/ranges, independent accounts, INT_MAX parallel expansion and routed fallback passed");
        } finally { ConfigHolder.INSTANCE = previous; }
    }

    private static String migrate(String yaml) throws Exception {
        var type = Class.forName("org.gtlcore.gtlcore.config.ConfigCategoryMigration");
        var method = type.getDeclaredMethod("migrateContent", String.class);
        method.setAccessible(true);
        String migrated = (String) method.invoke(null, yaml);
        check(migrated.equals(method.invoke(null, migrated)), "Configuration migration is not idempotent");
        return migrated;
    }

    private static ConfigHolder load(String name, String yaml) throws Exception {
        var path = Path.of("budget-config", name + ".yaml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
        var format = new YamlFormat(); format.readFile(path.toFile());
        var holder = new dev.toma.configuration.config.ConfigHolder<>(ConfigHolder.class,
                "gtlcore", "budget-test", "gtlcore", ConfigFormats.yaml());
        holder.values().forEach(value -> value.deserializeValue(format));
        var serialized = new YamlFormat();
        holder.values().forEach(value -> value.serializeValue(serialized));
        serialized.writeFile(path.resolveSibling(name + "-roundtrip.yaml").toFile());
        ConfigHolder.INSTANCE = holder.getConfigInstance();
        return ConfigHolder.INSTANCE;
    }

    private static void migration() throws Exception {
        String old = """
                # Existing server configuration
                ae2GraphPlannerMaxSteps: 50000
                performance:
                  ae2GraphPlannerMaxSteps: 71000
                  ae2GraphPlannerThreads: 3
                  ae2GraphPlannerMaxRequests: 17
                  ae2GraphPlannerParallelWorkBudget: true
                  ae2GraphPlannerTimeoutMs: 1900
                  ae2GraphPlannerMemoryMiB: 256
                  ae2GraphFallback: false
                  ae2StorageServiceUpdateInterval: 7
                ae:
                  ae2GraphPlannerMaxSteps: 91000
                diagnostics:
                  ae2GraphDiagnosticLogging: false
                """;
        var mixed = load("mixed", migrate(old));
        check(mixed.ae.ae2GraphIntelligentScheduling, "Existing YAML did not enable intelligent scheduling by default");
        check(mixed.ae.ae2GraphPlannerMaxSteps == 91000 && mixed.ae.ae2GraphPlannerThreads == 3 &&
                mixed.ae.ae2GraphPlannerMaxRequests == 17 && mixed.ae.ae2GraphPlannerParallelWorkBudget &&
                mixed.ae.ae2GraphPlannerTimeoutMs == 1900 && mixed.ae.ae2GraphPlannerMemoryMiB == 256 &&
                !mixed.ae.ae2GraphFallback, "AE values or legacy performance budgets were lost");
        check(mixed.performance.ae2StorageServiceUpdateInterval == 7, "Unrelated performance setting changed");
        check(mixed.ae.ae2GraphCompilerMaxSteps == 0 && mixed.ae.ae2GraphCachePreparationMaxSteps == 8192 &&
                mixed.ae.ae2GraphFallbackTimeoutMs == 0 && mixed.ae.ae2GraphFallbackMaxSteps == 131072 &&
                mixed.ae.ae2GraphFallbackMemoryMiB == 16, "New settings did not use compatible defaults");
        var legacy = load("old-category", migrate(old.replace("ae:\n  ae2GraphPlannerMaxSteps: 91000\n", "")));
        check(legacy.ae.ae2GraphPlannerMaxSteps == 71000, "Flat key overwrote the old category");
        var flat = load("flat", migrate("ae2GraphPlannerMaxSteps: 54000\nae2GraphFallback: false\n"));
        check(flat.ae.ae2GraphPlannerMaxSteps == 54000 && !flat.ae.ae2GraphFallback, "Flat budgets did not migrate to AE");
        String alone = migrate("performance:\n  ae2GraphPlannerMaxSteps: 2147483647\n");
        check(!alone.contains("performance:"), "Empty old category retained duplicate legacy keys");
        check(load("alone", alone).ae.ae2GraphPlannerMaxSteps == Integer.MAX_VALUE, "Large migrated work truncated");
        String current = "ae:\r\n  ae2GraphCompilerMaxSteps: 32000\r\n  ae2GraphCachePreparationMaxSteps: 0\r\n";
        check(migrate(current).equals(current), "Current config needlessly rewritten");
        check(!load("intelligent-disabled", "ae:\n  ae2GraphIntelligentScheduling: false\n").ae.ae2GraphIntelligentScheduling,
                "Intelligent scheduling cannot be disabled in YAML");
        check(!load("intelligent-migrated", migrate("ae2GraphIntelligentScheduling: false\n")).ae.ae2GraphIntelligentScheduling,
                "Explicit flat scheduling policy lost during migration");
        var clamped = load("invalid", """
                ae:
                  ae2GraphCompilerMaxSteps: -1
                  ae2GraphCachePreparationMaxSteps: -1
                  ae2GraphFallbackMaxSteps: -1
                  ae2GraphFallbackMemoryMiB: 2147483647
                  ae2GraphFallbackTimeoutMs: 2147483647
                """);
        check(clamped.ae.ae2GraphCompilerMaxSteps == 0 && clamped.ae.ae2GraphCachePreparationMaxSteps == 0 &&
                clamped.ae.ae2GraphFallbackMaxSteps == 1 && clamped.ae.ae2GraphFallbackMemoryMiB == 1024 &&
                clamped.ae.ae2GraphFallbackTimeoutMs == 86400000, "Config ranges did not correct invalid inputs");
    }

    private static void configuredAccounts() throws Exception {
        load("custom", """
                ae:
                  ae2GraphPlannerMaxSteps: 10000
                  ae2GraphCompilerMaxSteps: 3
                  ae2GraphCachePreparationMaxSteps: 2
                  ae2GraphPlannerParallelWorkBudget: true
                  ae2GraphFallbackTimeoutMs: 0
                  ae2GraphFallbackMaxSteps: 777
                  ae2GraphFallbackMemoryMiB: 9
                  ae2GraphGlobalMemoryMiB: 32
                  ae2GraphCacheMemoryMiB: 0
                  ae2GraphSnapshotMicros: 700
                  ae2GraphSliceSteps: 128
                  ae2GraphSliceMicros: 800
                """);
        check(ConfigHolder.INSTANCE.ae.ae2GraphCacheMemoryMiB == 0 && ConfigHolder.INSTANCE.ae.ae2GraphSnapshotMicros == 700 &&
                ConfigHolder.INSTANCE.ae.ae2GraphSliceSteps == 128 && ConfigHolder.INSTANCE.ae.ae2GraphSliceMicros == 800,
                "Advanced runtime configuration did not round-trip");
        var memoryA = CraftingEngineRouter.planningBudget(1);
        var memoryB = CraftingEngineRouter.planningBudget(1);
        try {
            memoryA.reserve(24L << 20);
            expectLimit(() -> memoryB.reserve(16L << 20), "global_planning_memory");
            memoryA.closeMemory();
            memoryB.reserve(16L << 20);
        } finally { memoryA.closeMemory(); memoryB.closeMemory(); }
        var b = CraftingEngineRouter.planningBudget(4);
        charge(b, "compilationCharge", 6); charge(b, "charge", 20_000);
        check((boolean) charge(b, "tryCacheCharge", 2) && !(boolean) charge(b, "tryCacheCharge", 1), "Cache cap multiplied or ignored");
        expectLimit(b::compilationCheck, "compilation_work=7/6");
        expectLimit(b::check, "search_work=20001/20000");
        var fallback = CraftingEngineRouter.fallbackLimits();
        check(fallback.milliseconds() == 0 && fallback.maxWork() == 777 && fallback.maxBytes() == 9L << 20,
                "Fallback configuration ignored or multiplied");
        ConfigHolder.INSTANCE.ae.ae2GraphPlannerMaxSteps = Integer.MAX_VALUE;
        ConfigHolder.INSTANCE.ae.ae2GraphCompilerMaxSteps = Integer.MAX_VALUE;
        ConfigHolder.INSTANCE.ae.ae2GraphCachePreparationMaxSteps = Integer.MAX_VALUE;
        b = CraftingEngineRouter.planningBudget(4);
        long limit = 4_294_967_294L;
        charge(b, "charge", limit); charge(b, "compilationCharge", limit);
        check((boolean) charge(b, "tryCacheCharge", Integer.MAX_VALUE) && !(boolean) charge(b, "tryCacheCharge", 1), "Large cache cap overflow");
        check(b.searchWork() == limit && b.compilationWork() == limit, "Parallel int-sized budgets overflowed");
        ConfigHolder.INSTANCE.ae.ae2GraphCompilerMaxSteps = 0;
        ConfigHolder.INSTANCE.ae.ae2GraphCachePreparationMaxSteps = 0;
        b = CraftingEngineRouter.planningBudget(4);
        charge(b, "compilationCharge", limit);
        expectLimit(b::compilationCheck, "/4294967294");
        check(!(boolean) charge(b, "tryCacheCharge", 1) && b.searchWork() == 0, "Zero cache cap disabled compiler or borrowed work");
    }

    @SuppressWarnings("unchecked")
    private static void retainedCacheLimit(AEKey input, AEKey output) throws Exception {
        ConfigHolder.INSTANCE = new ConfigHolder();
        ConfigHolder.INSTANCE.ae.ae2GraphCacheMemoryMiB = 1;
        var owner = new GtlPatternCatalog();
        var cacheField = GtlPatternCatalog.class.getDeclaredField("cache"); cacheField.setAccessible(true);
        var cache = (Map<Object, GtlPatternCatalog.Structure>) cacheField.get(owner);
        var roots = Class.forName(GtlPatternCatalog.class.getName() + "$Roots").getDeclaredConstructors()[0]; roots.setAccessible(true);
        Object first = roots.newInstance(input, Set.of(), false), last = roots.newInstance(output, Set.of(), false);
        var older = new CapturedPatternCatalog(List.of(), 0);
        var newer = new CapturedPatternCatalog(List.of(), 0);
        var bytes = CapturedPatternCatalog.class.getDeclaredField("retainedBytes"); bytes.setAccessible(true);
        var access = CapturedPatternCatalog.class.getDeclaredField("lastAccess"); access.setAccessible(true);
        bytes.setLong(older, 700_000); bytes.setLong(newer, 700_000); access.setLong(older, 1); access.setLong(newer, 2);
        var activeSnapshot = new GtlPatternCatalog.Structure(older, Set.of(input), Set.of(), Set.of(), false, Map.of(), 0);
        cache.put(first, activeSnapshot);
        cache.put(last, new GtlPatternCatalog.Structure(newer, Set.of(output), Set.of(), Set.of(), false, Map.of(), 0));
        GtlPatternCatalog.trimRetainedCaches();
        check(!cache.containsKey(first) && cache.containsKey(last), "Cache memory target did not evict the older snapshot first");
        check(activeSnapshot.catalog() == older && activeSnapshot.resources().contains(input), "Eviction invalidated a captured request");
        ConfigHolder.INSTANCE.ae.ae2GraphCacheMemoryMiB = 0;
        GtlPatternCatalog.trimRetainedCaches();
        check(cache.isEmpty(), "Zero cache target retained snapshots");
    }

    @SuppressWarnings("unchecked")
    private static void routedFallback(AEKey input, AEKey output, int fallbackWork) throws Exception {
        ConfigHolder.INSTANCE = new ConfigHolder();
        ConfigHolder.INSTANCE.diagnostics.ae2GraphDiagnosticLogging = false;
        ConfigHolder.INSTANCE.ae.ae2GraphFallbackTimeoutMs = 0;
        ConfigHolder.INSTANCE.ae.ae2GraphFallbackMaxSteps = fallbackWork;
        var tag = new CompoundTag(); var inputs = new ListTag(); var outputs = new ListTag();
        inputs.add(GenericStack.writeTag(new GenericStack(input, 1)));
        outputs.add(GenericStack.writeTag(new GenericStack(output, 1)));
        tag.put("in", inputs); tag.put("out", outputs);
        var binding = new AEProcessingPattern(AEItemKey.of(Items.PAPER, tag));
        long configuredWork = 10_000;
        var budget = new PlanningBudget(0, configuredWork, 64L << 20, () -> false, System::nanoTime);
        var recipe = new GraphRecipe<AEKey>("make", "binding", List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
        var catalog = new CapturedPatternCatalog(List.of(), 1);
        var prepared = CapturedPatternCatalog.class.getDeclaredField("prepared"); prepared.setAccessible(true);
        prepared.set(catalog, new CapturedPatternCatalog.Prepared(new GraphCompiler<>(List.of(recipe)), Map.of("binding", binding), null));
        var structure = new GtlPatternCatalog.Structure(catalog, Set.of(input, output), Set.of(), Set.of(), false, Map.of(), 0);
        var snapshot = new GtlPatternCatalog.Snapshot(structure, Map.of(input, 2L), Set.of(), 0, false);
        var type = Class.forName(CraftingEngineRouter.class.getName() + "$RequestWork");
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var work = (PlanningScheduler.Work<ICraftingPlan>) constructor.newInstance(null,
                CompletableFuture.completedFuture(snapshot), output, 1L, CalculationStrategy.REPORT_MISSING_ITEMS,
                budget, null, true, new GraphPlanningRequest(budget), null);
        // Retired per-fallback settings cannot alter either primary configuration.
        ConfigHolder.INSTANCE.ae.ae2GraphFallbackMaxSteps = fallbackWork == 1 ? 10_000 : 1;
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var fresh = new java.util.concurrent.atomic.AtomicReference<PlanningBudget>();
        var retiredWork = new java.util.concurrent.atomic.AtomicLong(-1);
        var observing = new PlanningScheduler.Work<ICraftingPlan>() {
            public boolean advance(PlanningScheduler.Slice slice) {
                try {
                    var field = type.getDeclaredField("planning"); field.setAccessible(true);
                    var planning = (RequestPlanningWork<?>) field.get(work);
                    if (planning != null && armed.compareAndSet(false, true)) {
                        // Exhaust only after real host preparation. Enter the
                        // same advance before yielding so the inner coordinator
                        // owns the handoff, rather than a synchronous limited().
                        var method = PlanningBudget.class.getDeclaredMethod("charge", long.class); method.setAccessible(true);
                        try { method.invoke(budget, configuredWork + 1); throw new AssertionError("Parent account was not exhausted"); }
                        catch (InvocationTargetException expected) {
                            check(expected.getCause() instanceof PlanningBudget.Exhausted exhausted &&
                                    exhausted.limit() == PlanningBudget.Limit.SEARCH_LIMIT, "Wrong primary limit");
                        }
                    }
                    boolean done = work.advance(slice);
                    var active = work.activeBudget();
                    if (active != null && active != budget && fresh.compareAndSet(null, active)) {
                        check(armed.get() && active.searchWork() < configuredWork, "Recovery inherited spent work");
                        var maximum = PlanningBudget.class.getDeclaredField("maxNodes"); maximum.setAccessible(true);
                        var bytes = PlanningBudget.class.getDeclaredField("maxBytes"); bytes.setAccessible(true);
                        check(maximum.getLong(active) == configuredWork && maximum.getLong(budget) == configuredWork,
                                "Recovery changed the configured primary work cap");
                        check(bytes.getLong(active) == bytes.getLong(budget), "Recovery changed configured memory");
                        var compilation = PlanningBudget.class.getDeclaredField("maxCompilationNodes"); compilation.setAccessible(true);
                        var timeout = PlanningBudget.class.getDeclaredField("timeoutNanos"); timeout.setAccessible(true);
                        check(compilation.getLong(active) == compilation.getLong(budget) && timeout.getLong(active) == timeout.getLong(budget),
                                "Recovery changed configured compilation or time cap");
                        retiredWork.set(budget.searchWork());
                    }
                    return done;
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
            // This must delegate through the actual host wrapper: omitting its
            // activeBudget override would resume the cancelled primary account.
            public PlanningBudget activeBudget() { return work.activeBudget(); }
            public CompletableFuture<?> waitingFor() { return work.waitingFor(); }
            public ICraftingPlan result() { return work.result(); }
            public ICraftingPlan limited(PlanningBudget.Exhausted limit) { return work.limited(limit); }
            public void close() { work.close(); }
        };
        try (var scheduler = new PlanningScheduler(1, 2, 128, 2_000_000)) {
            var result = (AeGraphPlan) scheduler.submit(observing, budget).get(10, TimeUnit.SECONDS);
            check(armed.get() && fresh.get() != null && result.fallback() && !result.simulation(),
                    "Router did not resume an independent same-configuration primary call");
            check(budget.searchWork() == retiredWork.get() && fresh.get().searchWork() > 0,
                    "Recovery work was charged to the retired primary account");
            check(result.graph().amount() == 1 && result.graph().patternTimes().equals(Map.of("make", 1L)),
                    "Recovery changed the original request or physical binding");
            PlanVerifier.verify(result.graph());
            for (var entry : result.graph().initialExact().entrySet())
                check(entry.getValue().compareTo(java.math.BigInteger.valueOf(entry.getKey().equals(input) ? 2L : 0L)) <= 0,
                        "Recovery spent unavailable stock");
        }
        check(budget.reservedBytes() == 0 && fresh.get().reservedBytes() == 0, "Routed recovery leaked account ownership");
    }

    private static Object charge(PlanningBudget b, String name, long amount) throws Exception {
        var method = PlanningBudget.class.getDeclaredMethod(name, long.class); method.setAccessible(true);
        try { return method.invoke(b, amount); }
        catch (InvocationTargetException failure) { throw new AssertionError(failure.getCause()); }
    }

    private static void expectLimit(Runnable work, String detail) {
        try { work.run(); throw new AssertionError("Expected budget limit"); }
        catch (PlanningBudget.Exhausted limit) { check(limit.getMessage().contains(detail), "Wrong limit: " + limit); }
    }

    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
}
