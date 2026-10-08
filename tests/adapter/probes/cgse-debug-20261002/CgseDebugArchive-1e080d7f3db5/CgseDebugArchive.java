package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Stream into one ZIP: never build an entire JSON/ZIP byte array in the already stressed heap. */
public final class CgseDebugArchive {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final ZipOutputStream zip;
    private final Map<AEKey, String> keys = new LinkedHashMap<>();
    private final List<String> completed = new ArrayList<>();
    private final long maxBytes = Long.getLong("gtlcore.cgse.debug.maxReportMiB", 128) * (1L << 20);
    private long written;
    private String current;

    private CgseDebugArchive(ZipOutputStream zip) { this.zip = zip; }

    public static void write(Path output, CgseFailureDebug.Packet packet) throws IOException {
        Path temporary = output.resolveSibling(output.getFileName() + ".part");
        try (var stream = new BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW));
             var zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            zip.setLevel(1);
            CgseDebugArchive writer = new CgseDebugArchive(zip);
            Throwable failure = null;
            try {
                writer.entry("report.json", json -> JSON.toJson(packet.report(), Map.class, json));
                writer.entry("trace.jsonl", json -> writer.events(json, packet.trace()));
                writer.entry("fallback-trace.jsonl", json -> writer.events(json, packet.fallbackTrace()));
                writer.entry("solver-input.json", json -> writer.input(json, packet));
                writer.entry("primary-plan.json", json -> writer.plan(json, packet.primary()));
                writer.entry("final-plan.json", json -> writer.plan(json, packet.finalPlan()));
                for (var candidate : packet.candidates().entrySet())
                    writer.entry("candidate-" + candidate.getKey() + ".json", json -> writer.plan(json, candidate.getValue()));
                writer.entry("capture.json", json -> writer.capture(json, packet));
                writer.entry("resources.jsonl", writer::resources);
            } catch (Throwable error) {
                failure = error;
                try { zip.closeEntry(); } catch (Throwable suppressed) { error.addSuppressed(suppressed); }
            }
            // Always explicitly mark truncation. An interrupted .part is never advertised as a complete ZIP.
            zip.putNextEntry(new ZipEntry("export-status.json"));
            var status = new LinkedHashMap<String, Object>();
            status.put("complete", failure == null);
            status.put("completed_entries", writer.completed);
            status.put("failed_entry", failure == null ? null : writer.current);
            status.put("error", failure == null ? null : PlanningDebugTrace.stack(failure));
            status.put("uncompressed_bytes", writer.written);
            status.put("uncompressed_limit_bytes", writer.maxBytes);
            zip.write(JSON.toJson(status).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("CGSE local debug failure bundle v1\n" +
                    "Check export-status.json first. report.json explains failure origin and budget/heap distinction.\n" +
                    "trace.jsonl is chronological (sequence and elapsed nanoseconds); a bounded trace explicitly reports dropped events.\n" +
                    "solver-input.json stores stable string key IDs and decimal STRING quantities (no FP64 rounding).\n" +
                    "resources.jsonl maps IDs to AE key class and SNBT, including key NBT. Recipe/producer order is preserved.\n" +
                    "capture.json retains normalized alternatives and partial snapshot state if compilation was interrupted.\n" +
                    "primary-plan.json and final-plan.json use a compact instruction DAG: repetitions are never expanded.\n" +
                    "Replay only when export-status.complete and report.solver_input_replayable are true.\n" +
                    "This is planning input/state, not a full heap or thread checkpoint. Parallel timing, warmed caches and snapshot callbacks cannot be exactly replayed.\n" +
                    "No network upload, world save, player inventory scan or extra real extraction is performed.\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.move(temporary, output);
    }

    @FunctionalInterface
    private interface Entry { void write(JsonWriter json) throws IOException; }

    private void entry(String name, Entry action) throws IOException {
        current = name;
        zip.putNextEntry(new ZipEntry(name));
        OutputStream limited = new OutputStream() {
            @Override public void write(int b) throws IOException { check(1); zip.write(b); }
            @Override public void write(byte[] b, int offset, int length) throws IOException { check(length); zip.write(b, offset, length); }
            private void check(int length) throws IOException {
                if (written + length > maxBytes) throw new IOException("DEBUG_REPORT_SIZE_LIMIT; truncated entry=" + name);
                written += length;
            }
        };
        JsonWriter json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(limited, StandardCharsets.UTF_8), 16384));
        json.setSerializeNulls(true);
        json.setLenient(true); // JSONL entries contain multiple independent JSON values.
        action.write(json);
        json.flush();
        zip.closeEntry();
        completed.add(name);
    }

    private void events(JsonWriter json, List<PlanningDebugTrace.Event> events) throws IOException {
        for (var event : events) {
            JSON.toJson(event, PlanningDebugTrace.Event.class, json);
            json.jsonValue("\n");
        }
    }

    private String key(AEKey key) { return keys.computeIfAbsent(key, k -> "k" + keys.size()); }

    private void quantities(JsonWriter json, Map<AEKey, ? extends Number> amounts) throws IOException {
        json.beginObject();
        for (var e : amounts.entrySet()) json.name(key(e.getKey())).value(e.getValue().toString());
        json.endObject();
    }

    private void keySet(JsonWriter json, Collection<AEKey> values) throws IOException {
        json.beginArray();
        for (AEKey value : values) json.value(key(value));
        json.endArray();
    }

    private void input(JsonWriter json, CgseFailureDebug.Packet p) throws IOException {
        json.beginObject().name("schema").value("cgse-solver-input-v1")
                .name("complete").value(Boolean.TRUE.equals(p.report().get("solver_input_replayable")))
                .name("target").value(key(p.target())).name("amount").value(Long.toString(p.amount()))
                .name("preserve_seeds").value(p.preserve()).name("force_craft").value(p.forceCraft())
                .name("calculation_strategy").value(String.valueOf(p.report().get("calculation_strategy")));
        json.name("catalysts"); JSON.toJson(p.catalysts(), CatalystPolicy.class, json);
        json.name("budget"); JSON.toJson(p.report().get("budget"), Map.class, json);
        json.name("stock"); quantities(json, p.available());
        json.name("network_stock"); quantities(json, p.snapshot() != null ? p.snapshot().stock() : p.partial() == null ? Map.of() : p.partial().stock());
        json.name("external"); keySet(json, p.snapshot() != null ? p.snapshot().emitable() : p.partial() == null ? Set.of() : p.partial().external());
        json.name("required_seeds"); quantities(json, p.checkpoint() == null ? Map.of() : p.checkpoint().recoverySeeds());
        json.name("forecast"); quantities(json, p.checkpoint() == null ? Map.of() : p.checkpoint().forecast());
        json.name("recipes").beginArray();
        for (var recipe : p.recipes()) recipe(json, recipe);
        json.endArray().endObject();
    }

    private void recipe(JsonWriter json, GraphRecipe<AEKey> recipe) throws IOException {
        json.beginObject().name("id").value(recipe.id()).name("binding").value(recipe.binding()).name("slots").beginArray();
        for (var slot : recipe.slots()) json.beginObject().name("key").value(key(slot.key()))
                .name("amount").value(Long.toString(slot.amount())).name("input_slot").value(slot.inputSlot())
                .name("configuration").value(slot.configuration()).name("reusable").value(slot.reusable()).endObject();
        json.endArray().name("outputs"); quantities(json, recipe.outputs());
        json.endObject();
    }

    private void plan(JsonWriter json, GraphPlan<AEKey> plan) throws IOException {
        if (plan == null) { json.nullValue(); return; }
        json.beginObject().name("result").value(plan.result().name()).name("target").value(key(plan.target()))
                .name("amount").value(Long.toString(plan.amount())).name("preserve_seeds").value(plan.preserveSeeds())
                .name("search_nodes").value(plan.searchNodes()).name("planning_nanos").value(plan.planningNanos());
        json.name("initial"); quantities(json, plan.initialExact());
        json.name("seeds"); quantities(json, plan.seeds());
        json.name("missing"); quantities(json, plan.missingExact());
        json.name("seed_optimality"); JSON.toJson(plan.seedOptimality(), GraphPlan.SeedOptimality.class, json);
        json.name("recipes").beginArray();
        for (var recipe : plan.recipes().values()) recipe(json, recipe);
        json.endArray().name("program_root").value(plan.steps() == null ? -1 : 0).name("program").beginArray();
        var ids = new IdentityHashMap<PlanStep, Integer>();
        List<PlanStep> nodes = new ArrayList<>();
        if (plan.steps() != null) { ids.put(plan.steps(), 0); nodes.add(plan.steps()); }
        for (int index = 0; index < nodes.size(); index++) {
            PlanStep step = nodes.get(index);
            json.beginObject().name("id").value(index);
            if (step instanceof PlanStep.Batch batch) {
                json.name("kind").value("batch").name("recipe").value(batch.recipe()).name("runs").value(Long.toString(batch.runs()));
            } else if (step instanceof PlanStep.Repeat repeat) {
                json.name("kind").value("repeat").name("times").value(Long.toString(repeat.times()))
                        .name("body").value(programId(repeat.body(), ids, nodes));
            } else if (step instanceof PlanStep.Sequence sequence) {
                json.name("kind").value("sequence").name("children").beginArray();
                for (PlanStep child : sequence.children()) json.value(programId(child, ids, nodes));
                json.endArray();
            }
            json.endObject();
        }
        json.endArray().endObject();
    }

    private int programId(PlanStep step, IdentityHashMap<PlanStep, Integer> ids, List<PlanStep> nodes) {
        Integer old = ids.get(step);
        if (old != null) return old;
        int next = nodes.size();
        ids.put(step, next);
        nodes.add(step);
        return next;
    }

    private void stacks(JsonWriter json, List<GenericStack> stacks) throws IOException {
        json.beginArray();
        for (var stack : stacks) json.beginObject().name("key").value(key(stack.what()))
                .name("amount").value(Long.toString(stack.amount())).endObject();
        json.endArray();
    }

    private void capture(JsonWriter json, CgseFailureDebug.Packet p) throws IOException {
        json.beginObject().name("snapshot_complete").value(p.snapshot() != null).name("partial_state");
        JSON.toJson(p.partial() == null ? null : p.partial().state(), Map.class, json);
        var entries = p.entries().isEmpty() && p.partial() != null ? p.partial().entries() : p.entries();
        json.name("raw_catalog_present").value(!entries.isEmpty())
                .name("note").value("A warm compiled catalog may have released raw alternatives; solver-input.json still has the effective immutable recipes.")
                .name("entries").beginArray();
        for (var entry : entries) {
            var values = entry.values();
            var pattern = entry.pattern();
            json.beginObject().name("priority").value(entry.priority()).name("pattern_class").value(values.type())
                    .name("definition").value(key(values.definition())).name("external").value(pattern.debugExternal())
                    .name("variant_count").value(pattern.size()).name("bounded").value(pattern.bounded());
            json.name("outputs"); stacks(json, pattern.debugOutputs());
            json.name("inputs").beginArray();
            for (var input : pattern.debugInputs()) {
                json.beginObject().name("multiplier").value(Long.toString(input.multiplier())).name("candidates").beginArray();
                for (var choice : input.candidates()) json.beginObject().name("key").value(key(choice.stack().what()))
                        .name("amount").value(Long.toString(choice.stack().amount()))
                        .name("remaining").value(choice.remaining() == null ? null : key(choice.remaining()))
                        .name("configuration").value(choice.configuration()).name("reusable").value(choice.reusable()).endObject();
                json.endArray().endObject();
            }
            json.endArray().endObject();
        }
        json.endArray().endObject();
    }

    private void resources(JsonWriter json) throws IOException {
        for (var entry : keys.entrySet()) {
            json.beginObject().name("id").value(entry.getValue())
                .name("class").value(entry.getKey().getClass().getName()).name("description").value(entry.getKey().toString())
                .name("snbt").value(entry.getKey().toTagGeneric().toString()).endObject();
            json.jsonValue("\n");
        }
    }
}
