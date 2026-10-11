// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.List;

/** Dependency-free exact witness check used by migrated historical probes. */
final class HistoricalCountAssertions {
    static void verify(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, BigInteger[] point) {
        if (point.length != lower.length || point.length != upper.length) throw new AssertionError("Dimension mismatch");
        for (int i = 0; i < point.length; i++) {
            if (point[i].compareTo(lower[i]) < 0 || upper[i] != null && point[i].compareTo(upper[i]) > 0)
                throw new AssertionError("Count outside original domain: " + i);
        }
        for (var row : rows) {
            BigInteger activity = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) activity = activity.add(term.getValue().multiply(point[term.getKey()]));
            if (activity.compareTo(row.upper()) > 0) throw new AssertionError("Violated original row");
        }
    }
}
