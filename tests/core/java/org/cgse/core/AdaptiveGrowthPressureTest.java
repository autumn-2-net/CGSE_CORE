// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Map;
import java.util.Set;

/** Congestion hints must recover and must never turn into a permanent parallelism cap. */
public final class AdaptiveGrowthPressureTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Clock {
        final AdaptiveScheduling<String> feedback = new AdaptiveScheduling<>();
        long tick, owner;

        Clock() {
            feedback.tick(0, true);
            feedback.watch("cycle", Set.of("seed"));
        }

        long begin(long runs) {
            long id = ++owner;
            feedback.begin(id, "cycle", runs, Map.of("seed", runs));
            return id;
        }

        void finish(long id, long runs, int ticks) {
            for (int i = 0; i < ticks; i++) feedback.tick(++tick, true);
            feedback.returned(id, "seed", runs);
        }

        void sample(long runs, int ticks) {
            long id = begin(runs);
            feedback.confirm(id);
            finish(id, runs, ticks);
        }

        int awaitProbe(long runs, int duration) {
            int completed = 0;
            while (!feedback.growing("cycle") && completed < 128) {
                sample(runs, duration);
                completed++;
            }
            check(feedback.growing("cycle"), "Stable constrained work never received a larger-batch probe");
            return completed;
        }
    }

    private static void constantLatencyIsScaling() {
        var host = new Clock();
        // A small increase in batch size at exactly the same latency is real
        // progress; it need not clear an arbitrary percentage improvement bar.
        for (long runs = 32; runs <= 160; runs++) {
            host.sample(runs, 5);
            check(host.feedback.growing("cycle"), "Constant-latency scaling misclassified at batch " + runs);
        }
    }

    private static void boundedProbesAndRecovery() {
        var host = new Clock();
        host.sample(16, 8);
        host.sample(32, 16);
        check(!host.feedback.growing("cycle"), "Larger batches with proportional latency kept reinvesting all output");
        int first = host.awaitProbe(32, 16);
        check(first > 0, "No observation window before probing constrained growth");

        long rejected = host.begin(64);
        host.feedback.reject(rejected);
        check(host.feedback.growing("cycle"), "Rejected probe consumed the exploration invitation");
        host.sample(32, 16);
        check(host.feedback.growing("cycle"), "An ordinary-sized acceptance consumed the larger-batch invitation");

        long probe = host.begin(64);
        host.feedback.confirm(probe);
        check(!host.feedback.growing("cycle"), "Accepted probe allowed unlimited further speculative growth");
        host.finish(probe, 64, 32);
        int second = host.awaitProbe(64, 32);
        check(second >= first, "Failed probe increased exploration frequency");

        // A provider upgrade makes the already-funded batch return quickly.
        // The old congestion cooldown must not keep suppressing this evidence.
        host.sample(64, 1);
        check(host.feedback.growing("cycle"), "Fast confirmed recovery retained an old congestion cooldown");
        host.sample(128, 1);
        check(host.feedback.growing("cycle"), "Recovered provider could not expand its next batch");
    }

    private static void unusedProbeCannotKeepOldEvidenceForever() {
        var host = new Clock();
        host.sample(16, 8);
        host.sample(32, 16);
        // Coupled stages can keep completing the same funded batch without
        // ever accepting the larger probe. Real progress must bound how long
        // the old throughput observation can keep controlling this node.
        for (int i = 0; i < 128; i++) host.sample(32, 16);
        check(host.feedback.growing("cycle"), "Unusable probe invitation stalled later growth");
        long larger = host.begin(64);
        host.feedback.confirm(larger);
        check(host.feedback.growing("cycle"), "Unused probe retained a permanent old congestion belief");
        host.finish(larger, 64, 1);
    }

    public static void main(String[] args) {
        var unknown = new AdaptiveScheduling<String>();
        unknown.tick(0, true);
        for (int i = 0; i < 513; i++) unknown.watch("node" + i, java.util.Set.of("seed"));
        check(!unknown.congested("node512"), "Exhausted optional timing coverage was mistaken for congestion");
        constantLatencyIsScaling();
        boundedProbesAndRecovery();
        unusedProbeCannotKeepOldEvidenceForever();
        var large = new Clock();
        large.sample(Long.MAX_VALUE / 4, 8);
        large.sample(Long.MAX_VALUE / 2, 16);
        check(!large.feedback.growing("cycle"), "Large batch throughput comparison overflowed");
        System.out.println("AdaptiveGrowthPressureTest " + checks + " checks passed");
    }
}
