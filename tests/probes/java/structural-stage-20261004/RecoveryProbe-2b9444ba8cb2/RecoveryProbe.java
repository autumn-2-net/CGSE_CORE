package org.cgse.core;
import java.util.*;import java.math.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.concurrent.atomic.*;import com.google.gson.Gson;
public final class RecoveryProbe {
    static long checks,coldWork,warmWork;static int reuses,scopes,requests;
    static BigInteger bi(long n){return BigInteger.valueOf(n);}static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static Object template(CountRecovery<?> r)throws Exception{var f=CountRecovery.class.getDeclaredField("template");f.setAccessible(true);return f.get(r);}
    static void inspect(RecipeCountModel<String> model,CountRecovery<?> recovery)throws Exception{
        var f=CountRecovery.class.getDeclaredField("bodies");f.setAccessible(true);var bodies=(Map<String,PlanStep>)f.get(recovery);
        var t=(CountRecoveryTemplates.Template<String>)template(recovery);if(t==null)return;
        var original=new LinkedHashMap<String,GraphRecipe<String>>();model.recipes.forEach(r->original.put(r.id(),r));
        for(var macro:t.recipes)if(bodies.containsKey(macro.id()))try(var summary=new SummaryComputation<>(bodies.get(macro.id()),original,model.budget)){while(!summary.step()){}for(var k:summary.result().keys()){check(summary.result().required(k).equals(bi(macro.inputs().getOrDefault(k,0L))),"macro prefix changed");check(summary.result().delta(k).equals(bi(macro.outputs().getOrDefault(k,0L)).subtract(bi(macro.inputs().getOrDefault(k,0L)))),"macro delta changed");}}
    }
    static Object compile(GraphCompiler<String> compiler,String target,long amount,Map<String,Long> stock,Map<String,Long> seeds,Set<String> external,Set<String> excluded,boolean expected)throws Exception{
        var b=budget();Object value;long work;
        try(var model=RecipeCountModel.create(compiler,target,amount,stock,seeds,external,excluded,true,b);var execution=new CountExecution<>(model,b);var branch=new IntegerCountBranch<>(model,execution,target,amount,stock,seeds,external,false,true,b,System.nanoTime(),List.of())){
            long start=b.nodes();try(var recovery=new CountRecovery<>(branch)){work=b.nodes()-start;value=template(recovery);if(expected){check(b.diagnostics().contains("count_recovery_cache"),"no reuse "+b.diagnostics());reuses++;warmWork+=work;}else coldWork+=work;inspect(model,recovery);}
        }check(b.reservedBytes()==0,"recovery lifecycle");return value;
    }
    static List<GraphRecipe<String>> chain(int seed){var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<4+seed%12;i++)rs.add(recipe("r"+i,Map.of("k"+i,2L+seed%3),Map.of("k"+(i+1),3L+seed%2)));return rs;}
    static void run()throws Exception{
        for(int seed=0;seed<120;seed++){var rs=chain(seed);String target="k"+rs.size();var compiler=new GraphCompiler<>(rs);var stock=Map.of("k0",Long.MAX_VALUE);
            compile(compiler,target,1,stock,Map.of(),Set.of(),Set.of(),false);Object original=compile(compiler,target,3,stock,Map.of(),Set.of(),Set.of(),false);check(original!=null,"completed template absent");check(compile(compiler,target,1000,Map.of("k0",9L,"k1",4L),Map.of(),Set.of(),Set.of(),true)==original,"quantity/inventory changed structural identity");
            compile(compiler,target,1,stock,Map.of("k1",1L),Set.of(),Set.of(),false);compile(compiler,target,1,stock,Map.of(),Set.of("k1"),Set.of(),false);compile(compiler,target,1,stock,Map.of(),Set.of(),Set.of("r0"),false);scopes+=3;
            check(compile(compiler,target,1,stock,Map.of(),Set.of(),Set.of(),true)==original,"scope evicted original prematurely");
        }
        // The review's non-divisible intermediate example must keep the primitive solution.
        var rs=List.of(recipe("start",Map.of("A",2L),Map.of("X",3L)),recipe("finish",Map.of("X",2L),Map.of("B",1L)));var c=new GraphCompiler<>(rs);
        for(int pass=0;pass<8;pass++){var b=budget();var stock=Map.of("A",pass%2==0?2L:4L);try(var work=new GraphPlanningWork<>(c,"B",1,stock,false,true,b)){while(!work.step()){}var plan=work.result();check(plan.feasible(),"primitive remainder lost "+plan.result());PlanVerifier.verifyRuntimeInventory(plan);requests++;}}
        // Interrupt discovery at many budget checkpoints, then retry the same catalog.
        for(int limit=1;limit<=100;limit++){var compiler=new GraphCompiler<>(chain(9));var calls=new AtomicInteger();int stop=limit*11;var b=new PlanningBudget(0,20_000_000,128L<<20,()->calls.incrementAndGet()>stop,System::nanoTime);
            try(var model=RecipeCountModel.create(compiler,"k13",1,Map.of("k0",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),true,b);var execution=new CountExecution<>(model,b);var branch=new IntegerCountBranch<>(model,execution,"k13",1,model.stock,Map.of(),Set.of(),false,true,b,System.nanoTime(),List.of());var recovery=new CountRecovery<>(branch)){}catch(java.util.concurrent.CancellationException|PlanningBudget.Exhausted expected){}
            check(b.reservedBytes()==0,"cancelled discovery leaked");compile(compiler,"k13",1,Map.of("k0",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),false);
        }
    }
    public static void main(String[] args)throws Exception{run();var report=Map.of("assertions",checks,"reuseCases",reuses,"scopeCases",scopes,"primitiveRequests",requests,"coldPreparationWork",coldWork,"warmPreparationWork",warmWork);Files.writeString(Path.of(args[0],"recovery-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
