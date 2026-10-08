package org.cgse.core;

import java.util.*;

/** Local scheduling sensitivity experiment; these are synthetic observations, not solver claims. */
public class PortfolioScaleProbe {
    public static void main(String[] args) {
        run("equal_units", false, false);
        run("one_arm_1000x_units", true, false);
        run("same_observations_normalized", true, true);
    }
    static void run(String label, boolean scaled, boolean normalized) {
        var policy = new CountPortfolioPolicy();
        var arms = new ArrayList<CountPortfolioPolicy.Arm>();
        for (int i = 0; i < 12; i++) arms.add(policy.add(100));
        long remaining = 20_000_000;
        while (remaining > 0) {
            var arm = policy.select(); int id = arms.indexOf(arm);
            long q = policy.quantum(arm, remaining);
            policy.selected(arm);
            // Each underlying arm has exactly one synthetic progress event per 4096 work.
            // Only the externally reported unit changes for arm zero.
            long multiplier = scaled && id == 0 ? 1000 : 1;
            long observed = Math.max(1, q / 4096) * multiplier;
            policy.feedback(arm, q, normalized ? observed / multiplier : observed);
            remaining -= q;
        }
        System.out.print("{\"case\":\"" + label + "\",\"work\":[");
        for (int i=0;i<arms.size();i++) System.out.print((i==0?"":",")+arms.get(i).work);
        System.out.print("],\"selections\":[");
        for (int i=0;i<arms.size();i++) System.out.print((i==0?"":",")+arms.get(i).selections);
        System.out.println("]}");
    }
}
