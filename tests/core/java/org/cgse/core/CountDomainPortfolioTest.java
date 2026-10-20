// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Admission, lifetime and private proof boundaries of the late domain arm. */
public final class CountDomainPortfolioTest {
    public static void main(String[] args) throws Exception {
        var lo = new BigInteger[130]; var hi = new BigInteger[130];
        Arrays.fill(lo, BigInteger.ZERO); Arrays.fill(hi, BigInteger.ZERO);
        hi[0] = BigInteger.valueOf(3); hi[1] = hi[2] = BigInteger.ONE;
        var rows = CountEqualitySupportTest.pair(Map.of(0, BigInteger.ONE, 1, BigInteger.TWO, 2, BigInteger.valueOf(3)), BigInteger.valueOf(3));
        Method add = CountViewSearch.class.getDeclaredMethod("addEqualitySupport", boolean.class); add.setAccessible(true);
        Field work = field(CountViewSearch.class, "work"), searches = field(CountViewSearch.class, "searches");
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var models = CountModelViews.create(rows, lo, hi, budget);
             var portfolio = new CountViewSearch(models, budget)) {
            models.compileLight();
            work.setLong(portfolio, 4_194_304);
            check(!(boolean) add.invoke(portfolio, true), "Domains displaced early hull frontier");
            check((boolean) add.invoke(portfolio, false), "Old hull frontier missing");
            Object hull = ((List<?>) searches.get(portfolio)).get(0);
            work.setLong(portfolio, 16_777_216);
            portfolio.mode(CountPortfolioPolicy.Mode.PROOF);
            check(!(boolean) add.invoke(portfolio, true), "Unexported domain explanation admitted to proof task");
            portfolio.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            var journal = new CountProof.Journal(1L << 20); budget.proofJournal(journal);
            check(!(boolean) add.invoke(portfolio, true), "Unexported domain explanation admitted to journal");
            budget.proofJournal(null);
            check((boolean) add.invoke(portfolio, true), "Late domain frontier missing");
            check(!(boolean) add.invoke(portfolio, true), "Repeated admission renewed lifetime");
            var arms = (List<?>) searches.get(portfolio);
            check(arms.size() == 2 && arms.get(0) == hull, "Old frontier replaced");
            Object arm = arms.get(1);
            Field specialist = field(arm.getClass(), "specialist");
            var candidate = (CountSupportedSearch) specialist.get(arm);
            while (true) {
                if (candidate.paused()) candidate.resume(37);
                if (candidate.step() && !candidate.paused()) break;
            }
            BigInteger[] witness = candidate.counts();
            check(witness != null && witness[0].add(witness[1].multiply(BigInteger.TWO))
                    .add(witness[2].multiply(BigInteger.valueOf(3))).equals(BigInteger.valueOf(3)), "Invalid original candidate");
            Method publish = arm.getClass().getDeclaredMethod("publish", CountModelViews.class); publish.setAccessible(true);
            publish.invoke(arm, models);
            Method infeasible = arm.getClass().getDeclaredMethod("infeasible"); infeasible.setAccessible(true);
            check(!(boolean) infeasible.invoke(arm), "Candidate exposed negative conclusion");
            var domains = models.domains(models.available().get(0));
            check(Arrays.equals(domains.lower(), lo) && Arrays.equals(domains.upper(), hi) &&
                    domains.version() == 0 && models.conflictVersion() == 0, "Private holes escaped to model facts");
        }
        check(budget.reservedBytes() == 0, "Portfolio ownership leaked");
        System.out.println("Domain portfolio: deferred admission, retained hull frontier, proof/journal isolation, positive witness and cleanup passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
