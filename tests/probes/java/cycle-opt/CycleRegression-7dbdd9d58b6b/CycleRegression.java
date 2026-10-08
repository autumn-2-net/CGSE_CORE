import org.cgse.core.*;
import java.util.*;

public final class CycleRegression {
    public static void main(String[] args) {
        Random random = new Random(780134);
        int passed = 0, limited = 0;
        for (int i = 0; i < 160; i++) {
            int depth = 1 + random.nextInt(8), ratio = 1 + random.nextInt(7);
            long amount = i % 3 == 0 ? 100_000_017 : 1 + random.nextInt(9);
            var original = ComplexCycleStress.nested(depth, ratio, amount, 1);
            var recipes = new ArrayList<>(original.recipes()); Collections.shuffle(recipes, random);
            var c = new ComplexCycleStress.Case(original.name(), recipes, original.target(), amount,
                    original.stock(), original.seeds(), original.witness(), true);
            ComplexCycleStress.validate(c, c.known(), true);
            var measured = ComplexCycleStress.run(c, null, 3000);
            if (!measured.plan().feasible()) {
                limited++;
                System.out.println("SHUFFLE_LIMIT sample=" + i + " name=" + c.name() + " n=" + amount + " result=" + measured.plan().result());
            } else passed++;
        }
        System.out.println("SHUFFLE passed=" + passed + " limited=" + limited);
        for (int size : new int[]{128, 256, 512, 1024}) {
            var c = ComplexCycleStress.hub(size, 100_000_017, 4);
            ComplexCycleStress.validate(c, c.known(), true);
            ComplexCycleStress.print(c, "scale", ComplexCycleStress.run(c, null, 5000));
        }
        // With no seed, or one less raw item than the analytically required
        // quantity, no amount of cycle compression may create a feasible plan.
        for (int depth = 1; depth <= 6; depth++) for (int ratio = 2; ratio <= 3; ratio++) {
            var c = ComplexCycleStress.nested(depth, ratio, 3, 1);
            for (String missing : new String[]{"C0", "R"}) {
                var stock = new TreeMap<>(c.stock()); stock.put(missing, stock.get(missing) - 1);
                var bad = new ComplexCycleStress.Case(c.name(), c.recipes(), c.target(), c.amount(), stock, c.seeds(), c.witness(), false);
                var result = ComplexCycleStress.run(bad, null, 3000);
                if (result.plan().feasible()) throw new AssertionError("Invented material " + missing);
            }
        }
        System.out.println("NEGATIVE passed=24");
    }
}
