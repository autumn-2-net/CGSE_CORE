import org.cgse.core.*;
import java.util.*;

/** Local material-model probe. KubeJS amounts from the downloaded script; GT forming is explicit. */
public final class HypercubeReview {
    static final String H="hypercube", M="metal", I="ingot", R="rod", S="spatial", T="temporal", D="residue";
    static Map<String,Long> a(Object... entries) { var m=new LinkedHashMap<String,Long>(); for(int i=0;i<entries.length;i+=2)m.put((String)entries[i],((Number)entries[i+1]).longValue());return m; }
    static GraphRecipe<String> r(String id, Map<String,Long> in, Map<String,Long> out) {return ComplexCycleStress.recipe(id,in,out);}
    static List<GraphRecipe<String>> recipes(boolean base, boolean qft) {
        var list=new ArrayList<GraphRecipe<String>>();
        list.add(r("forge64",a(R,16,"anomaly",1,"excited",1000,S,1000),a(H,64,D,100)));
        list.add(r("metal",a(H,1,"spacetime",100,"tennessine",144),a(M,144,D,100)));
        list.add(r("solidify",a(M,144),a(I,1)));
        list.add(r("extrude",a(I,1),a(R,2)));
        if(qft)list.add(r("qft",a(T,10000,"stellar",10000,"cosmicplate",16),a(S,10000)));
        list.add(r("time",a(H,1,"charge",4,"spacetime",1000,D,100),a(T,500,S,500)));
        if(base)list.add(r("base",a("cosmicrod",12,"titaniumplate",12,"excited",1000),a(H,1,D,100)));
        return list;
    }
    public static void main(String[] args) {
        for(boolean base:new boolean[]{false,true}) for(boolean qft:new boolean[]{false,true})
            for(long seeds:new long[]{0,1,9,10,64}) for(long n:new long[]{1,100_000_017L}) {
                var stock=a(H,seeds,"anomaly",Long.MAX_VALUE,"excited",Long.MAX_VALUE,"spacetime",Long.MAX_VALUE,
                    "tennessine",Long.MAX_VALUE,"charge",Long.MAX_VALUE,"stellar",Long.MAX_VALUE,"cosmicplate",Long.MAX_VALUE,
                    "cosmicrod",Long.MAX_VALUE,"titaniumplate",Long.MAX_VALUE);
                var budget=new PlanningBudget(3000,10_000_000,128L<<20,()->false,System::nanoTime);
                long start=System.nanoTime();
                var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes(base,qft)),H,n,stock,true,true,budget).catalysts(CatalystPolicy.MINIMAL);
                while(!work.step()){}
                var plan=work.result();
                if(plan.feasible()) PlanVerifier.verify(plan);
                System.out.printf(Locale.ROOT,"HYPERCUBE base=%s qft=%s seeds=%d amount=%d result=%s ms=%.3f search=%d initial=%s counts=%s%n",base,qft,seeds,n,plan.result(),(System.nanoTime()-start)/1e6,budget.nodes(),plan.initialExact(),plan.patternTimesExact());
            }
    }
}
