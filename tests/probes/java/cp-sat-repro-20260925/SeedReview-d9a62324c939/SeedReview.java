import java.util.*;
import java.math.BigInteger;
import org.cgse.core.*;

public class SeedReview {
    static PlanningBudget budget(){return new PlanningBudget(5000,8_000_000,128L<<20,()->false,System::nanoTime);}
    static String label(String k){return DumpReplay.labels.getOrDefault(k,k);}
    static Map<String,String> labelled(Map<String,?> in){var out=new TreeMap<String,String>();in.forEach((k,v)->out.put(label(k),v.toString()));return out;}
    static GraphPlan<String> plan(GraphCompiler<String> c,Map<String,Long> stock,long amount){
        var b=budget();var work=new GraphPlanningWork<>(c,"k747",amount,stock,DumpReplay.external,Map.of(),true,true,b).catalysts(CatalystPolicy.MINIMAL);
        while(!work.step()){}var p=work.result();
        System.out.println("PLAN amount="+amount+" status="+p.result()+" seeds="+labelled(p.seeds())+" trace="+b.diagnostics());
        return p;
    }
    public static void main(String[] args)throws Exception {
        DumpReplay.main(new String[]{".local/ae-dumps/sky2-all.json",".local/cp-sat-repro-20260925/seed-empty.jsonl","1","__none__"});
        var c=DumpReplay.compiler("k747");var compiled=c.compile("k747",Map.of(),Set.of(),budget());
        var p=plan(c,DumpReplay.stock,Long.MAX_VALUE);
        var falselyMarked=new TreeMap<String,String>();
        for(var e:p.missingExact().entrySet())if(!p.seeds().containsKey(e.getKey())&&p.recipes().values().stream().anyMatch(r->r.outputs().containsKey(e.getKey())))falselyMarked.put(label(e.getKey()),e.getValue().toString());
        System.out.println("UI_MISSING_PRODUCED_BUT_NOT_SEEDS count="+falselyMarked.size()+" entries="+falselyMarked);
        for(var region:compiled.regions())if(region.cyclic()&&region.recipes().size()>6){
            System.out.println("LARGE_SCC recipes="+region.recipes().size());
            var regionIds=new HashSet<String>();region.recipes().forEach(r->regionIds.add(r.id()));
            for(var seed:p.seeds().entrySet()) {
                System.out.println("SEED "+label(seed.getKey())+" reserve="+seed.getValue()+" stock="+DumpReplay.stock.getOrDefault(seed.getKey(),0L));
                for(var r:c.producers(seed.getKey()))System.out.println("SOURCE region="+regionIds.contains(r.id())+" in="+labelled(r.inputs())+" out="+labelled(r.outputs()));
            }
        }
        plan(c,DumpReplay.stock,1);
        var r=new GraphRecipe<String>("work","work",List.of(new GraphRecipe.Slot<>("A",1),new GraphRecipe.Slot<>("C",2)),Map.of("A",1L,"C",1L,"P",1L));
        var tiny=new GraphPlanner<>(new GraphCompiler<>(List.of(r))).plan("P",1,Map.of(),true,true,budget());
        System.out.println("TINY result="+tiny.result()+" initial="+tiny.initialExact()+" seeds="+tiny.seeds()+" missing="+tiny.missingExact());
        if(!tiny.seeds().equals(Map.of("A",1L))||!tiny.missingExact().containsKey("C"))throw new AssertionError("Fixture changed");
    }
}
