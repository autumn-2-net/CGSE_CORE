import org.cgse.core.*;
import java.util.*;

public class DependencyScaleTest {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    public static void main(String[]args) {
        for(int n:new int[]{4,100,1000,2000}) {
            var recipes=new LinkedHashMap<String,GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
            var steps=new ArrayList<PlanStep>();
            for(int i=0;i<n;i++) {
                recipes.put("a"+i,r("a"+i,Map.of("seed"+i,1L,"raw"+i,1L),Map.of("mid"+i,1L)));
                recipes.put("b"+i,r("b"+i,Map.of("mid"+i,1L),Map.of("seed"+i,1L,"A"+i,1L)));
                steps.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a"+i,1),new PlanStep.Batch("b"+i,1))),1_000_000_000_000L));
                stock.put("seed"+i,1L);stock.put("raw"+i,1_000_000_000_000L);
            }
            recipes.put("independent",r("independent",Map.of("other",1L),Map.of("P",1L)));
            stock.put("other",1L);steps.add(new PlanStep.Batch("independent",1));
            var plan=new GraphPlan<>("P",1,false,new PlanStep.Sequence(steps),recipes,stock,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
            execute(plan,n);
        }
        var recipes=new LinkedHashMap<String,GraphRecipe<String>>();
        recipes.put("a",r("a",Map.of("seed",1L,"raw",1L),Map.of("mid",1L)));
        recipes.put("b",r("b",Map.of("mid",1L),Map.of("seed",1L,"A",1L)));
        recipes.put("independent",r("independent",Map.of("other",1L),Map.of("P",1L)));
        recipes.put("zero",r("zero",Map.of("never",1L),Map.of("unused",1L)));
        PlanStep shared=new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1)));
        for(int i=0;i<55;i++) shared=new PlanStep.Sequence(List.of(shared,shared));
        PlanStep nested=new PlanStep.Sequence(List.of(shared,new PlanStep.Batch("independent",1),new PlanStep.Repeat(new PlanStep.Batch("zero",1),0)));
        for(int i=0;i<10000;i++) nested=new PlanStep.Sequence(List.of(nested));
        var plan=new GraphPlan<>("P",1,false,nested,recipes,Map.of("seed",1L,"raw",1L<<55,"other",1L),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        execute(plan,1);
    }
    static void execute(GraphPlan<String> plan,int lanes) {
        long start=System.nanoTime();var runtime=new GraphJobRuntime<>(plan,plan.initial(),Map.of());
        double init=(System.nanoTime()-start)/1e6;
        long[] pushed={0};
        var adapter=new GraphJobRuntime.Adapter<String>() {
            public long capacity(GraphRecipe<String> r,long n){return r.id().equals("independent")?n:0;}
            public GraphJobRuntime.Outcome push(GraphRecipe<String> r,long n,Map<String,Long> in){pushed[0]+=n;return GraphJobRuntime.Outcome.ACCEPTED;}
            public long deliver(String k,long n){return n;}
            public long refund(String k,long n){return n;}
        };
        int ticks=0;double max=0;
        for(;ticks<2000&&pushed[0]==0;ticks++){long now=System.nanoTime();runtime.tick(adapter,ticks,8);max=Math.max(max,(System.nanoTime()-now)/1e6);}
        if(pushed[0]!=1)throw new AssertionError("Starved independent branch among "+lanes+" blocked cycles");
        var counts=PlanCountComputation.of(runtime.snapshot().pendingSteps());
        for(int i=0;i<5;i++)runtime=new GraphJobRuntime<>(runtime.snapshot());
        if(!counts.equals(PlanCountComputation.of(runtime.snapshot().pendingSteps())))throw new AssertionError("Compressed shared repeat reload lost counts");
        System.out.printf(Locale.ROOT,"lanes=%d init_ms=%.2f ticks=%d max_tick_ms=%.2f exact_remaining=PASS%n",lanes,init,ticks,max);
    }
}
