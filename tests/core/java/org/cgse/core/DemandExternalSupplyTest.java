// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Supply views must retain exact bills without pretending external ports are finite stock. */
public final class DemandExternalSupplyTest {
    private static int checks;
    public static void main(String[] args) {
        for (boolean reverse : List.of(false, true)) {
            var list = new ArrayList<>(List.of(r("open", Map.of("seed", 1L, "feed", 3L), Map.of("active", 1L)),
                    r("return", Map.of("active", 1L), Map.of("seed", 1L, "G", 2L))));
            if (reverse) Collections.reverse(list);
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>(); list.forEach(r -> recipes.put(r.id(), r));
            for (var amount : List.of(BigInteger.valueOf(16), BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(128)))) {
                var b = budget();
                var work = new DemandExpansion<>(recipes, Map.of("seed", BigInteger.ONE), Map.of("G", amount), b).externallySupplied(Set.of("feed"));
                try {
                    while (!work.step()) {}
                    check(work.result() != null, "External feed incorrectly exhausted");
                    try (var fold = new SummaryComputation<>(work.result(), recipes, b)) {
                        while (!fold.step()) {}
                        var s = fold.result();
                        check(s.required("seed").equals(BigInteger.ONE), "Invented/expanded finite seed");
                        check(s.delta("seed").signum() == 0, "Lost returned seed");
                        check(s.delta("G").compareTo(amount) >= 0, "Incomplete target");
                        check(s.required("feed").equals(amount.add(BigInteger.ONE).divide(BigInteger.TWO).multiply(BigInteger.valueOf(3))), "External requirement was truncated");
                    }
                } finally { work.close(); }
                check(b.reservedBytes() == 0, "Supply continuation leaked");
            }
            for (var stock : List.of(Map.of("seed", BigInteger.ONE), Map.<String, BigInteger>of())) {
                var b = budget();
                var work = new DemandExpansion<>(recipes, stock, Map.of("G", BigInteger.valueOf(16)), b);
                if (stock.isEmpty()) work.externallySupplied(Set.of("feed"));
                try { while (!work.step()) {} check(work.result() == null, "Finite missing feed/seed became external"); }
                finally { work.close(); }
                check(b.reservedBytes() == 0, "Failed supply continuation leaked");
            }
            var b = budget();
            var compiler = new GraphCompiler<>(list);
            var search = new AllocationSearch<>(compiler, "G", 1_000_000, Map.of("seed", 1L), Set.of("feed"),
                    Map.of("seed", 1L), true, true, Set.of(), b, System.nanoTime());
            try {
                while (!search.step()) {}
                var p = search.result();
                check(p != null && p.feasible(), "Native allocation did not pass the supply boundary");
                check(p.initialExact().get("feed").equals(BigInteger.valueOf(1_500_000)), "Lost physical external bill");
                check(p.missingExact().isEmpty(), "External stock reported missing");
            } finally { search.discard(); }
            check(b.reservedBytes() == 0, "Native allocation leaked");
        }
        System.out.println("DemandExternalSupplyTest PASS: checks=" + checks);
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 250_000, 16L << 20, () -> false, System::nanoTime); }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
