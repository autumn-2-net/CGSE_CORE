import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

public final class NestedCompilerChecks {
    static GraphPlan<String> plan(List<GraphRecipe<String>> recipes, long amount, Map<String,Long> stock) {
        var b=new PlanningBudget(5000,10_000_000,128L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"Q0",amount,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
        while(!w.step()){}return w.result();
    }
    static int nodes(PlanStep p) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<PlanStep,Boolean>());
        var todo=new ArrayDeque<PlanStep>();todo.add(p);
        while(!todo.isEmpty()) {
            var s=todo.removeFirst();if(!seen.add(s))continue;
            if(s instanceof PlanStep.Sequence q)todo.addAll(q.children());
            if(s instanceof PlanStep.Repeat r)todo.add(r.body());
        }
        return seen.size();
    }
    static void check(boolean condition,String detail) {if(!condition)throw new AssertionError(detail);}
    static void execute(GraphPlan<String> p,int reload) {
        var m=new ComplexCycleStress.Machines(p,reload);
        for(int tick=0;tick<250_000&&!m.runtime.finished();tick++)m.step();
        check(m.runtime.state()==GraphJobRuntime.State.COMPLETED,"Runtime "+m.runtime.state()+" "+m.runtime.reason());
        check(m.accepted.equals(p.patternTimes()),"Wrong dispatch counts");
        check(m.delivered.equals(Map.of(p.target(),p.amount())),"Wrong delivery");
        check(m.physical.isEmpty()&&m.flights.isEmpty(),"Stranded material");
        p.seeds().forEach((k,v)->check(m.refunded.getOrDefault(k,0L)>=v,"Lost catalyst "+k));
    }
    public static void main(String[] args) {
        var random=new Random(2985625);int passed=0;
        for(int depth:new int[]{24,40,60})for(long amount:new long[]{1,100_000_017,Long.MAX_VALUE})for(int permutation=0;permutation<5;permutation++) {
            var recipes=new ArrayList<>(DeepLongBoundary.nested(depth));Collections.shuffle(recipes,random);
            var p=plan(recipes,amount,Map.of("C0",1L));
            check(p.feasible(),"Nested "+depth+" n="+amount+" "+p.result());
            PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);
            check(p.initialExact().equals(Map.of("C0",BigInteger.ONE)),"Unexpected starting material");
            var counts=p.patternTimesExact();
            for(int level=0;level<depth;level++)for(String prefix:List.of("open","close"))
                check(BigInteger.valueOf(amount).shiftLeft(level).equals(counts.get(prefix+level)),"Wrong exact count "+prefix+level);
            check(BigInteger.valueOf(amount).shiftLeft(depth).equals(counts.get("inner")),"Wrong exact inner count");
            check(nodes(p.steps())<12*depth+16,"Expanded AST "+nodes(p.steps()));
            if(permutation==0)System.out.println("NESTED_CHECK depth="+depth+" request="+amount+" nodes="+nodes(p.steps())+" inner="+counts.get("inner"));
            passed++;
        }
        for(int depth=1;depth<=7;depth++) {
            var p=plan(DeepLongBoundary.nested(depth),7,Map.of("C0",1L));
            check(p.feasible(),"Executable nested "+depth);
            execute(p,0);execute(p,97);passed+=2;
        }
        for(int depth:new int[]{24,40,60}) {
            var c=ComplexCycleStress.nested(depth,2,1,1);
            var stock=new HashMap<>(c.stock());stock.compute("R",(k,v)->v-1);
            var p=plan(c.recipes(),1,stock);
            check(!p.feasible(),"Invented raw material depth="+depth);
            System.out.println("MISSING_RAW depth="+depth+" result="+p.result()+" deficit="+p.missingExact());passed++;
        }
        var growth=DeepLongBoundary.run("cpu-limit-growth",List.of(DeepLongBoundary.r("g",Map.of("C",1L),Map.of("C",2L))),"C",Long.MAX_VALUE,Map.of("C",1L),true);
        boolean rejected=false;try{PlanVerifier.verifyRuntimeInventory(growth);}catch(ArithmeticException expected){rejected=true;}
        check(rejected,"Physical per-key capacity must not wrap");passed++;
        var recipes=DeepLongBoundary.nested(60);
        var b=new PlanningBudget(5000,200,128L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"Q0",Long.MAX_VALUE,Map.of("C0",1L),true,true,b);
        while(!w.step()){}check(w.result().result()==GraphPlan.Result.SEARCH_LIMIT,"Global work limit lost");passed++;
        System.out.println("NESTED_CHECKS_PASS "+passed);
    }
}
