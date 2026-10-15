// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Exact short-circuit queries over packing faces (at most 35 rows). Charge
 * every visited coordinate together, before the caller publishes a hint,
 * witness or subtree decision. This keeps the same work and query order
 * without an atomic budget update for every coordinate. */
final class CountPackingQuery {
    private CountPackingQuery() {}

    static int reject(long[] values, int base, long[] query, int first, PlanningBudget budget) {
        int checks = 1, rejected = values[base + first] > query[first] ? first : -1;
        if (rejected < 0) for (int r = query.length - 1; r >= 0; r--) {
            if (r == first) continue;
            checks++;
            if (values[base + r] > query[r]) { rejected = r; break; }
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, checks);
        return rejected;
    }

    static int reject(CountPackingValues values, int base, long[] query, int first, PlanningBudget budget) {
        int checks = 1, rejected = values.get(base + first) > query[first] ? first : -1;
        if (rejected < 0) for (int r = query.length - 1; r >= 0; r--) {
            if (r == first) continue;
            checks++;
            if (values.get(base + r) > query[r]) { rejected = r; break; }
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, checks);
        return rejected;
    }
}
