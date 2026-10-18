// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the original physical/source oracle with indivisible multi-tool slots. */
public final class CapturedStateWholeMatchingTest {
    public static void main(String[] args) {
        int cases = 0;
        for (int mode : new int[]{0, 1}) for (long q : new long[]{2, 3, 16})
            for (int n : new int[]{1, 2, 7, 31}) for (int order = 0; order < 12; order++) {
                var f = CapturedStateMatchingCostsTest.fixture(n, mode, q, false, order, false);
                CapturedStateMatchingCostsTest.solve(f, true, Map.of(), false); cases++;
            }
        for (long q : new long[]{2, 3}) for (int n : new int[]{2, 7, 31}) for (int order = 0; order < 4; order++) {
            var f = CapturedStateMatchingCostsTest.fixture(n, 2, q, false, order, false);
            CapturedStateMatchingCostsTest.solve(f, true, Map.of(), false); cases++;
        }
        for (int mode : new int[]{0, 1, 2}) {
            var f = CapturedStateMatchingCostsTest.fixture(2, mode, 3, false, 0, false);
            var plan = CapturedStateMatchingCostsTest.solve(f, false, Map.of(), false);
            CapturedStateMatchingCostsTest.runtime(plan, f.amount()); cases++;
        }
        var wide = CapturedStateMatchingCostsTest.fixture(1, 0, Long.MAX_VALUE / 4, false, 0, false);
        CapturedStateMatchingCostsTest.solve(wide, false, Map.of(), false); cases++;
        var made = CapturedStateMatchingCostsTest.fixture(2, 0, 3, false, 0, true);
        CapturedStateMatchingCostsTest.solve(made, true, Map.of("a", 3L), false); cases++;
        var stock = new LinkedHashMap<>(made.stock()); stock.put("product", made.amount());
        CapturedStateMatchingCostsTest.solve(new CapturedStateMatchingCostsTest.Fixture(made.entries(), stock, made.n(), made.mode(), made.cohort(), made.amount(), true), true, Map.of("a", 3L), true); cases++;
        System.out.println("CapturedStateWholeMatchingTest: cases=" + cases + "; source/physical/runtime checks passed");
    }
}
