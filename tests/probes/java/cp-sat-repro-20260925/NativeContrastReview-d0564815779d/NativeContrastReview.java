package org.cgse.core;

import com.google.ortools.Loader;
import java.util.*;

public final class NativeContrastReview {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Loader.loadNativeLibraries();
        var json = LocalContrastReview.file(args[0]);
        var f = new ContrastProbe.Fixture("supplied_json", json.recipes(), json.stock(), json.target(), json.amount(), true);
        for (int i = 0; i < 16; i++) ContrastProbe.cpDag(f, 3);
        for (int i = 0; i < 21; i++) System.out.println("LOCAL_CP_SUBSET " + i + " " + ContrastProbe.cpDag(f, 3));
        ContrastProbe.TRACE = true;
        System.out.println("LOCAL_CP_TRACE " + ContrastProbe.cpDag(f, 3));
        ContrastProbe.TRACE = false;
        for (long n : new long[] {1000, 1_000_000_000L, Long.MAX_VALUE})
            System.out.println("LOCAL_CP_COMPACT " + n + " " + ContrastProbe.cpCycle(ContrastProbe.cycle(n), 3, false));
        System.out.println("LOCAL_CP_EXPANDED " + ContrastProbe.cpCycle(ContrastProbe.cycle(1000), 3, true));
    }
}
