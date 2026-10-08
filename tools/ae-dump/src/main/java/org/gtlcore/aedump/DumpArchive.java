// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, streaming ZIP. An incomplete section is never advertised as replayable. */
public final class DumpArchive {
    private final ZipOutputStream zip;
    private final long limit;
    private long written;
    private final List<String> completed = new ArrayList<>();
    private String current;
    private DumpArchive(ZipOutputStream zip, long limit) { this.zip = zip; this.limit = limit; }
    public static boolean write(Path path, CaptureRecord r, String reason, long limit) throws IOException {
        Path part = path.resolveSibling(path.getFileName() + ".part");
        boolean full;
        try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)), StandardCharsets.UTF_8)) {
            zip.setLevel(1); var w = new DumpArchive(zip, limit);
            Throwable error = null;
            NetworkSnapshot n = null;
            try {
                w.entry("request.json", r.request);
                w.entry("environment.json", DumpManager.environment());
                w.entry("result.json", Data.object("status", r.result, "exception", r.error, "fallback", r.fallback, "trigger", reason,
                        "budget", r.budget, "coordinator", r.coordinator));
                n = r.network.join();
                w.entry("network.json", n.network); w.lines("patterns.jsonl", n.patterns); w.lines("nodes.jsonl", n.nodes);
                w.entry("cgse-input.json", r.input); w.entry("cgse-plan.json", r.graphPlan); w.entry("ae-plan.json", r.aePlan);
                w.entry("solver-states.json", r.states());
                w.entry("trace.json", Data.JSON.toJsonTree(r.trace == null ? null : r.trace.snapshot()));
                w.lines("resources.jsonl", r.keys.resources);
            } catch (Exception | LinkageError e) { error = e; try { zip.closeEntry(); } catch (Exception ignored) {} }
            full = error == null && n != null && n.complete;
            zip.putNextEntry(new ZipEntry("export-status.json"));
            zip.write(Data.JSON.toJson(Data.object("schema", "ae-network-dump-v1", "complete", full, "archive_complete", error == null,
                    "network_complete", n != null && n.complete, "completed_entries", w.completed, "failed_entry", error == null ? null : w.current,
                    "error", Trace.stack(error), "uncompressed_bytes", Long.toString(w.written), "limit_bytes", Long.toString(limit))).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("AE Network Dump 1.0.0 / Minecraft 1.20.1 Forge\n" +
                    "Check export-status.json and network.capture_errors before replay. Incomplete captures are explicitly marked.\n" +
                    "network.json: entire connected ME network, ALL cached/advertised/permission-aware extractable stock, emitter keys, CPUs, and exact producer order.\n" +
                    "patterns.jsonl: ALL registered/provider patterns, original encoded definitions (via resources.jsonl), alternatives, multipliers, remaining containers, validity callbacks and dispatch priorities.\n" +
                    "nodes.jsonl: connected grid topology and block/part NBT, including providers, machine settings and storage cells where exposed.\n" +
                    "resources.jsonl: AE equality-based IDs and clean generic key SNBT. observed_generic_snbt records polluted legacy key caches when different.\n" +
                    "All resource quantities, including fluids, are base-unit decimal STRINGS; parse as integers, never double.\n" +
                    "Keep recipe, provider and per-key producer order unchanged. Do not sort by item ID or serialize only the CGSE-selected graph.\n" +
                    "For CGSE/MaxFast comparison use the SAME frozen network stock, raw patterns, request and original mod/recipe environment.\n" +
                    "cgse-input.json is the actual target-specific compiled input and can differ from the full network; this difference is diagnostic.\n" +
                    "Player previews capture the network before solving. By default automated requests capture the network on failure/manual export; see request.full_network_capture_timing.\n" +
                    "CGSE's actual sampled stock is preserved separately in cgse-input.json. No real extraction/submission is performed.\n" +
                    "Candidate checks cover declared alternatives and fuzzy matches in the full network universe. Mod-specific callbacks require the original mods/datapacks to replay.\n" +
                    "This is a complete network diagnostic snapshot, not a JVM heap/checkpoint or a replacement world save. External machines/world state are not executable from JSON alone.\n" +
                    "No automatic upload. The archive contains network contents and locations; hand the ZIP to the developer for diagnosis.\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.move(part, path); return full;
    }
    private void entry(String name, JsonElement value) throws IOException { write(name, j -> Data.JSON.toJson(value == null ? JsonNull.INSTANCE : value, j)); }
    private void lines(String name, JsonArray values) throws IOException {
        write(name, j -> { for (var value : values) { Data.JSON.toJson(value, j); j.jsonValue("\n"); } });
    }
    @FunctionalInterface private interface Output { void write(JsonWriter json) throws IOException; }
    private void write(String name, Output action) throws IOException {
        current = name; zip.putNextEntry(new ZipEntry(name));
        var limited = new OutputStream() {
            public void write(int value) throws IOException { check(1); zip.write(value); }
            public void write(byte[] b, int off, int len) throws IOException { check(len); zip.write(b, off, len); }
            private void check(int n) throws IOException { if (written + n > limit) throw new IOException("ARCHIVE_SIZE_LIMIT: " + name); written += n; }
        };
        var json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(limited, StandardCharsets.UTF_8), 16384));
        json.setSerializeNulls(true); json.setLenient(true); action.write(json); json.flush(); zip.closeEntry(); completed.add(name);
    }
}
