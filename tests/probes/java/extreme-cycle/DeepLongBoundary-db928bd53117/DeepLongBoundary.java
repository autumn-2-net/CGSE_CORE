import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

public final class DeepLongBoundary {
    static final long MAX=Long.MAX_VALUE;
    static int passed;
    static int failed;
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return ComplexCycleStress.recipe(id,in,out);}
    static GraphPlan<String> run(String name,List<GraphRecipe<String>> recipes,String target,long n,Map<String,Long> stock,boolean feasible){
        var b=new PlanningBudget(5000,10_000_000,128L<<20,()->false,System::nanoTime);
        long start=System.nanoTime();
        var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),target,n,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
        while(!work.step()){}var p=work.result();
        System.out.printf(Locale.ROOT,"LONG name=%s request=%d result=%s wall_ms=%.3f nodes=%d initial=%s missing=%s max_runs=%s%n",name,n,p.result(),(System.nanoTime()-start)/1e6,b.nodes(),p.initialExact().size(),p.missingExact(),p.patternTimesExact().values().stream().max(BigInteger::compareTo).orElse(BigInteger.ZERO));
        if(p.feasible()!=feasible){failed++;System.out.println("LONG_FAIL "+name+" expected="+feasible);}
        if(p.feasible()) {
            var c=new ComplexCycleStress.Case(name,recipes,target,n,stock,Map.of(),p.steps(),true);
            ExtremeCycleStress.validate(c,p);
        }
        passed++;return p;
    }
    static List<GraphRecipe<String>> nested(int d){
        var out=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<d;i++){
            out.add(r("open"+i,Map.of("C"+i,1L),Map.of("C"+(i+1),1L)));
            out.add(r("close"+i,Map.of("C"+(i+1),1L,"Q"+(i+1),2L),Map.of("C"+i,1L,"Q"+i,1L)));
        }
        out.add(r("inner",Map.of("C"+d,1L),Map.of("C"+d,1L,"Q"+d,1L)));return out;
    }
    public static void main(String[] args){
        for(long n:new long[]{Integer.MAX_VALUE,1L+Integer.MAX_VALUE,1L<<53,(1L<<53)+1,MAX-1,MAX}){
            run("growth",List.of(r("growth",Map.of("C",1L),Map.of("C",2L))),"C",n,Map.of("C",1L),true);
            run("nested24",nested(24),"Q0",n,Map.of("C0",1L),true);
        }
        run("nested60",nested(60),"Q0",MAX,Map.of("C0",1L),true);
        run("nested-no-seed",nested(24),"Q0",MAX,Map.of(),false);
        var ring=ComplexCycleStress.ring(32768,MAX,1);
        run("ring32768",ring.recipes(),ring.target(),MAX,ring.stock(),true);
        for(String shape:List.of("deep","tree","wide")){
            var dag=ExtremeCycleStress.dag(2048,shape,1);
            var stock=new HashMap<String,Long>();stock.put("R",MAX);
            run("dag-"+shape,dag.recipes(),dag.target(),MAX,stock,shape.equals("deep"));
        }
        var shortage=run("wide-required",List.of(r("linear",Map.of("R",16L),Map.of("P",1L))),"P",MAX,Map.of("R",MAX),false);
        if(!shortage.initialExact().get("R").equals(BigInteger.valueOf(MAX).multiply(BigInteger.valueOf(16))))throw new AssertionError("Clipped intermediate");
        var mixed=List.of(r("source1",Map.of("R1",1L),Map.of("Q",1L)),r("source2",Map.of("R2",1L),Map.of("Q",1L)),r("target",Map.of("Q",2L),Map.of("P",1L)));
        run("mixed-wide",mixed,"P",MAX,Map.of("R1",MAX,"R2",MAX),true);
        run("mixed-short-one",mixed,"P",MAX,Map.of("R1",MAX,"R2",MAX-1),false);
        System.out.println("LONG_PASS "+passed);
        if(failed!=0)throw new AssertionError("Failures="+failed);
    }
}
