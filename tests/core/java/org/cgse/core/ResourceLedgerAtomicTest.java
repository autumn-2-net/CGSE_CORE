package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Public ledger input failures must not publish a partial batch restoration. */
public final class ResourceLedgerAtomicTest {
    private static int checks;

    public static void main(String[] args) {
        var ledger = new ResourceLedger<>(Map.of("B", Long.MAX_VALUE));
        var overflow = new LinkedHashMap<String, Long>();
        overflow.put("A", 1L);
        overflow.put("B", 1L);
        unchanged(ledger, overflow, ArithmeticException.class);
        check(ledger.get("A") == 0, "overflow cannot introduce an earlier new key");

        for (int variant = 0; variant < 4; variant++) {
            var invalid = new LinkedHashMap<String, Long>();
            invalid.put("A", 7L);
            if (variant == 0) invalid.put("B", -1L);
            else if (variant == 1) invalid.put(null, 1L);
            else if (variant == 2) invalid.put("B", null);
            else invalid.put(null, 0L);
            unchanged(ledger, invalid, variant == 0 ? IllegalArgumentException.class : NullPointerException.class);
        }
        unchanged(ledger, null, NullPointerException.class);
        ledger.restore(Map.of("A", 0L, "B", 0L));
        check(ledger.snapshot().equals(Map.of("B", Long.MAX_VALUE)), "zero restoration adds no phantom key");

        var random = new Random(2091011);
        for (int trial = 0; trial < 2000; trial++) {
            long first = random.nextInt(10000) + 1;
            long second = Long.MAX_VALUE - random.nextInt(10000);
            var held = new ResourceLedger<>(Map.of("A", first, "B", second));
            var before = held.snapshot();
            var escrow = held.take(Map.of("A", first, "B", 1L + random.nextInt(1000)), 1);
            held.restore(escrow);
            check(before.equals(held.snapshot()), "real escrow take/restore conserves all material near long headroom");
            var failed = new LinkedHashMap<String, Long>();
            failed.put("A", 1L);
            failed.put("B", Long.MAX_VALUE - second + 1);
            unchanged(held, failed, ArithmeticException.class);
            held.remove("B", 1);
            held.restore(Map.of("B", 1L));
            check(before.equals(held.snapshot()), "failed restore does not poison subsequent valid operations");
        }
        System.out.println("ResourceLedgerAtomicTest: PASS assertions=" + checks);
    }

    private static void unchanged(ResourceLedger<String> ledger, Map<String, Long> incoming, Class<? extends Throwable> type) {
        var before = ledger.snapshot();
        Throwable caught = null;
        try { ledger.restore(incoming); } catch (Throwable failure) { caught = failure; }
        check(type.isInstance(caught), "invalid restore reports " + type.getSimpleName());
        check(before.equals(ledger.snapshot()), "invalid restore leaves entire ledger unchanged");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
