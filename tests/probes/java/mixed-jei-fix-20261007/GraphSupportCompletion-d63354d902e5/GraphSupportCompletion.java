package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Elastic boundary relaxation guides a restricted, positive-only recipe neighborhood. */
final class GraphSupportCompletion<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private long memory, started;

    GraphSupportCompletion(GraphCompiler<K> compiler, Map<K, Long> stock, Set<K> external,
                           Set<String> excluded, PlanningBudget budget) {
        this.compiler = compiler;
        this.stock = stock;
        this.external = external;
        this.excluded = excluded;
        this.budget = budget;
    }

    List<GraphRecipe<K>> complete(GraphCompiler.Compiled<K> graph, GraphPlan<K> candidate, K target, long amount) {
        started = budget.threadSearchWork();
        if (!reserve(1024)) return List.of();
        addSources(target, amount, 8);
        for (int round = 0; round < 32 && recipes.size() < 192 && remaining() >= 4096; round++) {
            var local = new GraphCompiler<>(List.copyOf(recipes.values()));
            try (var model = RecipeCountModel.create(local, target, amount, stock, Map.of(), external, Set.of(), true, budget)) {
                if (model == null || model.recipes.size() + model.keys.size() > 512) break;
                long bytes = 1024L + 192L * (model.constraints.size() + model.recipes.size());
                for (var row : model.constraints) bytes += 96L * row.terms().size();
                if (!budget.tryReserve(bytes)) break;
                try {
                    var rows = new ArrayList<ExactLinearProgram.Constraint>();
                    var boundary = new ArrayList<K>();
                    var scales = new ArrayList<BigInteger>();
                    for (int i = 0; i < model.constraints.size(); i++) {
                        if (!scan()) return List.of();
                        var row = model.constraints.get(i);
                        K key = i < model.rowKeys.size() ? model.rowKeys.get(i) : null;
                        boolean expandable = false;
                        if (key != null && !key.equals(target)) for (var source : compiler.producers(key)) {
                            if (!scan()) return List.of();
                            if (!excluded.contains(source.id()) && !recipes.containsKey(source.id())) { expandable = true; break; }
                        }
                        if (!expandable) { rows.add(row); continue; }
                        BigInteger scale = row.upper().abs().max(BigInteger.ONE);
                        for (var value : row.terms().values()) { if (!scan()) return List.of(); scale = scale.max(value.abs()); }
                        var terms = new LinkedHashMap<>(row.terms());
                        terms.put(model.recipes.size() + boundary.size(), scale.negate());
                        boundary.add(key);
                        scales.add(scale);
                        rows.add(new ExactLinearProgram.Constraint(terms, row.upper()));
                    }
                    if (boundary.isEmpty()) return List.copyOf(recipes.values());
                    var objective = new BigInteger[model.recipes.size() + boundary.size()];
                    Arrays.fill(objective, 0, model.recipes.size(), BigInteger.ZERO);
                    Arrays.fill(objective, model.recipes.size(), objective.length, BigInteger.ONE.negate());
                    var relaxation = CountNumericRelaxation.solve(objective.length, rows, objective, budget, remaining());
                    budget.note("support_completion", "round=" + round + "; recipes=" + recipes.size() + "; boundary=" + boundary.size() +
                            "; numeric=" + (relaxation == null ? "unresolved" : relaxation.phaseOneInfeasible() ? "infeasible_hint" : "point") + "; work=" + (budget.threadSearchWork() - started));
                    if (relaxation == null || relaxation.phaseOneInfeasible()) break;
                    var point = relaxation.point();
                    boolean expanded = false;
                    for (int i = 0; i < boundary.size(); i++) {
                        if (!scan()) return List.of();
                        double artificial = point[model.recipes.size() + i];
                        double demand = artificial * scales.get(i).doubleValue();
                        if (!(demand > 0.25)) continue;
                        long needed = !Double.isFinite(demand) || demand >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1, (long) Math.ceil(demand));
                        expanded |= addSources(boundary.get(i), needed, 3);
                    }
                    if (!expanded) return List.copyOf(recipes.values());
                } finally { budget.release(bytes); }
            }
        }
        return List.copyOf(recipes.values());
    }

    private boolean addSources(K key, long needed, int count) {
        var choices = new ArrayList<GraphRecipe<K>>();
        var costs = new ArrayList<Double>();
        for (var recipe : compiler.producers(key)) {
            if (!scan()) break;
            if (excluded.contains(recipe.id()) || recipes.containsKey(recipe.id())) continue;
            double output = Math.max(1, recipe.executionOutputs().getOrDefault(key, 0L) - recipe.inputs().getOrDefault(key, 0L));
            double batches = Math.max(1, Math.ceil(needed / output)), score = 0;
            for (var input : recipe.inputs().entrySet()) {
                if (!scan()) return false;
                if (external.contains(input.getKey())) continue;
                long available = stock.getOrDefault(input.getKey(), 0L);
                double required = batches * input.getValue();
                if (required > available) {
                    score += 1 + Math.log1p((required - available) / Math.max(1, available));
                    if (compiler.producers(input.getKey()).isEmpty()) score += 1024;
                }
            }
            int at = 0;
            while (at < costs.size() && costs.get(at) <= score) at++;
            if (at < count) {
                choices.add(at, recipe); costs.add(at, score);
                if (choices.size() > count) { choices.remove(count); costs.remove(count); }
            }
        }
        boolean added = false;
        for (var recipe : choices) {
            if (recipes.size() >= 192 || !reserve(256L + 96L * (recipe.inputs().size() + (long) recipe.outputs().size()))) break;
            recipes.put(recipe.id(), recipe); added = true;
        }
        return added;
    }

    private long remaining() { return Math.max(0, 1048576 - (budget.threadSearchWork() - started)); }

    private boolean scan() {
        if (remaining() == 0) return false;
        budget.operation(PlanningBudget.Operation.SCAN, 1);
        return true;
    }

    private boolean reserve(long bytes) {
        if (bytes > (2L << 20) - memory || !budget.tryReserve(bytes)) return false;
        memory += bytes;
        return true;
    }

    @Override
    public void close() { budget.release(memory); memory = 0; }
}
