package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bound-implication ordering, following SCIP's variable-bound fixing heuristic. */
final class CountBoundOrder {

    static int[] create(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high,
                        PlanningBudget budget) {
        int n = low.length * 2;
        if (n > 4096) return null;
        for (int i = 0; i < low.length; i++) if (high[i] == null || high[i].subtract(low[i]).compareTo(BigInteger.ONE) > 0) return null;
        long bytes = 2048L + 64L * n + 8L * n * ((n + 63L) / 64);
        if (!budget.tryReserve(bytes)) return null;
        long until = budget.threadWork() + Math.min(65536, budget.remainingWork() / 32);
        try {
            BitSet[] edges = new BitSet[n];
            for (int i = 0; i < n; i++) edges[i] = new BitSet(n);
            for (var row : rows) {
                if (budget.threadWork() >= until) break;
                BigInteger minimum = BigInteger.ZERO;
                List<Integer> literals = new ArrayList<>();
                List<BigInteger> costs = new ArrayList<>();
                for (var term : row.terms().entrySet()) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    int id = term.getKey();
                    BigInteger a = term.getValue(), endpoint = a.signum() > 0 ? low[id] : high[id];
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), endpoint.bitLength()));
                    minimum = minimum.add(a.multiply(endpoint));
                    if (!low[id].equals(high[id]) && a.signum() != 0) {
                        literals.add(2 * id + (a.signum() > 0 ? 1 : 0));
                        costs.add(a.abs());
                    }
                }
                BigInteger slack = row.upper().subtract(minimum);
                for (int i = 0; i < literals.size() && budget.threadWork() < until; i++) for (int j = i + 1; j < literals.size(); j++) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    if (costs.get(i).add(costs.get(j)).compareTo(slack) > 0) {
                        edges[literals.get(i)].set(literals.get(j) ^ 1);
                        edges[literals.get(j)].set(literals.get(i) ^ 1);
                    }
                }
            }
            BitSet visited = new BitSet(n);
            int[] next = new int[n], stack = new int[n], order = new int[n];
            int length = 0;
            for (int start = 0; start < n; start++) {
                if (visited.get(start)) continue;
                int depth = 1;
                stack[0] = start;
                visited.set(start);
                while (depth > 0) {
                    budget.operation(PlanningBudget.Operation.SCAN, 0);
                    int v = stack[depth - 1], child = edges[v].nextSetBit(next[v]);
                    if (child < 0) {
                        order[n - 1 - length++] = v;
                        depth--;
                    } else {
                        next[v] = child + 1;
                        if (!visited.get(child)) {
                            visited.set(child);
                            stack[depth++] = child;
                        }
                    }
                }
            }
            return order;
        } finally {
            budget.release(bytes);
        }
    }
}
