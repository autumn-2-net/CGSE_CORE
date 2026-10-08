import org.cgse.core.*;
import java.util.*;

public final class SaveCycleReview {
    static final String H="hypercube", D="residue", S="spatial", T="temporal";
    static Map<String,Long> a(Object... x) {var m=new LinkedHashMap<String,Long>();for(int i=0;i<x.length;i+=2)m.put((String)x[i],((Number)x[i+1]).longValue());return m;}
    static GraphRecipe<String> r(String id, Map<String,Long> in, Map<String,Long> out) {
        var slots=new ArrayList<GraphRecipe.Slot<String>>(); in.forEach((k,v)->slots.add(new GraphRecipe.Slot<>(k,v,slots.size())));
        return new GraphRecipe<>(id,id,slots,out);
    }
    static List<GraphRecipe<String>> recipes() {return List.of(
        r("forge64",a("rod",16,"anomaly",1,"excited",1000,S,1000),a(H,64,D,100)),
        r("metal",a("catalyst",1,H,1,"spacetime",100,"tennessine",144),a("metal",144,D,100)),
        r("space",a(H,1,"anomaly",1,"plate",16,T,10000,"stellar",10000),a(S,10000)),
        r("time",a("charge",4,H,1,"spacetime",1000,D,100),a(T,500,S,500))
    );}
    static Map<String,Long> raw() {return a("rod",Long.MAX_VALUE,"anomaly",Long.MAX_VALUE,"excited",Long.MAX_VALUE,
        "catalyst",Long.MAX_VALUE,"spacetime",Long.MAX_VALUE,"tennessine",Long.MAX_VALUE,"plate",Long.MAX_VALUE,
        "stellar",Long.MAX_VALUE,"charge",Long.MAX_VALUE);}
    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("monotonic")){ monotonic(); return; }
        for (String mode:List.of("empty","raw","seed1","seed4","seed64","residue200","spatial1000")) {
            var stock=mode.equals("empty")?a():raw();
            if(mode.startsWith("seed")) stock.put(H,Long.parseLong(mode.substring(4)));
            if(mode.equals("residue200")){stock.put(H,2L);stock.put(D,200L);}
            if(mode.equals("spatial1000"))stock.put(S,1000L);
            for(long n:new long[]{1,100_000_017L,Long.MAX_VALUE}) {
                var budget=new PlanningBudget(3000,10_000_000,128L<<20,()->false,System::nanoTime);
                long start=System.nanoTime();
                var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes()),H,n,stock,true,true,budget).catalysts(CatalystPolicy.MINIMAL);
                while(!work.step()){}
                var plan=work.result();
                if(plan.feasible())PlanVerifier.verify(plan);
                System.out.printf(Locale.ROOT,"SAVE mode=%s n=%d result=%s ms=%.3f nodes=%d initial=%s missing=%s seeds=%s counts=%s%n",mode,n,plan.result(),(System.nanoTime()-start)/1e6,budget.nodes(),plan.initialExact(),plan.missingExact(),plan.seeds(),plan.patternTimesExact());
            }
        }
    }
    static void monotonic() {
        var full=recipes().stream().filter(r->!r.id().equals("metal")).toList();
        var reduced=full.stream().filter(r->!r.id().equals("space")).toList();
        for(String mode:List.of("empty","raw","funded")) {
            var stock=mode.equals("empty")?a():raw();
            if(mode.equals("funded")){stock.put(H,2L);stock.put(D,200L);}
            GraphPlan<String> previous=null;
            for(var set:List.of(reduced,full)) {
                var budget=new PlanningBudget(3000,10_000_000,128L<<20,()->false,System::nanoTime);
                var work=new GraphPlanningWork<>(new GraphCompiler<>(set),H,1,stock,true,true,budget).catalysts(CatalystPolicy.MINIMAL);
                while(!work.step()){}
                var p=work.result();
                System.out.println("CHOICE stock="+mode+" count="+set.size()+" result="+p.result()+" initial="+p.initialExact()+" missing="+p.missingExact()+" steps="+p.steps());
                if(previous!=null) {
                    var ids=new LinkedHashMap<String,GraphRecipe<String>>();set.forEach(r->ids.put(r.id(),r));
                    if(!previous.initialExact().isEmpty()) {
                        PlanVerifier.verify(new GraphPlan<>(H,1,true,previous.steps(),ids,previous.initialExact(),previous.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0));
                        System.out.println("OLD_WITNESS_VALID_WITH_EXTRA_SOURCE=true");
                    }
                }
                previous=p;
            }
        }
    }
}
