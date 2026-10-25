// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Late candidate admission must preserve proof scope and captured completion budgets. */
public final class CountMessagePortfolioTest {
    public static void main(String[] args) throws Exception {
        BigInteger[] low = new BigInteger[130], high = new BigInteger[130];
        Arrays.fill(low, BigInteger.ZERO); Arrays.fill(high, BigInteger.ZERO);
        high[0] = BigInteger.valueOf(3); high[1] = high[2] = BigInteger.ONE;
        var rows = CountEqualitySupportTest.pair(Map.of(0, BigInteger.ONE, 1, BigInteger.TWO,
                2, BigInteger.valueOf(3)), BigInteger.valueOf(3));
        var budget = new PlanningBudget(0, 60_000_000, 64L << 20, () -> false, System::nanoTime);
        Method add = CountViewSearch.class.getDeclaredMethod("addEqualityMessage"); add.setAccessible(true);
        Field searches = field(CountViewSearch.class, "searches");
        try (var models = CountModelViews.create(rows, low, high, budget);
             var portfolio = new CountViewSearch(models, budget)) {
            models.compileLight();
            check(!(boolean) add.invoke(portfolio), "Message search displaced early exact paths");
            budget.charge(16_777_216);
            portfolio.mode(CountPortfolioPolicy.Mode.PROOF);
            check(!(boolean) add.invoke(portfolio), "Message search entered proof-only task");
            portfolio.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            budget.proofJournal(new CountProof.Journal(1L << 20));
            check(!(boolean) add.invoke(portfolio), "Message search entered proof journal");
            budget.proofJournal(null);
            check((boolean) add.invoke(portfolio), "Late equality candidate was not admitted");
            check(!(boolean) add.invoke(portfolio), "Repeated admission renewed candidate");
            Object arm = ((List<?>) searches.get(portfolio)).get(0);
            Field limit = field(arm.getClass(), "continuationLimit"), work = field(arm.getClass(), "work");
            long captured = limit.getLong(arm), before = budget.nodes();
            check(captured > 0 && captured <= 33_554_432, "Unbounded completion opportunity");
            check(portfolio.messageCompletionQuantum() > 0 && budget.nodes() == before,
                    "Observation spent work or failed to retain opportunity");
            portfolio.mode(CountPortfolioPolicy.Mode.PROOF);
            check(portfolio.messageCompletionQuantum() == 0, "Pending preference bypassed proof mode");
            portfolio.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            work.setLong(arm, captured);
            check(portfolio.messageCompletionQuantum() == 0 && limit.getLong(arm) == captured,
                    "Completed opportunity renewed itself");
            var candidate = (CountMessageSearch) field(arm.getClass(), "specialist").get(arm);
            while (true) {
                if (candidate.paused()) candidate.resume(37);
                if (candidate.step() && !candidate.paused()) break;
            }
            BigInteger[] witness = candidate.counts();
            check(witness != null && witness[0].add(witness[1].multiply(BigInteger.TWO))
                    .add(witness[2].multiply(BigInteger.valueOf(3))).equals(BigInteger.valueOf(3)), "Invalid exact candidate");
            Method publish = arm.getClass().getDeclaredMethod("publish", CountModelViews.class); publish.setAccessible(true);
            publish.invoke(arm, models);
            Method infeasible = arm.getClass().getDeclaredMethod("infeasible"); infeasible.setAccessible(true);
            check(!(boolean) infeasible.invoke(arm), "Candidate exported a negative conclusion");
            var domains = models.domains(models.available().get(0));
            check(Arrays.equals(domains.lower(), low) && Arrays.equals(domains.upper(), high) &&
                    domains.version() == 0 && models.conflictVersion() == 0, "Private domains escaped to model facts");
        }
        check(budget.reservedBytes() == 0, "Message portfolio leaked ownership");
        System.out.println("Message portfolio: late admission, proof isolation, fixed completion allowance and exact candidate passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
