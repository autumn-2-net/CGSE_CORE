package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.RegistryBuilder;
import com.google.gson.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

public final class DebugArchiveTest {
    private static int checks;
    private static AEKey iron, block, water;
    private static Path directory;

    public static void main(String[] args) throws Exception {
        directory = Path.of(System.getProperty("gtlcore.cgse.debug.directory"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var builder = new RegistryBuilder<AEKeyType>().setName(new ResourceLocation("gtlcore", "debug_test_keys"));
        var create = RegistryBuilder.class.getDeclaredMethod("create");
        create.setAccessible(true);
        var registry = (net.minecraftforge.registries.IForgeRegistry<AEKeyType>) create.invoke(builder);
        AEKeyTypesInternal.setRegistry(() -> registry);
        AEKeyTypesInternal.register(AEKeyType.items()); AEKeyTypesInternal.register(AEKeyType.fluids());
        iron = AEItemKey.of(Items.IRON_INGOT); block = AEItemKey.of(Items.IRON_BLOCK);
        var nbt = new CompoundTag(); nbt.putLong("exact", 9007199254740993L); nbt.putString("variant", "test");
        water = AEFluidKey.of(Fluids.WATER, nbt);
        if (args.length > 0 && args[0].equals("cap")) {
            var packet = packet(Map.of("large", "x".repeat(2_000_000)), null, null);
            CgseDebugArchive.write(directory.resolve("cap.zip"), packet);
            check(!read(directory.resolve("cap.zip"), "export-status.json").getAsJsonObject().get("complete").getAsBoolean(), "size limit explicit");
            System.out.println("DEBUG_CAP_PASS checks=" + checks); return;
        }
        if (args.length > 0 && args[0].equals("quota")) {
            for (int i = 0; i < 10; i++) missing();
            check(archives().size() == 8, "ordinary missing archives capped separately");
            failure(PlanningBudget.Limit.MEMORY_LIMIT);
            check(archives().size() == 9, "background missing previews cannot consume failure archive quota");
            System.out.println("DEBUG_QUOTA_PASS checks=" + checks); return;
        }
        successDoesNotExport();
        for (var limit : PlanningBudget.Limit.values()) failure(limit);
        missing();
        exception();
        fallbackRetainsFailure();
        cancelledDoesNotExport();
        partial();
        compactProgram();
        boundedTrace();
        for (Path path : archives()) verifyArchive(path);
        System.out.println("DEBUG_ARCHIVE_PASS checks=" + checks + " archives=" + archives().size());
    }

    private static GraphCompiler<AEKey> compiler() {
        return new GraphCompiler<>(List.of(new GraphRecipe<>("make", "binding", List.of(new GraphRecipe.Slot<>(iron, 1, 0)), Map.of(block, 1L))));
    }
    private static GtlPatternCatalog.Snapshot snapshot(Map<AEKey, Long> stock) {
        return new GtlPatternCatalog.Snapshot(new GtlPatternCatalog.Structure(new CapturedPatternCatalog(List.of(), 1),
                Set.of(iron, block, water), Set.of(iron), Set.of(), false, Map.of(), 19), Map.copyOf(stock), Set.of(), 19, false);
    }
    private static CgseFailureDebug session(PlanningBudget b) {
        return new CgseFailureDebug(b, block, 9007199254740993L, CalculationStrategy.REPORT_MISSING_ITEMS, true, null);
    }
    private static Map<AEKey, Long> stock() { return Map.of(iron, Long.MAX_VALUE, water, 9007199254740993L); }
    private static PlanningBudget budget() { return new PlanningBudget(0, 200000, 128L << 20, () -> false, System::nanoTime); }
    private static void complete(CgseFailureDebug s, Throwable e, GraphPlan<AEKey> plan) throws Exception {
        s.complete(e, snapshot(stock()), compiler(), stock(), CatalystPolicy.MINIMAL, true, plan, List.of(), List.of(), Map.of("test", true));
        check(CgseFailureDebug.awaitWrites(10000), "writer drained");
    }
    private static GraphPlan<AEKey> funded() {
        return new GraphPlanner<>(compiler()).plan(block, 9007199254740993L, stock(), true, true, budget());
    }
    private static void successDoesNotExport() throws Exception {
        int before = archives().size();
        var b = budget(); var s = session(b); complete(s, null, funded());
        check(archives().size() == before, "no success archive");
        check(b.debug().attachment == null && b.debug().events().isEmpty(), "completed request releases debug state");
    }
    private static void failure(PlanningBudget.Limit limit) throws Exception {
        int before = archives().size();
        var clock = new AtomicLong();
        var b = new PlanningBudget(1, 1, 64, () -> false, clock::get);
        var s = session(b);
        Throwable failure;
        try {
            switch (limit) {
                case MEMORY_LIMIT -> {
                    var optional = PlanningBudget.class.getDeclaredMethod("tryReserve", long.class);
                    optional.setAccessible(true);
                    check(!(boolean) optional.invoke(b, 128L), "optional workspace refusal");
                    b.reserve(128);
                }
                case SEARCH_LIMIT -> { b.check(); b.check(); }
                case TIMEOUT -> { b.start(); clock.set(2_000_000); b.check(); }
                default -> throw b.exhausted(limit, "controlled " + limit);
            }
            throw new AssertionError("limit missing");
        } catch (PlanningBudget.Exhausted expected) { failure = expected; }
        complete(s, failure, null);
        check(archives().size() == before + 1, limit + " export");
    }
    private static void missing() throws Exception {
        var b = budget(); var s = session(b);
        var plan = new GraphPlanner<>(compiler()).plan(block, 9007199254740993L, Map.of(), true, true, b);
        check(!plan.feasible() && !plan.missing().isEmpty(), "actual missing preview");
        s.complete(null, snapshot(Map.of()), compiler(), Map.of(), CatalystPolicy.MINIMAL, true, plan, List.of(), List.of(), Map.of());
        check(CgseFailureDebug.awaitWrites(10000), "missing archive drained");
    }
    private static void exception() throws Exception {
        var b = budget(); var s = session(b);
        s.candidates(Map.of("candidate", funded()));
        complete(s, new IllegalStateException("CONTROLLED_VERIFICATION_FAILURE"), null);
        Path path = archives().get(archives().size() - 1);
        check(read(path, "candidate-candidate.json").getAsJsonObject().get("result").getAsString().equals("FEASIBLE"), "candidate before exception is retained");
    }
    private static void fallbackRetainsFailure() throws Exception {
        var b = budget(); var s = session(b);
        s.mark("primary:MEMORY_LIMIT", null, Map.of("phase", "counts"));
        var quick = budget(); s.fallback(quick); quick.note("fallback", "success after failed primary");
        complete(s, null, funded());
        Path path = archives().get(archives().size() - 1);
        var report = read(path, "report.json").getAsJsonObject();
        check(!report.get("fallback_budget").isJsonNull(), "fallback metrics retained");
        check(report.getAsJsonArray("failure_reasons").get(0).getAsString().contains("MEMORY_LIMIT"), "original reason retained");
    }
    private static void cancelledDoesNotExport() throws Exception {
        int before = archives().size(); var b = budget(); var s = session(b);
        complete(s, new CancellationException("user cancelled"), null);
        check(archives().size() == before, "user cancellation no archive");
    }
    private static void partial() throws Exception {
        var b = budget(); var s = session(b);
        s.complete(new IllegalStateException("snapshot interrupted"), null, null, null, CatalystPolicy.MINIMAL, true,
                null, List.of(), List.of(), Map.of("snapshot", "not complete"));
        check(CgseFailureDebug.awaitWrites(10000), "partial archive drained");
        Path path = archives().get(archives().size() - 1);
        check(!read(path, "report.json").getAsJsonObject().get("solver_input_replayable").getAsBoolean(), "partial never claimed replayable");
    }
    private static CgseFailureDebug.Packet packet(Map<String, Object> report, GraphPlan<AEKey> primary, GraphPlan<AEKey> plan) {
        return new CgseFailureDebug.Packet(report, List.of(), List.of(), block, Long.MAX_VALUE, true, true, CatalystPolicy.MINIMAL,
                null, snapshot(stock()), compiler().catalog(), stock(), List.of(), null, primary, plan, Map.of());
    }
    private static void compactProgram() throws Exception {
        var batch = new PlanStep.Batch("make", Long.MAX_VALUE);
        var repeat = new PlanStep.Repeat(batch, Long.MAX_VALUE);
        var program = new PlanStep.Sequence(List.of(repeat, repeat));
        BigInteger exact = BigInteger.valueOf(Long.MAX_VALUE).pow(2).multiply(BigInteger.TWO);
        var plan = new GraphPlan<>(block, Long.MAX_VALUE, true, program, Map.of("make", compiler().catalog().get(0)),
                Map.of(iron, exact), Map.of(), Map.of(iron, exact), GraphPlan.Result.MISSING_INPUT, 0, 0);
        Path path = directory.resolve("compact.zip");
        CgseDebugArchive.write(path, packet(Map.of("solver_input_replayable", true), plan, plan));
        var saved = read(path, "primary-plan.json").getAsJsonObject();
        check(saved.getAsJsonArray("program").size() == 3, "program DAG is not expanded");
        check(saved.getAsJsonObject("initial").entrySet().iterator().next().getValue().getAsString().equals(exact.toString()), "BigInteger exact roundtrip");
        check(Files.size(path) < 20000, "huge repeated plan has small archive");
    }
    private static void boundedTrace() {
        var b = budget(); b.debug("bounded");
        for (int i = 0; i < 20000; i++) b.note("test", "event " + i);
        check(((Number) b.debug().summary().get("events_dropped")).longValue() > 0, "dropped trace explicit");
        check(b.debug().events().get(0).detail().equals("event 0"), "trace beginning kept");
        var events = b.debug().events();
        check(events.get(events.size() - 1).detail().equals("event 19999"), "trace tail kept");
    }
    private static List<Path> archives() throws Exception {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("cgse-failure-") && p.toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; } })).toList();
        }
    }
    private static void verifyArchive(Path path) throws Exception {
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            check(read(path, "export-status.json").getAsJsonObject().get("complete").getAsBoolean(), "complete valid ZIP");
            for (String entry : List.of("trace.jsonl", "fallback-trace.jsonl", "resources.jsonl")) {
                String text = new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\n")) if (!line.isBlank()) {
                    var row = JsonParser.parseString(line).getAsJsonObject();
                    if (entry.equals("resources.jsonl")) {
                        AEKey key = AEKey.fromTagGeneric(TagParser.parseTag(row.get("snbt").getAsString()));
                        check(key != null, "real AE key/NBT can be decoded");
                        if (key.equals(water)) check(((AEFluidKey) key).getTag().getLong("exact") == 9007199254740993L, "fluid NBT exact");
                    }
                }
            }
            var input = read(path, "solver-input.json").getAsJsonObject();
            check(input.get("amount").getAsJsonPrimitive().isString(), "quantity stored as decimal string");
            check(input.get("amount").getAsLong() == 9007199254740993L, "quantity above FP64 boundary exact");
        }
    }
    private static JsonElement read(Path path, String name) throws Exception {
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
             var reader = new java.io.InputStreamReader(zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
