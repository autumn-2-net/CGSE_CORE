import org.cgse.core.*;
import java.util.*;

public class CoproductRegression {
    static int assertions;
    static void check(boolean b, String s) {assertions++;if(!b)throw new AssertionError(s);}
    static GraphPlan<String> plan(List<GraphRecipe<String>> recipes,String target,long n,Map<String,Long> stock) {
        var budget=new PlanningBudget(5000,10000000,128L<<20,()->false,System::nanoTime);
        var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),target,n,stock,true,true,budget).catalysts(CatalystPolicy.MINIMAL);
        while(!work.step()){}
        return work.result();
    }
    static void hypothetical(GraphPlan<String> p) {
        PlanVerifier.verify(new GraphPlan<>(p.target(),p.amount(),p.preserveSeeds(),p.steps(),p.recipes(),p.initialExact(),p.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0));
    }
    public static void main(String[] args) {
        var recipes=SaveCycleReview.recipes().stream().filter(r->!r.id().equals("metal")).toList();
        for(int a=0;a<3;a++) for(int b=0;b<3;b++) if(a!=b) {
            int c=3-a-b;
            var perm=List.of(recipes.get(a),recipes.get(b),recipes.get(c));
            var empty=plan(perm,"hypercube",1,Map.of());
            check(!empty.feasible()&&!empty.missingExact().isEmpty(),"Empty cyclic stock must show a missing-material preview: "+empty.result());
            hypothetical(empty);
            var stock=SaveCycleReview.a("rod",16,"anomaly",1,"excited",1000,"charge",8,"hypercube",2,"spacetime",2000,"residue",200);
            var funded=plan(perm,"hypercube",1,stock);
            check(funded.feasible(),"Funded route survives optional producer: "+funded.result());
            PlanVerifier.verify(funded);
            check(!funded.patternTimes().containsKey("space"),"Unavailable optional source must execute zero times");
            var broad=SaveCycleReview.raw();broad.put("hypercube",64L);broad.put("residue",10000L);
            var efficient=plan(perm,"hypercube",1,broad);
            check(efficient.feasible(),"Funded coproduct plan missing");
            PlanVerifier.verify(efficient);
            check(efficient.patternTimes().get("time")==2,"Stop when spatial coproduct is sufficient: "+efficient.patternTimes());
            check(!efficient.patternTimes().containsKey("space"),"Don't run discarded source");
            var mixed=SaveCycleReview.a("hypercube",21,"anomaly",1,"plate",16,"temporal",0,"stellar",10000,"charge",80,"spacetime",20000,"residue",2000);
            var together=plan(perm,"spatial",15000,mixed);
            check(together.feasible(),"Both sources must remain available: "+together.result());
            PlanVerifier.verify(together);
            check(together.patternTimes().get("time")==20&&together.patternTimes().get("space")==1,"Joint products and both providers: "+together.patternTimes());
            System.out.println("ORDER "+a+b+c+" empty="+empty.result()+" funded="+funded.patternTimes()+" efficient="+efficient.patternTimes()+" mixed="+together.patternTimes());
        }
        var base=List.of(SaveCycleReview.r("base",SaveCycleReview.a("raw",1),SaveCycleReview.a("P",1)));
        var extended=new ArrayList<>(base);
        extended.add(SaveCycleReview.r("dead1",SaveCycleReview.a("C",1),SaveCycleReview.a("I",1)));
        extended.add(SaveCycleReview.r("dead2",SaveCycleReview.a("I",1),SaveCycleReview.a("C",1,"P",1)));
        for(boolean externalSeed:new boolean[]{false,true}) {
            var p=plan(extended,"P",5,Map.of("raw",5L));
            check(p.feasible(),"Empty optional siphon is not proof that all sources are blocked");
            PlanVerifier.verify(p);
        }
        System.out.println("COPRODUCT PASS assertions="+assertions);
    }
}
