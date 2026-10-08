import java.math.BigInteger;
import java.util.*;
import org.cgse.core.*;

public class SourceBoundary {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return ComplexCycleStress.recipe(id,in,out);}
    static GraphPlan<String> run(String name,List<GraphRecipe<String>> recipes,String key,long n,Map<String,Long> stock,boolean expected){
        var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),key,n,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
        while(!w.step()){}var p=w.result();
        if(p.feasible())ExtremeCycleStress.validate(new ComplexCycleStress.Case(name,recipes,key,n,stock,Map.of(),p.steps(),true),p);
        System.out.printf("SOURCE %s request=%d recipes=%d result=%s ms=%.3f work=%d missing=%s detail=%s EXPECT=%s%n",name,n,recipes.size(),p.result(),(System.nanoTime()-start)/1e6,b.nodes(),p.missingExact(),b.failureDetail(),expected);
        if(p.feasible()!=expected)throw new AssertionError(name+" expected="+expected);
        if(!expected && !p.missingExact().values().stream().reduce(BigInteger.ZERO,BigInteger::add).equals(BigInteger.ONE))throw new AssertionError(name+" did not report one missing unit");
        return p;
    }
    public static void main(String[] args){
        for(long n:new long[]{2_147_483_647L,Long.MAX_VALUE})for(int paths:new int[]{4,16,64}){
            var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
            for(int i=0;i<paths;i++){recipes.add(r("s"+i,Map.of("R"+i,1L),Map.of("Q",1L)));stock.put("R"+i,n);}
            recipes.add(r("p",Map.of("Q",(long)paths),Map.of("P",1L)));
            run("split-"+paths,recipes,"P",n,stock,true);
            stock.put("R"+(paths-1),n-1);
            run("split-one-short-"+paths,recipes,"P",n,stock,false);
            stock.put("Q",1L);
            run("split-rescued-one-"+paths,recipes,"P",n,stock,true);
        }
        for(int depth:new int[]{256,8192}){
            var c=ExtremeCycleStress.dag(depth,"deep",Long.MAX_VALUE);var recipes=new ArrayList<>(c.recipes());
            recipes.add(0,r("bad",Map.of("scarce",2L),Map.of(c.target(),1L)));var stock=new HashMap<>(c.stock());stock.put("scarce",Long.MAX_VALUE);
            run("deep-alternate-"+depth,recipes,c.target(),c.amount(),stock,true);
        }
        for(int d:new int[]{24,40,60}){
            var c=ComplexCycleStress.nested(d,2,1,1);var stock=new HashMap<>(c.stock());stock.compute("R",(k,v)->v-1);
            run("nested-one-short-"+d,c.recipes(),c.target(),1,stock,false);
        }
    }
}
