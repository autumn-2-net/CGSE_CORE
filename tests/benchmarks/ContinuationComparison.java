// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Identical specialist-only budget campaign, compilable before the continuation API existed. */
public final class ContinuationComparison {
    private record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {}

    public static void main(String[] args) throws Exception {
        long quantum = Arrays.asList(args).contains("--whole") ? 2_000_000 : 2048;
        System.out.println("engine,seed,order,witness,pauses,work,peak_bytes");
        for (String engine : List.of("mitm", "separator", "diagram", "domain"))
            for (int seed = 0; seed < 32; seed++) for (int order = 0; order < 2; order++) {
                Model model = model(engine, seed, order);
                var budget = new PlanningBudget(0, 2_000_000, 64L << 20, () -> false, System::nanoTime);
                Object search = switch (engine) {
                    case "mitm" -> new CountMeetInMiddle(model.rows, model.low, model.high, budget, quantum);
                    case "separator" -> new CountSeparator(model.rows, model.low, model.high, budget, quantum);
                    case "diagram" -> new CountDecisionDiagram(model.rows, model.low, model.high, budget, quantum);
                    default -> new CountDomainSearch(model.rows, model.low, model.high, budget, quantum);
                };
                int pauses = 0;
                BigInteger[] values = null;
                try {
                    boolean retained;
                    try { call(search, "retained"); retained = true; }
                    catch (NoSuchMethodException oldRevision) { retained = false; }
                    for (;;) {
                        while (!(boolean) call(search, "step")) {}
                        values = (BigInteger[]) call(search, "counts");
                        if (values != null) break;
                        if ((boolean) call(search, "infeasible")) throw new AssertionError("Planted model classified infeasible");
                        if (!retained || !(boolean) call(search, "paused")) break;
                        search.getClass().getDeclaredMethod("resume", long.class).invoke(search, quantum);
                        pauses++;
                    }
                } catch (InvocationTargetException failure) {
                    if (!(failure.getCause() instanceof PlanningBudget.Exhausted)) throw failure;
                } finally { ((AutoCloseable) search).close(); }
                if (values != null && !valid(model, values)) throw new AssertionError("Invalid original-coordinate witness");
                if (budget.reservedBytes() != 0) throw new AssertionError("Leaked continuation memory");
                System.out.println(engine + "," + seed + "," + order + "," + (values != null) + "," + pauses + "," +
                        budget.searchWork() + "," + budget.peakBytes());
            }
    }

    private static Object call(Object search, String name) throws Exception {
        return search.getClass().getDeclaredMethod(name).invoke(search);
    }

    private static Model model(String engine, int seed, int order) {
        int n = switch (engine) { case "mitm" -> 20; case "separator" -> 16 + seed; case "diagram" -> 8; default -> 10; };
        var low = new BigInteger[n];
        var high = low.clone();
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.valueOf(engine.equals("mitm") ? 1 : engine.equals("separator") ? 31 : 3));
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        Random random = new Random(48179 + seed);
        if (engine.equals("separator")) {
            for (int i = 1; i < n; i++) {
                rows.add(new ExactLinearProgram.Constraint(Map.of(i - 1, BigInteger.valueOf(3), i, BigInteger.valueOf(5)), BigInteger.valueOf(71)));
                rows.add(new ExactLinearProgram.Constraint(Map.of(i - 1, BigInteger.valueOf(-3), i, BigInteger.valueOf(-5)), BigInteger.valueOf(-63)));
            }
        } else {
            var witness = new BigInteger[n];
            for (int i = 0; i < n; i++) witness[i] = BigInteger.valueOf(random.nextInt(high[i].intValueExact() + 1));
            for (int r = 0; r < 2; r++) {
                var terms = new LinkedHashMap<Integer, BigInteger>();
                var reverse = new LinkedHashMap<Integer, BigInteger>();
                BigInteger bound = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    var a = BigInteger.valueOf(1 + random.nextInt(20));
                    terms.put(i, a);
                    reverse.put(i, a.negate());
                    bound = bound.add(a.multiply(witness[i]));
                }
                rows.add(new ExactLinearProgram.Constraint(terms, bound));
                rows.add(new ExactLinearProgram.Constraint(reverse, bound.negate()));
            }
        }
        Collections.shuffle(rows, new Random(721L * seed + order));
        return new Model(rows, low, high);
    }

    private static boolean valid(Model model, BigInteger[] values) {
        for (int i = 0; i < values.length; i++)
            if (values[i].compareTo(model.low[i]) < 0 || values[i].compareTo(model.high[i]) > 0) return false;
        for (var row : model.rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }
}
