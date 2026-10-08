package org.cgse.core;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Complete captured-catalog replay using the standalone core and no JSON/game dependency. */
public final class DatasetReplay {
    private static final Map<String, String> KEYS = new HashMap<>();

    record Catalog(List<GraphRecipe<String>> recipes, Map<String, List<GraphRecipe<String>>> producers,
                           Map<String, Long> stock, Set<String> external, boolean preserve, boolean force,
                           int parallelism, int extraCopies) {}

    private record Request(String target, long amount, String expected) {}

    static String text(DataInputStream input) throws Exception {
        int size = input.readInt();
        if (size < 0 || size > 64 * 1024 * 1024) throw new IllegalArgumentException("Invalid string length");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new java.io.EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String key(DataInputStream input) throws Exception {
        String value = text(input);
        return KEYS.computeIfAbsent(value, unused -> value);
    }

    private static Map<String, Long> amounts(DataInputStream input) throws Exception {
        int count = input.readInt();
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) amounts.put(key(input), input.readLong());
        return amounts;
    }

    static Catalog catalog(DataInputStream input) throws Exception {
        Map<String, Long> stock = amounts(input);
        Set<String> external = new LinkedHashSet<>();
        for (int count = input.readInt(); count > 0; count--) external.add(key(input));
        boolean preserve = input.readBoolean(), force = input.readBoolean();
        int parallelism = input.readInt();
        int extraCopies = Math.toIntExact(input.readLong());
        List<GraphRecipe<String>> recipes = new ArrayList<>();
        Map<String, GraphRecipe<String>> byId = new LinkedHashMap<>();
        for (int count = input.readInt(); count > 0; count--) {
            String id = text(input), binding = text(input);
            List<GraphRecipe.Slot<String>> slots = new ArrayList<>();
            for (int slotCount = input.readInt(); slotCount > 0; slotCount--)
                slots.add(new GraphRecipe.Slot<>(key(input), input.readLong(), input.readInt(), input.readBoolean(), input.readBoolean()));
            GraphRecipe<String> recipe = new GraphRecipe<>(id, binding, slots, amounts(input));
            if (byId.put(id, recipe) != null) throw new IllegalArgumentException("Duplicate recipe " + id);
            recipes.add(recipe);
        }
        Map<String, List<GraphRecipe<String>>> producers = new LinkedHashMap<>();
        int producerCount = input.readInt();
        for (int index = 0; index < producerCount; index++) {
            String resource = key(input);
            List<GraphRecipe<String>> values = new ArrayList<>();
            for (int count = input.readInt(); count > 0; count--)
                values.add(Objects.requireNonNull(byId.get(text(input)), "Unknown captured producer"));
            producers.put(resource, values);
        }
        if (producerCount == -1) for (var recipe : recipes) for (String resource : recipe.executionOutputs().keySet())
            producers.computeIfAbsent(resource, unused -> new ArrayList<>()).add(recipe);
        return new Catalog(recipes, producers, stock, external, preserve, force, parallelism, extraCopies);
    }

