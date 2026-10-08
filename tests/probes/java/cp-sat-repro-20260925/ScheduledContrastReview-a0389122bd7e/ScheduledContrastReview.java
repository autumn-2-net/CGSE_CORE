package org.cgse.core;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ScheduledContrastReview {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        var f = LocalContrastReview.file(args[0]);
        for (int threads : new int[] {1, 4, 8, 16}) {
            try (var scheduler = new PlanningScheduler(threads, 32, 512, 1_000_000)) {
                for (int i = 0; i < 2; i++) {
                    var b = new PlanningBudget(3000, 20_000_000, 256L << 20, () -> false, System::nanoTime);
                    b.enableMetrics();
                    long start = System.nanoTime();
                    var work = new GraphPlanner<>(new GraphCompiler<>(f.recipes())).begin(f.target(), f.amount(), f.stock(), true, true, b);
                    var p = scheduler.submit(work, b).get(10, TimeUnit.SECONDS);
                    double ms = (System.nanoTime() - start) / 1e6;
                    if (p.feasible()) LocalContrastReview.verify(f, LocalContrastReview.summary(p.steps(), p.recipes()));
                    System.out.printf("SCHEDULED threads=%d sample=%d result=%s ms=%.4f checks=%d peak_bytes=%d active=%d trace=%s%n",
                        threads, i, p.result(), ms, b.nodes(), b.peakBytes(), b.metrics().peakActiveWorkers(), b.diagnostics());
                }
            }
        }
    }
}
