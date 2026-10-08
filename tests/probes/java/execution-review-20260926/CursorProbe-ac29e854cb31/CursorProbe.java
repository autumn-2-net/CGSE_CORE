import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

public final class CursorProbe {
    public static void main(String[] args) {
        String mode = args[0];
        int depth = Integer.parseInt(args[1]);
        PlanStep root = mode.equals("empty") ? new PlanStep.Sequence(List.of()) : new PlanStep.Batch("a", 1);
        for (int i = 0; i < depth; i++) root = new PlanStep.Sequence(mode.equals("deep") ? List.of(root) : List.of(root, root));
        long start = System.nanoTime();
        try {
            PlanCursor cursor = new PlanCursor(root);
            long indexed = System.nanoTime();
            PlanStep.Batch batch = cursor.current();
            long positioned = System.nanoTime();
            var counts = cursor.remainingCountsExact();
            long counted = System.nanoTime();
            if (mode.equals("empty") && (batch != null || !counts.isEmpty())) throw new AssertionError("Nonempty");
            if (!mode.equals("empty")) {
                BigInteger expected = mode.equals("deep") ? BigInteger.ONE : BigInteger.ONE.shiftLeft(depth);
                if (!expected.equals(counts.get("a"))) throw new AssertionError("Wrong counts");
                if (mode.equals("batch") && batch.runs() != expected.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact())
                    throw new AssertionError("Only dispatched " + batch.runs() + " of " + expected);
            }
            System.out.printf(Locale.ROOT,"PASS %s depth=%d index_ms=%.3f current_ms=%.3f count_ms=%.3f batch=%s%n", mode,depth,
                (indexed-start)/1e6,(positioned-indexed)/1e6,(counted-positioned)/1e6,batch);
        } catch (StackOverflowError failure) {
            System.out.println("STACK_OVERFLOW " + mode + " " + depth);
            System.exit(2);
        }
    }
}
