// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** FinalPolicyParity's 300,000 observations, checked by invariants instead of a retired policy copy. */
public final class HistoricalPortfolioTest {
    public static void main(String[] args) {
        for (int seed = 0; seed < 100; seed++) {
            var a = new CountPortfolioPolicy(); var b = new CountPortfolioPolicy();
            var x = new ArrayList<CountPortfolioPolicy.Arm>(); var y = new ArrayList<CountPortfolioPolicy.Arm>();
            var random = new Random(seed); int n = 2 + random.nextInt(30);
            for (int i = 0; i < n; i++) { long cost = random.nextInt(100000); x.add(a.add(cost)); y.add(b.add(cost)); }
            for (int t = 0; t < 3000; t++) {
                var mode = CountPortfolioPolicy.Mode.values()[(t / 1000) % 3]; a.mode(mode); b.mode(mode);
                int disabled = t / 17 % n;
                for (int i = 0; i < n; i++) { x.get(i).eligible = i != disabled; y.get(i).eligible = i != disabled; }
                var p = a.select(); var q = b.select();
                if (p == null || !p.eligible || p.retired || x.indexOf(p) != y.indexOf(q)) throw new AssertionError("Invalid/scaled selection");
                long remaining = t % 23 == 0 ? 1 : t % 13 == 0 ? Long.MAX_VALUE : 100000;
                long amount = a.quantum(p, remaining);
                if (amount <= 0 || amount > remaining || amount > CountPortfolioPolicy.MAX_QUANTUM || amount != b.quantum(q, remaining))
                    throw new AssertionError("Quantum ignored budget or progress units");
                a.selected(p); b.selected(q);
                long spent = amount + random.nextInt(10000), gain = random.nextInt(5) == 0 ? 0 : random.nextInt(10000);
                a.feedback(p, spent, gain); b.feedback(q, spent, gain * 7);
                if (!Double.isFinite(p.reward) || p.work < 0 || p.waiting < 0) throw new AssertionError("Invalid feedback state");
            }
            for (var arm : x) arm.retired = true;
            if (a.select() != null) throw new AssertionError("Retired portfolio reopened");
        }
        System.out.println("Historical scheduling: 300000 scaled-feedback decisions, three goals, admission and budget boundaries passed");
    }
}
