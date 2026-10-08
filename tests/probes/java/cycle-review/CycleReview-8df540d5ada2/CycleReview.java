import org.cgse.core.*;
import java.util.*;

/** Local review only: the original five-recipe counterexample, unchanged. */
public final class CycleReview {
    public static void main(String[] args) {
        String label = args.length == 0 ? "current" : args[0];
        for (int i = 0; i < 30; i++) ComplexCycleStress.run(ComplexCycleStress.ring(8, 100, 1), null, 3000);
        for (long amount : new long[]{1, 100_000_017L, 1_000_000_000_000L, Long.MAX_VALUE / 7}) {
            var fixture = ComplexCycleStress.nested(2, 2, amount, 1);
            ComplexCycleStress.validate(fixture, fixture.known(), true);
            var samples = new ArrayList<ComplexCycleStress.Measured>();
            for (int i = 0; i < 7; i++) samples.add(ComplexCycleStress.run(fixture, null, 3000));
            samples.sort(Comparator.comparingLong(ComplexCycleStress.Measured::nanos));
            var measured = samples.get(3);
            ComplexCycleStress.print(fixture, label + "-median-7", measured);
            if (amount == 1 && measured.plan().feasible()) {
                System.out.println("WITNESS initial=" + measured.plan().initial() + " seeds=" + measured.plan().seeds() + " steps=" + measured.plan().steps());
                ComplexCycleStress.execute(fixture, measured.plan(), label);
            }
        }
    }
}
