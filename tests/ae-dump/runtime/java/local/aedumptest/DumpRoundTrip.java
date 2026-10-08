package local.aedumptest;

import appeng.api.crafting.*;
import appeng.api.stacks.*;
import com.google.gson.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Test-only: verifies original encoded patterns survive the archive and decode in the actual modpack. */
@net.minecraftforge.fml.common.Mod("localaedumpvalidation")
public final class DumpRoundTrip {
    public static String checks() throws Exception {
        return checks(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    }
    public static String checks(UUID owner) throws Exception {
        var r = org.gtlcore.aedump.DumpManager.find(owner, false, 0);
        if (r == null) throw new AssertionError("Missing owned preview");
        if (org.gtlcore.aedump.DumpManager.find(UUID.randomUUID(), false, r.id) != null) throw new AssertionError("Other player's dump exposed");
        if (org.gtlcore.aedump.DumpManager.find(null, true, r.id) != r) throw new AssertionError("Operator lookup broken");
        Path path = Path.of("logs/ae-dump/validation-truncated-" + System.nanoTime() + ".zip");
        boolean full = org.gtlcore.aedump.DumpArchive.write(path, r, "size-limit-validation", 2048);
        if (full) throw new AssertionError("Size cap not applied");
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var status = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("export-status.json")).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (status.get("complete").getAsBoolean() || !status.get("error").getAsString().contains("ARCHIVE_SIZE_LIMIT")) throw new AssertionError("Truncation not declared");
        }
        var o = new JsonObject(); o.addProperty("permissions", "pass"); o.addProperty("archive_size_limit", "pass");
        o.addProperty("last_owned_id", r.id); o.addProperty("file", path.toString()); return o.toString();
    }
    public static String verify(Level level, String file) throws Exception {
        try (var zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            var status = read(zip, "export-status.json").getAsJsonObject();
            if (!status.get("complete").getAsBoolean()) throw new AssertionError("Cannot roundtrip an incomplete archive");
            var request = read(zip, "request.json").getAsJsonObject();
            var result = read(zip, "result.json").getAsJsonObject();
            var input = read(zip, "cgse-input.json").getAsJsonObject();
            var graph = read(zip, "cgse-plan.json");
            if (request.get("engine").getAsString().equals("CGSE") &&
                    (!graph.isJsonNull() || result.get("status").getAsString().startsWith("FEASIBLE"))) {
                if (!input.has("complete") || !input.get("complete").getAsBoolean())
                    throw new AssertionError("Portable request delegate was not captured");
                var coordinator = result.getAsJsonObject("coordinator");
                for (String flag : List.of("directEmission", "fallbackAttempted", "fallbackMode"))
                    if (!coordinator.has(flag)) throw new AssertionError("Missing delegated coordinator flag: " + flag);
                if (!input.has("available") || graph.isJsonNull()) throw new AssertionError("Missing actual input or selected plan");
                if (coordinator.get("directEmission").getAsBoolean() && input.get("force_craft").getAsBoolean())
                    throw new AssertionError("Direct emission incorrectly captured as force-craft");
            }
            Map<String, AEKey> keys = new HashMap<>();
            var lines = new String(zip.getInputStream(zip.getEntry("resources.jsonl")).readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            for (String line : lines) {
                var r = JsonParser.parseString(line).getAsJsonObject();
                var key = AEKey.fromTagGeneric(TagParser.parseTag(r.get("snbt").getAsString()));
                if (key == null) throw new IllegalStateException("Key failed: " + r.get("id"));
                keys.put(r.get("id").getAsString(), key);
            }
            var errors = new JsonArray(); int decoded = 0, matching = 0;
            lines = new String(zip.getInputStream(zip.getEntry("patterns.jsonl")).readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            for (String line : lines) {
                var p = JsonParser.parseString(line).getAsJsonObject();
                var pattern = PatternDetailsHelper.decodePattern((AEItemKey) keys.get(p.get("definition").getAsString()), level);
                if (pattern == null) { errors.add(p.get("id").getAsString() + ": no decoder"); continue; }
                decoded++;
                boolean same = equal(pattern.getOutputs(), p.getAsJsonArray("outputs"), keys);
                var expected = p.getAsJsonArray("inputs"); var inputs = pattern.getInputs(); same &= expected.size() == inputs.length;
                for (int i = 0; same && i < inputs.length; i++) {
                    var in = expected.get(i).getAsJsonObject();
                    same &= in.get("multiplier").getAsLong() == inputs[i].getMultiplier();
                    same &= equal(inputs[i].getPossibleInputs(), in.getAsJsonArray("possible"), keys);
                }
                if (same) matching++; else errors.add(p.get("id").getAsString() + ": decoded callbacks differ; raw captured callbacks required");
            }
            var o = new JsonObject(); o.addProperty("resources", keys.size()); o.addProperty("patterns", lines.size());
            o.addProperty("decoded", decoded); o.addProperty("matching", matching); o.add("errors", errors);
            return o.toString();
        }
    }
    private static JsonElement read(ZipFile zip, String name) throws Exception {
        return JsonParser.parseString(new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8));
    }
    private static boolean equal(GenericStack[] stacks, JsonArray json, Map<String, AEKey> keys) {
        if (stacks.length != json.size()) return false;
        for (int i = 0; i < stacks.length; i++) {
            var s = json.get(i).getAsJsonObject();
            if (!stacks[i].what().equals(keys.get(s.get("key").getAsString())) || stacks[i].amount() != s.get("amount").getAsLong()) return false;
        }
        return true;
    }
}
