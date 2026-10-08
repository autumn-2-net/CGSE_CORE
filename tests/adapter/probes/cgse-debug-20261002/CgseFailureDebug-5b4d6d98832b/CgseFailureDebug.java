package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.cgse.core.*;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Only included by the ignored debug build. A failure bundle describes one immutable request. */
public final class CgseFailureDebug {
    public static final String BASE = "5b6cd0ec6a9d2be99d9031404176e84b9cd342ea";
    public static final String BUILD = "cgse-dbg-20261002-" + BASE.substring(0, 8);
    private static final String SESSION = Long.toString(System.currentTimeMillis(), 36) + "-" + ProcessHandle.current().pid();
    private static volatile Path directory = Path.of(System.getProperty("gtlcore.cgse.debug.directory", "logs/cgse-debug")).toAbsolutePath();
    private static final int MAX_REPORTS = Integer.getInteger("gtlcore.cgse.debug.maxReports", 32);
    private static final int MAX_MISSING_REPORTS = Integer.getInteger("gtlcore.cgse.debug.maxMissingReports", 8);
    private static final AtomicInteger exported = new AtomicInteger(), skipped = new AtomicInteger();
    private static final AtomicInteger missingExported = new AtomicInteger();
    private static final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "CGSE debug archive");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final PlanningBudget budget;
    private final AEKey target;
    private final long amount;
    private final CalculationStrategy strategy;
    private final boolean preserve;
    private final GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint;
    private final AtomicBoolean completed = new AtomicBoolean();
    private final Map<String, Object> context = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> reasons = new LinkedHashSet<>();
    private final Map<String, GraphPlan<AEKey>> candidates = new LinkedHashMap<>();
    private Map<String, Object> firstState;
    private GraphPlan<AEKey> firstPlan;
    private PlanningBudget fallback;
    private volatile PartialCapture partial;

    public record PartialCapture(Map<String, Object> state, List<CapturedPatternCatalog.Entry> entries,
                                 Map<AEKey, Long> stock, Set<AEKey> external) {}

    public record Packet(Map<String, Object> report, List<PlanningDebugTrace.Event> trace,
                         List<PlanningDebugTrace.Event> fallbackTrace, AEKey target, long amount,
                         boolean preserve, boolean forceCraft, CatalystPolicy catalysts,
                         GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint,
                         GtlPatternCatalog.Snapshot snapshot, List<GraphRecipe<AEKey>> recipes,
                         Map<AEKey, Long> available, List<CapturedPatternCatalog.Entry> entries,
                         PartialCapture partial, GraphPlan<AEKey> primary, GraphPlan<AEKey> finalPlan,
                         Map<String, GraphPlan<AEKey>> candidates) {}

    public static void startup() {
        if (System.getProperty("gtlcore.cgse.debug.directory") == null)
            directory = FMLPaths.GAMEDIR.get().resolve("logs/cgse-debug").toAbsolutePath();
        GTLCore.LOGGER.warn("[CGSE DBG] LOCAL DEBUG BUILD {}. Automatic failure archives: {}. Solver limits are unchanged. Remove this JAR after reproduction.", BUILD, directory);
    }

    public CgseFailureDebug(PlanningBudget budget, AEKey target, long amount, CalculationStrategy strategy,
                            boolean preserve, GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint) {
        this.budget = budget;
        this.target = target;
        this.amount = amount;
        this.strategy = strategy;
        this.preserve = preserve;
        this.checkpoint = checkpoint;
        budget.debug("crafting").attachment = this;
        budget.note("debug_begin", "id=" + budget.debug().id + "; target=" + target + "; amount=" + amount + "; strategy=" + strategy);
        context("started_at", Instant.now().toString());
        context("configuration", configuration());
        GTLCore.LOGGER.info("[CGSE DBG] begin id={} target={} amount={} strategy={} budget={}",
                budget.debug().id, target, amount, strategy, budget.debugSnapshot());
    }

    public void context(String key, Object value) { context.put(key, value); }

    public void candidates(Map<String, GraphPlan<AEKey>> values) {
        values.forEach((name, plan) -> candidates.putIfAbsent(name, plan));
    }

    public void mark(String reason, GraphPlan<AEKey> plan, Map<String, Object> state) {
        if (reasons.size() < 32) reasons.add(reason);
        if (firstState == null) {
            firstState = state;
            firstPlan = plan;
        }
        budget.note("debug_failure", reason);
    }

    public void fallback(PlanningBudget quick) {
        fallback = quick;
        quick.debug("fallback of " + budget.debug().id);
    }

    public static void captureFailed(PlanningBudget budget, GtlPatternCatalog.Capture capture, Throwable error) {
        if (budget.debug() == null || !(budget.debug().attachment instanceof CgseFailureDebug debug)) return;
        try {
            // This hook executes on the server thread, before publishing the failed snapshot future.
            debug.partial = capture.debugPartial();
            budget.note("snapshot_failure", PlanningDebugTrace.stack(error));
        } catch (Throwable diagnosticError) {
            GTLCore.LOGGER.error("[CGSE DBG] Could not retain partial capture id={}", budget.debug().id, diagnosticError);
        }
    }

    public void complete(Throwable error, GtlPatternCatalog.Snapshot snapshot, GraphCompiler<AEKey> compiler,
                         Map<AEKey, Long> available, CatalystPolicy catalysts, boolean forceCraft,
                         GraphPlan<AEKey> finalPlan, List<CapturedPatternCatalog.Entry> entries,
                         List<GraphRecipe<AEKey>> partialRecipes, Map<String, Object> state) {
        if (!completed.compareAndSet(false, true)) return;
        try {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        boolean cancelled = cause instanceof CancellationException;
        if (error != null && !cancelled) mark("exception:" + cause.getClass().getName(), finalPlan, state);
        if (finalPlan != null && !finalPlan.feasible()) mark("final_result:" + finalPlan.result(), finalPlan, state);
        if (budget.debug().limited() && reasons.isEmpty()) mark("limit_with_retained_plan", finalPlan, state);
        String result = cancelled ? "CANCELLED" : error != null ? cause.toString() : finalPlan == null ? "NO_PLAN" : finalPlan.result().name();
        budget.note("debug_end", "result=" + result);
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", budget.debug().id);
        end.put("target", String.valueOf(target));
        end.put("amount", Long.toString(amount));
        end.put("result", result);
        end.put("work", budget.nodes());
        end.put("peak_reserved_bytes", budget.peakBytes());
        end.put("cache_hit", snapshot != null && snapshot.cacheHit());
        synchronized (recent) {
            recent.addLast(end);
            while (recent.size() > 32) recent.removeFirst();
        }
        GTLCore.LOGGER.info("[CGSE DBG] end {}", end);
        if (reasons.isEmpty()) return;
        boolean ordinaryMissing = error == null && fallback == null && !budget.debug().limited() && finalPlan != null &&
                (finalPlan.result() == GraphPlan.Result.MISSING_INPUT || finalPlan.result() == GraphPlan.Result.MISSING_SEED);
        AtomicInteger quota = ordinaryMissing ? missingExported : exported;
        int maximum = ordinaryMissing ? MAX_MISSING_REPORTS : MAX_REPORTS;
        if (quota.incrementAndGet() > maximum) {
            quota.decrementAndGet();
            GTLCore.LOGGER.warn("[CGSE DBG] No archive id={}: {} session archive limit {} reached; skipped={}",
                    budget.debug().id, ordinaryMissing ? "ordinary_missing" : "solver_failure", maximum, skipped.incrementAndGet());
            return;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "gtlcore-cgse-failure-v1");
        report.put("debug_build", BUILD);
        report.put("base_commit", BASE);
        report.put("session", SESSION);
        report.put("captured_at", Instant.now().toString());
        report.put("failure_reasons", List.copyOf(reasons));
        report.put("result", result);
        report.put("error", PlanningDebugTrace.stack(error));
        report.put("budget", budget.debugSnapshot());
        report.put("fallback_budget", fallback == null ? null : fallback.debugSnapshot());
        report.put("first_failure_state", firstState);
        report.put("final_state", state);
        synchronized (context) { report.put("context", new LinkedHashMap<>(context)); }
        synchronized (recent) { report.put("recent_requests", List.copyOf(recent)); }
        report.put("calculation_strategy", strategy.name());
        report.put("snapshot_complete", snapshot != null);
        report.put("compiled_catalog_complete", compiler != null);
        report.put("solver_input_replayable", snapshot != null && compiler != null && available != null);
        report.put("snapshot_epoch", snapshot == null ? null : snapshot.epoch());
        report.put("input_alternatives_bounded", snapshot == null ? null : snapshot.structure().boundedAlternatives());
        report.put("capture_scope", "Frozen planning inputs and coordinator state; not a JVM heap dump or resumable thread checkpoint. Timing and concurrent cache history can affect replay.");
        report.put("runtime_at_failure", runtime());
        report.put("threads_at_failure", threads());
        Packet packet = new Packet(report, budget.debug().events(), fallback == null ? List.of() : fallback.debug().events(),
                target, amount, preserve, forceCraft, catalysts, checkpoint, snapshot,
                compiler == null ? partialRecipes : compiler.catalog(), available == null ? Map.of() : new LinkedHashMap<>(available),
                entries, partial, firstPlan, finalPlan, new LinkedHashMap<>(candidates));
        try {
            WRITER.execute(() -> export(packet, budget.debug().id));
            GTLCore.LOGGER.warn("[CGSE DBG] Failure archive queued id={} reasons={}; no live inventory is read by the writer", budget.debug().id, reasons);
        } catch (RejectedExecutionException full) {
            quota.decrementAndGet();
            GTLCore.LOGGER.error("[CGSE DBG] Archive queue full id={}; skipped={}; retry after queued archives finish", budget.debug().id, skipped.incrementAndGet());
        }
        } finally {
            // Completed futures may be retained by menus/requesters. Do not retain a second catalog/plan/trace there.
            budget.debug().detach();
            if (fallback != null) fallback.debug().detach();
            candidates.clear();
            firstPlan = null;
            partial = null;
        }
    }

    private static void export(Packet packet, long id) {
        try {
            Files.createDirectories(directory);
            long used;
            try (var files = Files.list(directory)) {
                used = files.filter(p -> p.getFileName().toString().startsWith("cgse-failure-")).mapToLong(p -> {
                    try { return Files.size(p); } catch (Exception ignored) { return 0; }
                }).sum();
            }
            if (used >= (1L << 30)) throw new java.io.IOException("debug directory reached 1 GiB; move previous reports before reproducing again");
            Path path = directory.resolve("cgse-failure-" + SESSION + "-" + id + ".zip");
            CgseDebugArchive.write(path, packet);
            GTLCore.LOGGER.warn("[CGSE DBG] Failure archive saved id={} path={} bytes={}. See export-status.json for completeness.", id, path, Files.size(path));
        } catch (Throwable error) {
            GTLCore.LOGGER.error("[CGSE DBG] Failure archive export failed id={}; original failure remains in latest.log", id, error);
        }
    }

    public static boolean awaitWrites(long milliseconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        do {
            if (WRITER.getQueue().isEmpty() && WRITER.getActiveCount() == 0) return true;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        return false;
    }

    static Map<String, Object> configuration() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (var field : ConfigHolder.class.getFields()) if (field.getName().startsWith("ae2")) {
                Object value = field.get(ConfigHolder.INSTANCE);
                result.put(field.getName(), value instanceof Number || value instanceof Boolean ? value : String.valueOf(value));
            }
        } catch (Throwable error) { result.put("unavailable", error.toString()); }
        return result;
    }

    static Map<String, Object> runtime() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("java_version", System.getProperty("java.version"));
        result.put("java_vendor", System.getProperty("java.vendor"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        result.put("processors", rt.availableProcessors());
        result.put("heap_used_bytes", rt.totalMemory() - rt.freeMemory());
        result.put("heap_committed_bytes", rt.totalMemory());
        result.put("heap_max_bytes", rt.maxMemory());
        result.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        result.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(gc -> Map.of(
                "name", gc.getName(), "collections", gc.getCollectionCount(), "time_ms", gc.getCollectionTime())).toList());
        result.put("memory_meaning", "budget.reserved_bytes is CGSE estimated workspace; heap_* is JVM memory; neither is crafting CPU byte cost");
        try {
            result.put("mods", ModList.get().getMods().stream().map(mod -> Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList());
        } catch (Throwable unavailable) { result.put("mods_unavailable", unavailable.toString()); }
        return result;
    }

    private static List<Map<String, Object>> threads() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : Thread.getAllStackTraces().entrySet()) {
            String name = e.getKey().getName();
            if (!(name.toLowerCase(Locale.ROOT).contains("graph") || name.contains("Server thread") || e.getKey() == Thread.currentThread())) continue;
            result.add(Map.of("name", name, "state", e.getKey().getState().name(), "stack",
                    Arrays.stream(e.getValue()).limit(96).map(Object::toString).toList()));
            if (result.size() == 64) break;
        }
        return result;
    }
}
