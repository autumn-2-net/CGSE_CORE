import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

public class CycleReview {
    static int checks;
    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static void check(boolean condition, Object detail) { if (!condition) throw new AssertionError(detail); checks++; }
    static GraphPlan<String> solve(List<GraphRecipe<String>> recipes, String target, long amount, Map<String, Long> stock) {
        var plan = new GraphPlanner<>(new GraphCompiler<>(recipes)).plan(target, amount, stock, true, true, new PlanningBudget(0, 20_000_000, () -> false));
        check(plan.feasible() || plan.result() == GraphPlan.Result.MISSING_INPUT || plan.result() == GraphPlan.Result.MISSING_SEED, plan.result());
        if (plan.feasible()) PlanVerifier.verify(plan);
        var summary = SequenceSummary.of(plan.steps(), plan.recipes());
        check(summary.delta(target).compareTo(BigInteger.valueOf(amount)) >= 0, "no fresh target: " + plan.result() + " " + plan.patternTimesExact() + " " + summary.delta());
        return plan;
    }
    public static void main(String[] args) {
        // From the local pack's gtceu.js, lines 5857-5895. Reusable sulfonate
        // appears on both sides, just as captured configuration ingredients do.
        var ring = List.of(
            recipe("azide", Map.of("dicarbonate",33L,"sodium_azide",8L,"potassium",2L),Map.of("carbonyl_azide",2000L,"sodium",2L,"potash",6L)),
            recipe("aminate",Map.of("fullerene",1L,"CO",4000L,"water",8000L,"carbonyl_azide",4000L),Map.of("aminated_fullerene",1000L,"CO2",8000L,"butanol",4000L)),
            recipe("recover",Map.of("butanol",2000L,"CO2",2000L,"sulfonate",1000L),Map.of("dicarbonate",33L,"water",1000L,"sulfonate",1000L)));
        Map<String, Long> stock = new LinkedHashMap<>();
        for (var key : List.of("dicarbonate","sodium_azide","potassium","fullerene","CO","water","sulfonate")) stock.put(key, Long.MAX_VALUE);
        for (long amount : new long[]{1,1000,1_000_000_000,Long.MAX_VALUE}) {
            var p=solve(ring,"aminated_fullerene",amount,stock);
            System.out.println("chemical amount="+amount+" status="+p.result()+" counts="+p.patternTimesExact()+" net="+SequenceSummary.of(p.steps(),p.recipes()).delta());
        }
        // A concrete closed turnover of the pictured intermediate creates real
        // output outside the SCC even though its dicarbonate delta is zero.
        var recipes=new LinkedHashMap<String, GraphRecipe<String>>(); ring.forEach(r->recipes.put(r.id(),r));
        var loop=new PlanStep.Sequence(List.of(new PlanStep.Batch("azide",2),new PlanStep.Batch("aminate",1),new PlanStep.Batch("recover",2)));
        var summary=SequenceSummary.of(loop,recipes);
        check(summary.delta("dicarbonate").signum()==0,"recycled dicarbonate");
        check(summary.delta("aminated_fullerene").equals(BigInteger.valueOf(1000)),"productive loop");
        var neutral=List.of(recipe("ab",Map.of("A",1L),Map.of("B",1L)),recipe("bc",Map.of("B",1L),Map.of("C",1L)),recipe("ca",Map.of("C",1L),Map.of("A",1L)));
        for(long amount:new long[]{1,1_000_000_000,Long.MAX_VALUE}) {
            for(var inventory:List.of(Map.<String,Long>of(),Map.of("A",1L),Map.of("B",Long.MAX_VALUE),Map.of("C",Long.MAX_VALUE))) {
                var p=solve(neutral,"A",amount,inventory);
                check(p.patternTimesExact().getOrDefault("ab",BigInteger.ZERO).signum()==0,"unproductive full turnover: "+p.patternTimesExact());
            }
        }
        var loss=List.of(recipe("grind",Map.of("diamond",1L),Map.of("dust",1L)),recipe("restore",Map.of("dust",4L),Map.of("diamond",3L)));
        var p=solve(loss,"diamond",6,Map.of("diamond",1L));
        check(p.patternTimesExact().equals(Map.of("restore",BigInteger.TWO)),p.patternTimesExact());
        check(p.missingExact().equals(Map.of("dust",BigInteger.valueOf(8))),p.missingExact());
        for(int size=2;size<=6;size++) {
            var conversions=new ArrayList<GraphRecipe<String>>();
            for(int i=0;i<size;i++)conversions.add(recipe("convert"+i,Map.of("R"+i,33L*(i+1)),Map.of("R"+((i+1)%size),33L*((i+1)%size+1))));
            for(int stocked=1;stocked<size;stocked++) for(long goal:new long[]{1,33,1000,1_000_000_000,Long.MAX_VALUE}) {
                var q=solve(conversions,"R0",goal,Map.of("R"+stocked,Long.MAX_VALUE));
                var counts=q.patternTimesExact();
                if(counts.getOrDefault("convert0",BigInteger.ZERO).signum()!=0) System.out.println("WEIGHTED WASTE size="+size+" stocked="+stocked+" goal="+goal+" "+counts);
                check(counts.getOrDefault("convert0",BigInteger.ZERO).signum()==0,"retained lossless conversion at size="+size+" goal="+goal+": "+counts);
            }
        }
        System.out.println("Cycle review PASS checks="+checks);
    }
}
