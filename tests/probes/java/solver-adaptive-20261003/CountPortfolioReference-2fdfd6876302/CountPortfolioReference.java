package org.cgse.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Deterministic effort-normalized feedback; scheduling observations are never proofs. */
final class CountPortfolioReference {

    static final long MIN_QUANTUM = 4096, MAX_QUANTUM = 32768;

    static final class Arm {

        final long startupCost;
        long work, lastTurn = -1;
        int selections, idleSlices, productiveSlices;
        double reward;
        boolean retired;
        long bestProgress, bestSpent = 1;
        long observations, progressObservations, pendingWork;
        double costMean, costVariance, progressCostMean, progressCostVariance;
        int waiting;
        double rewardVariance;

        Arm(long startupCost) {
            this.startupCost = startupCost;
        }
    }

    private final List<Arm> arms = new ArrayList<>();
    private long turn;

    Arm add(long startupCost) {
        Arm arm = new Arm(startupCost);
        arms.add(arm);
        return arm;
    }

    Arm select() {
        Arm fresh = null, overdue = null, best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int live = (int) arms.stream().filter(arm -> !arm.retired).count();
        for (Arm arm : arms) {
            if (arm.retired) continue;
            if (arm.selections == 0) {
                if (fresh == null || arm.startupCost < fresh.startupCost) fresh = arm;
                continue;
            }
            if (arm.waiting >= 2L * live && (overdue == null || arm.waiting > overdue.waiting)) overdue = arm;
            // Like CP-SAT's neighborhood feedback, compare recent gain per
            // deterministic effort and retain an exploration bonus. The hard
            // aging rule also gives a stalled original model another turn.
            double uncertainty = StrictMath.sqrt(arm.rewardVariance + 1.0 / arm.selections);
            double exploration = uncertainty * StrictMath.sqrt(StrictMath.log(turn + 1.0) / arm.selections);
            double score = arm.reward + exploration;
            if (best == null || score > bestScore || score == bestScore && arm.work < best.work) {
                best = arm;
                bestScore = score;
            }
        }
        return fresh != null ? fresh : overdue != null ? overdue : best;
    }

    void selected(Arm arm) {
        arm.lastTurn = turn;
        if (turn < Long.MAX_VALUE) turn++;
        if (arm.selections < Integer.MAX_VALUE) arm.selections++;
        for (Arm other : arms) if (!other.retired && other.selections > 0) {
            if (other == arm) other.waiting = 1;
            else if (other.waiting < Integer.MAX_VALUE) other.waiting++;
        }
    }

    long quantum(Arm arm, long remaining) {
        long live = arms.stream().filter(next -> !next.retired).count();
        // A lone retained solver keeps its old quantum. Competing views start
        // with a small probe; productive continuations grow within that cap.
        double estimated = arm.observations == 0 ? MIN_QUANTUM : upperCost(arm.costMean, arm.costVariance, arm.observations);
        if (arm.progressObservations > 0) estimated = Math.max(estimated,
                upperCost(arm.progressCostMean, arm.progressCostVariance, arm.progressObservations));
        long requested = live <= 1 ? MAX_QUANTUM : Math.max(MIN_QUANTUM,
                (long) Math.min(MAX_QUANTUM, StrictMath.ceil(estimated * arm.reward)));
        return Math.min(requested, remaining);
    }

    void feedback(Arm arm, long spent, long progress) {
        if (spent < 0) throw new IllegalArgumentException("Negative completed effort");
        long positive = Math.max(0, progress), divisor = Math.max(1, spent);
        long bestProgress = arm.bestProgress, bestSpent = arm.bestSpent;
        double sample = 0;
        if (positive > 0) {
            if (bestProgress == 0) {
                bestProgress = positive;
                bestSpent = divisor;
                sample = 1;
            } else {
                // Conflicts, bound reductions and local-search improvements have
                // different units. Compare each arm's rate with its own best;
                // changing its progress unit must not change scheduling scores.
                BigInteger numerator = BigInteger.valueOf(positive).multiply(BigInteger.valueOf(bestSpent));
                BigInteger denominator = BigInteger.valueOf(bestProgress).multiply(BigInteger.valueOf(divisor));
                if (numerator.compareTo(denominator) >= 0) {
                    bestProgress = positive;
                    bestSpent = divisor;
                    sample = 1;
                } else sample = new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL64).doubleValue();
            }
        }
        // Publish only a completed observation. EWMA forgets old success once
        // a continuation stops helping; aging still retains every live arm.
        double reward = arm.selections == 1 ? sample : 0.75 * arm.reward + 0.25 * sample;
        long nextWork = spent > Long.MAX_VALUE - arm.work ? Long.MAX_VALUE : arm.work + spent;
        // Welford observations depend on completed deterministic cost, not gain units.
        long observations = arm.observations == Long.MAX_VALUE ? Long.MAX_VALUE : arm.observations + 1;
        double costDelta = spent - arm.costMean;
        double costMean = arm.costMean + costDelta / observations;
        double costVariance = Math.max(0, arm.costVariance + costDelta * (spent - costMean));
        long pending = spent > Long.MAX_VALUE - arm.pendingWork ? Long.MAX_VALUE : arm.pendingWork + spent;
        long progressObservations = arm.progressObservations;
        double progressCostMean = arm.progressCostMean, progressCostVariance = arm.progressCostVariance;
        if (positive > 0) {
            if (progressObservations < Long.MAX_VALUE) progressObservations++;
            double delta = pending - progressCostMean;
            progressCostMean += delta / progressObservations;
            progressCostVariance = Math.max(0, progressCostVariance + delta * (pending - progressCostMean));
            pending = 0;
        }
        arm.observations = observations;
        arm.costMean = costMean;
        arm.costVariance = costVariance;
        arm.progressObservations = progressObservations;
        arm.progressCostMean = progressCostMean;
        arm.progressCostVariance = progressCostVariance;
        arm.pendingWork = pending;
        arm.work = nextWork;
        arm.bestProgress = bestProgress;
        arm.bestSpent = bestSpent;
        arm.rewardVariance = arm.selections == 1 ? 0 : .75 * arm.rewardVariance + .25 * (sample - arm.reward) * (sample - reward);
        arm.reward = reward;
        arm.idleSlices = positive > 0 ? 0 : Math.min(4, arm.idleSlices + 1);
        arm.productiveSlices = positive > 0 ? Math.min(3, arm.productiveSlices + 1) : 0;
    }
    private static double upperCost(double mean, double variance, long observations) {
        return mean + (observations <= 1 ? 0 : StrictMath.sqrt(variance / (observations - 1) / observations));
    }
}
