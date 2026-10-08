package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ProductionLearningExhaustion {
    public static void main(String[] args) throws Exception {
        var fixtures = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
                ".local/solver-weighted-20261003/lp-learning/lseu.json"))).getAsJsonArray();
        int checked = 0;
        for (var entry : fixtures) {
            var fixture = entry.getAsJsonObject();
            int n = fixture.getAsJsonArray("lower").size();
            var low = new BigInteger[n];
            var high = new BigInteger[n];
            Arrays.fill(low, BigInteger.ZERO);
            Arrays.fill(high, BigInteger.ONE);
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for (var element : fixture.getAsJsonArray("rows")) {
                var row = element.getAsJsonObject();
                var terms = new TreeMap<Integer, BigInteger>();
                for (var term : row.getAsJsonObject("terms").entrySet())
                    terms.put(Integer.parseInt(term.getKey()), term.getValue().getAsBigInteger());
                rows.add(new ExactLinearProgram.Constraint(terms, row.get("upper").getAsBigInteger()));
            }
            for (long maximum : new long[]{524288, 750000, 1000000, 2000000}) {
                var budget = new PlanningBudget(0, maximum, 128L << 20, () -> false, System::nanoTime);
                CountLcg search = null;
                CountCanonicalModel canonical = null;
                boolean exhausted = false;
                try {
                    canonical = CountCanonicalModel.create(rows, low, high, budget);
                    if (canonical == null) throw new AssertionError("canonical declined");
                    search = new CountLcg(canonical.rows(), canonical.lower(), canonical.upper(), budget, 1024, true)
                            .learnedRelaxation();
                    if (!search.learnedRelaxationEnabled()) throw new AssertionError("LP arm declined");
                    for (int attempt = 0; attempt < 100000; attempt++) {
                        while (!search.step()) {}
                        if (search.counts() != null || search.infeasible())
                            throw new AssertionError("fixture unexpectedly completed before budget exhaustion");
                        if (!search.paused()) throw new AssertionError("lost retained continuation");
                        search.resume(127);
                    }
                    throw new AssertionError("budget did not exhaust");
                } catch (PlanningBudget.Exhausted expected) {
                    if (expected.limit() != PlanningBudget.Limit.SEARCH_LIMIT) throw expected;
                    if (search == null || search.counts() != null || search.infeasible())
                        throw new AssertionError("exhaustion changed mathematical result");
                    if (search.certificate() != null && search.certificate().closed()) throw new AssertionError("exhaustion produced closed certificate");
                    exhausted = true;
                } finally {
                    if (search != null) { search.close(); search.close(); }
                    if (canonical != null) { canonical.close(); canonical.close(); }
                    if (budget.reservedBytes() != 0) throw new AssertionError("leak=" + budget.reservedBytes());
                }
                if (!exhausted) throw new AssertionError("no exhaustion");
                checked++;
                System.out.println("EXHAUSTED limit=" + maximum + " work=" + budget.nodes() + " exact_status=UNKNOWN leaks=0");
            }
        }
        System.out.println("EXHAUSTION checked=" + checked + " false_results=0 false_certificates=0 leaks=0");
    }
}
