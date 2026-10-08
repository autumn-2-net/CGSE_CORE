package org.cgse.core;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.cgse.core.DatasetReplay.catalog;
import static org.cgse.core.DatasetReplay.quote;
import static org.cgse.core.DatasetReplay.text;

/** QuantitySweep-compatible replay of exact archived group catalogs and request sequences. */
public final class ScenarioReplay {
    private record Group(String id, String kind, String target, DatasetReplay.Catalog catalog,
                         long timeout, long work, long memory, boolean fallback) {}

    private record Request(long amount, boolean cold, String phase, String expected) {}

    private static void verify(GraphPlan<String> plan, DatasetReplay.Catalog data) {
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
        for (var entry : plan.initialExact().entrySet())
            if (!data.external().contains(entry.getKey()) && entry.getValue().compareTo(
                    BigInteger.valueOf(data.stock().getOrDefault(entry.getKey(), 0L))) > 0)
                throw new AssertionError("Plan overdraws archived stock: " + entry.getKey());
        plan.executionDependencies();
        for (var mode : CraftingCostModel.Mode.values())
            if (CraftingCostModel.bytes(plan, mode, key -> key.contains("ae2:f") ? 8000 : 8).signum() < 0)
                throw new AssertionError("Negative storage charge");
    }

    private static GraphCompiler<String> compiler(DatasetReplay.Catalog data) {
        return new GraphCompiler<>(data.recipes(), data.producers());
    }

    private static boolean solve(Group group, GraphCompiler<String> compiler, Request request,
                                 BufferedWriter writer) throws Exception {
        var data = group.catalog();
        var budget = new PlanningBudget(group.timeout(), group.work(), group.memory(), () -> false, System::nanoTime);
        long started = System.nanoTime();
        String result = "UNKNOWN", error = "", fallbackJson = "";
        GraphPlan<String> plan = null;
        boolean feasible = false, verified = false;
        var policy = new CatalystPolicy(data.parallelism(), data.extraCopies());
        var work = new CatalystPlanningWork<String>(policy, budget,
                value -> new GraphPlanningWork<>(compiler, group.target(), request.amount(), data.stock(), data.external(),
                        Map.of(), data.preserve(), data.force(), budget).catalysts(value));
        try {
            try {
                while (!work.step()) {}
                plan = work.result();
            } catch (PlanningBudget.Exhausted exhausted) {
                plan = work.limited(exhausted);
            }
            result = plan.result().name();
            feasible = plan.feasible();
            if (feasible) {
                verify(plan, data);
                verified = true;
            }
        } catch (PlanningBudget.Exhausted exhausted) {
            result = exhausted.limit().name();
        } catch (Throwable failure) {
            result = "ERROR";
            error = failure.toString();
            failure.printStackTrace(System.err);
        } finally {
            work.close();
        }
        // The original probe records fallback separately; it never replaces the main result.
        if (group.fallback() && plan != null && !feasible && error.isEmpty() && plan.missingExact().isEmpty()
                && Set.of("UNKNOWN", "INFEASIBLE", "TIMEOUT", "SEARCH_LIMIT", "MEMORY_LIMIT", "GRAPH_LIMIT").contains(result)) {
            var quick = new PlanningBudget(250, 131_072, 16L << 20, () -> false, System::nanoTime);
            String fallbackResult;
            boolean fallbackVerified = false;
            try {
                var alternative = GraphFallback.plan(compiler, group.target(), request.amount(), data.stock(),
                        data.external(), Map.of(), data.preserve(), data.force(), quick);
                fallbackResult = alternative.result().name();
                if (alternative.feasible()) {
                    verify(alternative, data);
                    fallbackVerified = true;
                }
            } catch (PlanningBudget.Exhausted exhausted) {
                fallbackResult = exhausted.limit().name();
            } catch (Throwable failure) {
                fallbackResult = "ERROR";
                error = "Fallback: " + failure;
                failure.printStackTrace(System.err);
            }
            fallbackJson = ",\"fallback\":{\"result\":" + quote(fallbackResult) + ",\"verified\":" + fallbackVerified +
                    ",\"work\":" + quick.nodes() + "}";
        }
        if (request.expected().equals("SAT") && !feasible && error.isEmpty())
            error = "Original known_feasible_max assertion failed: " + result;
        String assessment = !error.isEmpty() ? "ERROR" : verified ? "VERIFIED_FEASIBLE" : "INCONCLUSIVE";
        writer.write("{\"group\":" + quote(group.id()) + ",\"kind\":" + quote(group.kind()) +
                ",\"target\":" + quote(group.target()) + ",\"amount\":" + request.amount() +
                ",\"phase\":" + quote(request.phase()) + ",\"cold\":" + request.cold() +
                ",\"expected\":" + quote(request.expected()) + ",\"result\":" + quote(result) +
                ",\"assessment\":" + quote(assessment) + ",\"feasible\":" + feasible + ",\"verified\":" + verified +
                ",\"catalog_recipes\":" + data.recipes().size() + ",\"stock_keys\":" + data.stock().size() +
                ",\"selected_recipes\":" + (plan == null ? 0 : plan.patternTimesExact().size()) +
                ",\"work\":" + budget.nodes() + ",\"peak_bytes\":" + budget.peakBytes() +
                ",\"elapsed_ms\":" + (System.nanoTime() - started) / 1_000_000.0 +
                ",\"error\":" + quote(error) + fallbackJson + "}");
        writer.newLine();
        writer.flush();
        System.out.println(group.id() + " " + request.phase() + " x" + request.amount() + " -> " + result + " / " + assessment);
        return error.isEmpty();
    }

    public static void main(String[] args) throws Exception {
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))));
             var writer = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            if (!text(input).equals("CGSE_SCENARIOS_V1")) throw new IllegalArgumentException("Unknown scenario transfer format");
            int errors = 0;
            for (int remaining = input.readInt(); remaining > 0; remaining--) {
                var group = new Group(text(input), text(input), text(input), catalog(input),
                        input.readLong(), input.readLong(), input.readLong(), input.readBoolean());
                var warm = compiler(group.catalog());
                for (int requests = input.readInt(); requests > 0; requests--) {
                    var request = new Request(input.readLong(), input.readBoolean(), text(input), text(input));
                    if (!solve(group, request.cold() ? compiler(group.catalog()) : warm, request, writer)) errors++;
                }
            }
            if (input.read() != -1) throw new IllegalArgumentException("Trailing scenario bytes");
            if (errors > 0) throw new AssertionError("Scenario replay failures: " + errors);
        }
    }
}
