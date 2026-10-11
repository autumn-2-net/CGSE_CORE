// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent finite-domain oracle for the corpus's integer -> recipe -> count bridge. */
public final class CountEmbeddingEquivalenceTest {
    private static int points, feasible, compiledPoints, views;

    public static void main(String[] args) {
        for (int seed = 0; seed < 32; seed++) {
            var random = new Random(81117L + seed);
            int n = 3 + seed % 3;
            var lower = new BigInteger[n];
            var upper = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                lower[i] = switch (seed % 4) {
                    case 0 -> BigInteger.valueOf(-2);
                    case 1 -> BigInteger.ONE.shiftLeft(80).negate();
                    case 2 -> BigInteger.ONE.shiftLeft(80);
                    default -> BigInteger.ZERO;
                };
                upper[i] = lower[i].add(BigInteger.TWO);
            }
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (int r = 0; r < 2 + seed % 5; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                BigInteger bound = BigInteger.valueOf(random.nextInt(17) - 8);
                for (int i = 0; i < n; i++) {
                    int a = random.nextInt(7) - 3;
                    if (a != 0) {
                        terms.put(i, BigInteger.valueOf(a));
                        bound = bound.add(lower[i].multiply(BigInteger.valueOf(a)));
                    }
                }
                rows.add(new ExactLinearProgram.Constraint(terms, bound));
            }
            // Also exercise tautologies, contradictions and wholly unused coordinates.
            if (seed % 8 == 0) rows.add(new ExactLinearProgram.Constraint(Map.of(), BigInteger.ZERO));
            if (seed % 8 == 1) rows.add(new ExactLinearProgram.Constraint(Map.of(), BigInteger.ONE.negate()));
            for (int order = 0; order < 3; order++) {
                Collections.shuffle(rows, random);
                audit(rows, lower, upper, seed * 3L + order);
            }
        }
        check(feasible > 0 && compiledPoints >= feasible && views > 0, "Missing positive coverage");
        System.out.println("Count embedding: " + points + " domain points, " + feasible +
                " original witnesses, " + compiledPoints + " compiled witnesses, " + views +
                " view roundtrips; signed/wide offsets, absent coordinates and recipe orders passed");
    }

    private static void audit(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                              BigInteger[] upper, long seed) {
        var embedded = new CountModelReplay.Embedding(rows, lower, upper);
        Collections.shuffle(embedded.recipes, new Random(seed));
        var budget = new PlanningBudget(0, 4_000_000, 32L << 20, () -> false, System::nanoTime);
        try (var model = RecipeCountModel.create(new GraphCompiler<>(embedded.recipes), "target", 1,
                embedded.stock, Map.of(), Set.of(), Set.of(), true, budget)) {
            check(model != null, "Unexpected count-model admission refusal");
            var domains = new BigInteger[model.recipes.size()];
            var caps = new BigInteger[domains.length];
            Arrays.fill(domains, BigInteger.ZERO);
            Arrays.fill(caps, BigInteger.TWO);
            for (int i = 0; i < domains.length; i++) if (model.recipes.get(i).id().equals("finish"))
                domains[i] = caps[i] = BigInteger.ONE;
            try (var reduction = new CountReduction(model.constraints, domains, caps, budget);
                 var representations = CountModelViews.create(model.constraints, domains, caps, budget)) {
                check(representations != null, "Unexpected view admission refusal");
                reduction.retainStrideView();
                while (!reduction.step()) {}
                representations.compileLight();
                representations.addReduced(reduction);
                int combinations = 1;
                for (int i = 0; i < lower.length; i++) combinations *= 3;
                for (int encoded = 0; encoded < combinations; encoded++) {
                    var original = lower.clone();
                    for (int i = 0, remaining = encoded; i < original.length; i++, remaining /= 3)
                        original[i] = original[i].add(BigInteger.valueOf(remaining % 3));
                    points++;
                    boolean sourceValid = valid(rows, original);
                    var counts = new BigInteger[model.recipes.size()];
                    var restored = lower.clone();
                    for (int i = 0; i < counts.length; i++) {
                        String id = model.recipes.get(i).id();
                        int variable = id.equals("finish") ? -1 : Integer.parseInt(id.substring(3));
                        counts[i] = variable < 0 ? BigInteger.ONE : original[variable].subtract(lower[variable]);
                        if (variable >= 0) restored[variable] = original[variable];
                    }
                    boolean targetValid = valid(model.constraints, counts);
                    if (sourceValid) {
                        feasible++;
                        check(targetValid, "A feasible original assignment was lost by the count closure");
                    }
                    if (!targetValid) continue;
                    compiledPoints++;
                    // Omitted coordinates are restored at their lower endpoints. Do not
                    // incorrectly demand pointwise equivalence on unused input columns.
                    check(valid(rows, restored), "A count assignment violates its original integer model");
                    execute(model.recipes, counts, embedded.stock);
                    for (var view : representations.available()) {
                        BigInteger[] point = view.substitution() == null ? counts :
                                project(view.substitution(), counts, view.lower().length);
                        for (int i = 0; i < point.length; i++)
                            check(point[i].compareTo(view.lower()[i]) >= 0 &&
                                    (view.upper()[i] == null || point[i].compareTo(view.upper()[i]) <= 0),
                                    "Compiled domain lost witness: " + view.name());
                        check(valid(view.rows(), point), "Compiled rows lost witness: " + view.name());
                        check(Arrays.equals(counts, view.restore(point)), "Coordinate recovery lost witness");
                        views++;
                    }
                }
            }
        }
        check(budget.reservedBytes() == 0, "Count bridge retained budget reservations");
    }

    private static BigInteger[] project(CountMapping mapping, BigInteger[] original, int size) {
        var result = new BigInteger[size];
        for (int i = 0; i < original.length; i++) {
            var expression = mapping.coordinates().get(i);
            if (expression.terms().isEmpty()) {
                check(original[i].equals(expression.constant()), "Eliminated constant disagrees");
                continue;
            }
            check(expression.terms().size() == 1, "Unexpected non-singleton reduction");
            var term = expression.terms().entrySet().iterator().next();
            var qr = original[i].subtract(expression.constant()).divideAndRemainder(term.getValue());
            check(qr[1].signum() == 0, "Nonintegral reduced coordinate");
            check(result[term.getKey()] == null || result[term.getKey()].equals(qr[0]), "Inconsistent inverse");
            result[term.getKey()] = qr[0];
        }
        check(Arrays.stream(result).noneMatch(Objects::isNull), "Missing reduced coordinate");
        return result;
    }

    private static void execute(List<GraphRecipe<String>> recipes, BigInteger[] counts, Map<String, Long> stock) {
        var available = new HashMap<String, BigInteger>();
        stock.forEach((key, value) -> available.put(key, BigInteger.valueOf(value)));
        // Independent primitive execution: all variable batches, then one finish.
        for (int phase = 0; phase < 2; phase++) for (int i = 0; i < recipes.size(); i++) {
            var recipe = recipes.get(i);
            if (recipe.id().equals("finish") != (phase == 1)) continue;
            for (var input : recipe.inputs().entrySet()) {
                BigInteger need = BigInteger.valueOf(input.getValue()).multiply(counts[i]);
                check(available.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(need) >= 0,
                        "Unfunded primitive prefix: " + recipe.id());
                available.merge(input.getKey(), need.negate(), BigInteger::add);
            }
            for (var output : recipe.outputs().entrySet())
                available.merge(output.getKey(), BigInteger.valueOf(output.getValue()).multiply(counts[i]), BigInteger::add);
        }
        check(available.getOrDefault("target", BigInteger.ZERO).equals(BigInteger.ONE), "Missing target");
    }

    private static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] point) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(point[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