    private static GraphCompiler<String> compiler(Catalog manual, Catalog extra, String mode, long seed) {
        if (mode.equals("manual")) return new GraphCompiler<>(manual.recipes(), manual.producers());
        List<GraphRecipe<String>> all = new ArrayList<>(manual.recipes());
        all.addAll(extra.recipes());
        if (mode.equals("random")) {
            Collections.shuffle(all, new Random(seed));
            return new GraphCompiler<>(all);
        }
        if (!mode.equals("manual-first") && !mode.equals("extra-first")) throw new IllegalArgumentException("Unknown mode " + mode);
        Catalog first = mode.equals("manual-first") ? manual : extra;
        Catalog second = mode.equals("manual-first") ? extra : manual;
        Set<String> resources = new LinkedHashSet<>(manual.producers().keySet());
        resources.addAll(extra.producers().keySet());
        Map<String, List<GraphRecipe<String>>> producers = new LinkedHashMap<>();
        for (String resource : resources) {
            List<GraphRecipe<String>> values = new ArrayList<>(first.producers().getOrDefault(resource, List.of()));
            values.addAll(second.producers().getOrDefault(resource, List.of()));
            producers.put(resource, values);
        }
        return new GraphCompiler<>(all, producers);
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static boolean solve(Catalog data, GraphCompiler<String> compiler, Request request, String mode, long seed,
                                 long timeout, long workLimit, long memory, BufferedWriter writer) throws Exception {
        PlanningBudget budget = new PlanningBudget(timeout, workLimit, memory, () -> false, System::nanoTime);
        long started = System.nanoTime();
        String result, error = "";
        boolean verified = false, feasible = false;
        int selectedRecipes = 0, seeds = 0, missing = 0;
        long selectedJei = 0;
        try (var work = new WorkClose(new CatalystPlanningWork<>(new CatalystPolicy(data.parallelism(), data.extraCopies()), budget,
                policy -> new GraphPlanningWork<>(compiler, request.target(), request.amount(), data.stock(), data.external(),
                        Map.of(), data.preserve(), data.force(), budget).catalysts(policy)))) {
            GraphPlan<String> plan;
            try {
                while (!work.value.step()) {}
                plan = work.value.result();
            } catch (PlanningBudget.Exhausted exhausted) {
                plan = work.value.limited(exhausted);
            }
            result = plan.result().name();
            feasible = plan.feasible();
            selectedRecipes = plan.patternTimesExact().size();
            selectedJei = plan.patternTimesExact().keySet().stream().filter(id -> id.startsWith("jei:")).count();
            seeds = plan.seeds().size();
            missing = plan.missingExact().size();
            if (feasible) {
                PlanVerifier.verify(plan);
                PlanVerifier.verifyRuntimeInventory(plan);
                for (var entry : plan.initialExact().entrySet())
                    if (!data.external().contains(entry.getKey()) && entry.getValue().compareTo(BigInteger.valueOf(data.stock().getOrDefault(entry.getKey(), 0L))) > 0)
                        throw new AssertionError("Plan overdraws captured inventory: " + entry.getKey());
                plan.executionDependencies();
                verified = true;
            }
            if (request.expected().equals("SAT") && !feasible) throw new AssertionError("Expected feasible replay but got " + result);
            if (request.expected().equals("UNSAT") && feasible) throw new AssertionError("Unexpected feasible replay");
        } catch (PlanningBudget.Exhausted exhausted) {
            result = exhausted.limit().name();
            if (request.expected().equals("SAT")) error = "Expected SAT; replay exhausted " + result;
        } catch (Throwable failure) {
            result = "ERROR";
            error = failure.toString();
            failure.printStackTrace(System.err);
        }
        String assessment = !error.isEmpty() ? "ERROR" : verified ? "VERIFIED_FEASIBLE" : "INCONCLUSIVE";
        String row = "{\"mode\":" + quote(mode) + ",\"seed\":" + seed + ",\"target\":" + quote(request.target()) +
                ",\"amount\":" + request.amount() + ",\"expected\":" + quote(request.expected()) + ",\"result\":" + quote(result) +
                ",\"assessment\":" + quote(assessment) + ",\"feasible\":" + feasible + ",\"verified\":" + verified +
                ",\"catalog_recipes\":" + compiler.catalog().size() + ",\"selected_recipes\":" + selectedRecipes +
                ",\"selected_jei_recipes\":" + selectedJei + ",\"seed_types\":" + seeds + ",\"missing_types\":" + missing +
                ",\"work\":" + budget.nodes() + ",\"search_work\":" + budget.searchWork() + ",\"compilation_work\":" + budget.compilationWork() +
                ",\"peak_bytes\":" + budget.peakBytes() + ",\"elapsed_ms\":" + (System.nanoTime() - started) / 1_000_000.0 +
                ",\"error\":" + quote(error) + "}";
        writer.write(row);
        writer.newLine();
        writer.flush();
        System.out.println(mode + " " + request.target() + " x" + request.amount() + " -> " + result + " / " + assessment);
        return error.isEmpty();
    }

    private record WorkClose(CatalystPlanningWork<String> value) implements AutoCloseable {
        @Override
        public void close() { value.close(); }
    }

    public static void main(String[] args) throws Exception {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))));
             BufferedWriter writer = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            if (!text(input).equals("CGSE_DATASET_V2")) throw new IllegalArgumentException("Unknown dataset transfer format");
            Catalog manual = catalog(input), extra = catalog(input);
            long timeout = input.readLong(), workLimit = input.readLong(), memory = input.readLong(), seed = input.readLong();
            List<String> modes = new ArrayList<>();
            for (int count = input.readInt(); count > 0; count--) modes.add(text(input));
            List<Request> requests = new ArrayList<>();
            for (int count = input.readInt(); count > 0; count--) requests.add(new Request(key(input), input.readLong(), text(input)));
            if (input.read() != -1) throw new IllegalArgumentException("Trailing dataset bytes");
            System.out.println("Loaded complete catalogs: manual=" + manual.recipes().size() + " extra=" + extra.recipes().size() +
                    " stock_keys=" + manual.stock().size() + " resource_keys=" + KEYS.size());
            int errors = 0;
            for (String mode : modes) {
                GraphCompiler<String> compiler = compiler(manual, extra, mode, seed);
                for (Request request : requests) if (!solve(manual, compiler, request, mode, seed, timeout, workLimit, memory, writer)) errors++;
            }
            if (errors > 0) throw new AssertionError("Invalid or unexpected results: " + errors);
        }
    }
}
