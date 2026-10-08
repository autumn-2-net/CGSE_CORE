// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.google.gson.JsonObject;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

public final class DumpManager {
    public static final Logger LOG = LoggerFactory.getLogger("AE Dump");
    public static final ThreadLocal<CaptureRecord> CURRENT = new ThreadLocal<>();
    public static final AtomicLong IDS = new AtomicLong();
    private static final List<CaptureRecord> ACTIVE = new ArrayList<>();
    private static final Deque<CaptureRecord> HISTORY = new ArrayDeque<>();
    private static final String SESSION = Long.toString(System.currentTimeMillis(), 36);
    private static int autoCount;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> { var t = new Thread(r, "AE dump writer"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });

    public static CaptureRecord begin(IGrid grid, Level level, IActionSource source, AEKey target, long amount,
                                      CalculationStrategy strategy, String engine, boolean replan) {
        if (!DumpConfig.enabled.get()) return null;
        synchronized (ACTIVE) {
            if (ACTIVE.size() >= 32) { LOG.warn("Capture skipped: 32 requests still pending"); return null; }
        }
        UUID owner = source == null ? null : source.player().map(p -> p.getUUID()).orElse(null);
        var record = new CaptureRecord(IDS.incrementAndGet(), owner, engine, target, amount, strategy, replan);
        synchronized (ACTIVE) { ACTIVE.add(record); }
        record.request.add("configuration", configuration());
        record.request.addProperty("action_source_class", source == null ? null : source.getClass().getName());
        boolean before = owner != null || engine.equals("NETWORK_ONLY") || DumpConfig.machinePreviews.get();
        record.request.addProperty("full_network_capture_timing", before ? "before_calculation" : "on_failure_or_manual_export");
        var gridRef = new WeakReference<>(grid); var levelRef = new WeakReference<>(level); var sourceRef = new WeakReference<>(source);
        // Completed diagnostics must not keep chunks, grids or machine action hosts alive.
        record.deferredNetwork = () -> {
            var world = levelRef.get();
            if (world == null || world.getServer() == null) { record.network.completeExceptionally(new IllegalStateException("Network world unloaded before dump")); return; }
            Runnable capture = () -> {
                try {
                    IGrid g = gridRef.get(); IActionSource action = sourceRef.get();
                    if (g == null || g.isEmpty()) throw new IllegalStateException("Network unloaded before dump");
                    boolean originalSource = action != null;
                    if (action == null) action = IActionSource.empty();
                    var snapshot = NetworkSnapshot.capture(g, world, action, record.keys);
                    snapshot.network.addProperty("original_action_source_available", originalSource);
                    record.network.complete(snapshot);
                } catch (RuntimeException | LinkageError e) { record.network.completeExceptionally(e); LOG.error("Network capture failed id={}", record.id, e); }
            };
            if (world.getServer().isSameThread()) capture.run(); else world.getServer().execute(capture);
        };
        if (before) record.ensureNetwork();
        return record;
    }
    public static <T extends Future<ICraftingPlan>> T observe(CaptureRecord r, Supplier<T> action) {
        CaptureRecord previous = CURRENT.get();
        if (r != null) CURRENT.set(r);
        try { T f = action.get(); if (r != null) r.future = f; return f; }
        catch (RuntimeException | Error e) { if (r != null) r.finish(null, e); throw e; }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static void guard(Runnable action) {
        try { action.run(); } catch (RuntimeException | LinkageError e) { LOG.error("Diagnostic hook failed; solver result is unchanged", e); }
    }
    public static void tick() {
        List<CaptureRecord> completed = new ArrayList<>();
        synchronized (ACTIVE) {
            var it = ACTIVE.iterator();
            while (it.hasNext()) {
                var r = it.next();
                if (!r.done && r.future != null && r.future.isDone()) {
                    try { r.finish(r.future.get(), null); }
                    catch (ExecutionException e) { r.finish(null, e.getCause()); }
                    catch (Exception | LinkageError e) { r.finish(null, e); }
                }
                if (r.done) { it.remove(); completed.add(r); }
            }
        }
        for (var r : completed) {
            synchronized (HISTORY) { HISTORY.addLast(r); prune(); }
            if (r.owner != null || r.autoExport() || r.request.get("engine").getAsString().equals("NETWORK_ONLY"))
                LOG.info("Captured id={} engine={} result={} networkReady={} owner={}", r.id, r.request.get("engine"), r.result, r.network.isDone(), r.owner);
            if (DumpConfig.autoErrors.get() && r.autoExport() && autoCount < DumpConfig.maxAuto.get()) {
                autoCount++;
                if (!export(r, "automatic", text -> notifyOwner(r.owner, text))) autoCount--;
            }
        }
        synchronized (HISTORY) { prune(); }
    }
    private static long weight(CaptureRecord r) {
        if (!r.network.isDone()) return (1L << 20) + r.keys.estimatedBytes * 2;
        if (r.network.isCompletedExceptionally()) return 1L << 20;
        return Math.max(1L << 20, r.network.join().estimatedBytes * 2 + r.keys.resources.size() * 256L);
    }
    private static void prune() {
        long cutoff = System.currentTimeMillis() - DumpConfig.minutes.get() * 60_000L;
        while (!HISTORY.isEmpty() && HISTORY.peekFirst().created < cutoff) HISTORY.removeFirst();
        long bytes = HISTORY.stream().mapToLong(DumpManager::weight).sum();
        while (HISTORY.size() > DumpConfig.recentCount.get() || bytes > DumpConfig.retainedMiB.get() * (1L << 20) && HISTORY.size() > 1) {
            // Machine/requester churn must not displace a player's last preview first.
            CaptureRecord victim = HISTORY.stream().filter(r -> r.owner == null).findFirst().orElse(HISTORY.peekFirst());
            HISTORY.remove(victim); bytes -= weight(victim);
        }
    }
    public static List<CaptureRecord> records(UUID owner, boolean operator) {
        synchronized (HISTORY) { return HISTORY.stream().filter(r -> operator || Objects.equals(owner, r.owner) && owner != null).toList(); }
    }
    public static CaptureRecord find(UUID owner, boolean operator, long id) {
        var list = records(owner, operator);
        return list.stream().filter(r -> id == 0 ? operator || !r.request.get("replan").getAsBoolean() : r.id == id)
                .max(Comparator.comparingLong(r -> r.id)).orElse(null);
    }
    public static boolean export(CaptureRecord r, String reason, Consumer<String> reply) {
        if (r.saved != null) { reply.accept("AE dump 已保存：" + r.saved); return true; }
        if (!r.exporting.compareAndSet(false, true)) { reply.accept("这份 AE dump 正在导出。编号 " + r.id); return true; }
        try {
            WRITER.execute(() -> {
                try {
                    Path dir = FMLPaths.GAMEDIR.get().resolve("logs/ae-dump"); Files.createDirectories(dir);
                    long used;
                    try (var files = Files.list(dir)) { used = files.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum(); }
                    long maximum = DumpConfig.diskMiB.get() * (1L << 20);
                    if (used >= maximum) throw new java.io.IOException("AE dump 目录已达到容量限制，请移走已有 ZIP 后重试");
                    Path path = dir.resolve("ae-dump-" + SESSION + "-" + r.id + ".zip");
                    boolean full = DumpArchive.write(path, r, reason, Math.min(DumpConfig.archiveMiB.get() * (1L << 20), maximum - used));
                    r.saved = path.toAbsolutePath().toString();
                    String text = (full ? "完整 AE dump 已保存：" : "AE dump 已保存，但含未完成部分，请查看 export-status.json：") + r.saved;
                    LOG.warn(text); reply.accept(text);
                } catch (Exception | LinkageError e) { LOG.error("Export failed id={}", r.id, e); reply.accept("AE dump 导出失败：" + e.getMessage()); }
                finally { r.exporting.set(false); }
            });
            r.ensureNetwork();
            reply.accept("正在导出完整 AE 网络，编号 " + r.id + "；完成后会给出 ZIP 路径。"); return true;
        } catch (RejectedExecutionException e) { r.exporting.set(false); reply.accept("AE dump 导出队列已满，稍后重试。"); return false; }
    }
    public static JsonObject configuration() {
        var o = new JsonObject();
        try { for (var f : ConfigHolder.class.getFields()) if (f.getName().startsWith("ae2")) o.add(f.getName(), Data.JSON.toJsonTree(f.get(ConfigHolder.INSTANCE))); }
        catch (ReflectiveOperationException e) { o.addProperty("error", e.toString()); }
        return o;
    }
    public static JsonObject environment() {
        var rt = Runtime.getRuntime();
        var o = Data.object("aedump_version", "1.0.0", "java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "heap_used", Long.toString(rt.totalMemory() - rt.freeMemory()), "heap_max", Long.toString(rt.maxMemory()));
        var mods = new JsonObject(); ModList.get().getMods().forEach(m -> mods.addProperty(m.getModId(), m.getVersion().toString())); o.add("mods", mods); return o;
    }
    public static void notifyOwner(UUID owner, String message) {
        var server = ServerLifecycleHooks.getCurrentServer(); if (server == null || owner == null) return;
        server.execute(() -> { var p = server.getPlayerList().getPlayer(owner); if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(message)); });
    }
    public static String status() { synchronized (ACTIVE) { synchronized (HISTORY) {
        return "AE dump 1.0.0：启用=" + DumpConfig.enabled.get() + "，采集中=" + ACTIVE.size() + "，已保留=" + HISTORY.size() + "，自动导出=" + autoCount + "，队列=" + WRITER.getQueue().size();
    } } }
    public static void clear() {
        synchronized (ACTIVE) { ACTIVE.forEach(r -> r.network.completeExceptionally(new CancellationException("Server stopped"))); ACTIVE.clear(); }
        synchronized (HISTORY) { HISTORY.forEach(r -> { if (r.exporting.get()) r.network.completeExceptionally(new CancellationException("Server stopped")); }); HISTORY.clear(); }
        autoCount = 0; CURRENT.remove();
    }
    private DumpManager() {}
}
