package org.cgse.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Full historical graph corpus, with independent primitive-prefix witness checks. */
public final class CorpusRegression {
    public static void main(String[] args) throws Exception {
        int solved = 0, unresolved = 0, failed = 0;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            int size = input.readInt();
            for (int index = 0; index < size; index++) {
                String name = GraphFixtureRegression.string(input), target = GraphFixtureRegression.string(input);
                long amount = input.readLong();
                String expected = GraphFixtureRegression.string(input);
                Map<String, Long> stock = GraphFixtureRegression.amounts(input);
                var external = new LinkedHashSet<String>();
                for (int left = input.readInt(); left > 0; left--) external.add(GraphFixtureRegression.string(input));
                boolean preserve = input.readBoolean(), force = input.readBoolean();
                var primitives = new LinkedHashMap<String, GraphRecipe<String>>();
                for (int left = input.readInt(); left > 0; left--) {
                    String id = GraphFixtureRegression.string(input);
                    String binding = GraphFixtureRegression.string(input);
                    var slots = new ArrayList<GraphRecipe.Slot<String>>();
                    for (int remaining = input.readInt(); remaining > 0; remaining--)
                        slots.add(new GraphRecipe.Slot<>(GraphFixtureRegression.string(input), input.readLong(), input.readInt(), input.readBoolean(), input.readBoolean()));
                    var outputs = GraphFixtureRegression.amounts(input);
                    primitives.put(id, new GraphRecipe<>(id, binding, slots, outputs));
                }
                var budget = new PlanningBudget(Long.parseLong(args[1]), Long.parseLong(args[2]), 256L << 20, () -> false, System::nanoTime);
                try {
                    GraphPlan<String> plan;
                    var work = new GraphPlanner<>(new GraphCompiler<>(new ArrayList<>(primitives.values())))
                            .begin(target, amount, stock, external, preserve, force, budget);
                    try {
                        while (!work.step()) {}
                        plan = work.result();
                    } finally { work.close(); }
                    boolean conclusion = switch (plan.result()) {
                        case FEASIBLE, FEASIBLE_NOT_PROVEN_OPTIMAL, MISSING_INPUT, MISSING_SEED, INFEASIBLE -> true;
                        default -> false;
                    };
                    if (!conclusion) {
                        unresolved++;
                        System.out.println(name + "\tUNRESOLVED\t" + plan.result() + "\twork=" + budget.nodes());
                        continue;
                    }
                    if (expected.equals("CONFLICT")) throw new AssertionError("Conflicting historical oracle labels");
                    if (expected.equals("SAT") && !plan.feasible() || expected.equals("UNSAT") && plan.feasible())
                        throw new AssertionError("Expected " + expected + ", obtained " + plan.result());
                    if (plan.feasible() || !plan.missingExact().isEmpty()) {
                        var summary = GraphFixtureRegression.summary(plan.steps(), primitives, new IdentityHashMap<>());
                        var funded = new HashMap<String, BigInteger>();
                        stock.forEach((key, value) -> funded.put(key, BigInteger.valueOf(value)));
                        plan.missingExact().forEach((key, value) -> funded.merge(key, value, BigInteger::add));
                        for (var entry : summary.need().entrySet()) {
                            if (!external.contains(entry.getKey()) && funded.getOrDefault(entry.getKey(), BigInteger.ZERO).compareTo(entry.getValue()) < 0)
                                throw new AssertionError("Unfunded primitive prefix " + entry);
                            if (plan.initialExact().getOrDefault(entry.getKey(), BigInteger.ZERO).compareTo(entry.getValue()) < 0)
                                throw new AssertionError("Reservation below primitive prefix " + entry);
                        }
                        if (plan.feasible() && force && !external.contains(target) && summary.delta().getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) < 0)
                            throw new AssertionError("Order reused existing target inventory");
                        if (!external.contains(target) && funded.getOrDefault(target, BigInteger.ZERO)
                                .add(summary.delta().getOrDefault(target, BigInteger.ZERO)).compareTo(BigInteger.valueOf(amount)) < 0)
                            throw new AssertionError("Funded preview does not deliver the requested target");
                        for (var seed : plan.seeds().entrySet())
                            if (!external.contains(seed.getKey()) && funded.getOrDefault(seed.getKey(), BigInteger.ZERO)
                                    .add(summary.delta().getOrDefault(seed.getKey(), BigInteger.ZERO)).compareTo(BigInteger.valueOf(seed.getValue())) < 0)
                                throw new AssertionError("Required seed not restored");
                    }
                    solved++;
                    System.out.println(name + "\tPASS\t" + plan.result() + "\twork=" + budget.nodes());
                } catch (PlanningBudget.Exhausted limit) {
                    unresolved++;
                    System.out.println(name + "\tUNRESOLVED\t" + limit);
                } catch (Throwable failure) {
                    failed++;
                    System.out.println(name + "\tFAIL\t" + failure);
                    failure.printStackTrace(System.err);
                }
            }
            if (input.read() != -1) throw new AssertionError("Trailing corpus bytes");
        }
        System.out.println("SUMMARY solved=" + solved + " unresolved=" + unresolved + " failed=" + failed);
        if (failed != 0) System.exit(1);
        if (unresolved != 0) System.exit(2);
    }
}
