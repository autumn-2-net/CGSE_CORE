import org.cgse.core.*;
import java.util.*;
import java.math.BigInteger;

public class DeferredSupplyReview extends DumpRuntimeReplay {
    static class Machine implements GraphJobRuntime.Adapter<String> {
        GraphJobRuntime<String> runtime;
        final GraphPlan<String> plan;
        final int mode;
        final Map<String,BigInteger> provided=new HashMap<>(), in=new HashMap<>(), out=new HashMap<>(), delivered=new HashMap<>(), refunded=new HashMap<>(), runs=new HashMap<>();
        final List<Map<String,Long>> flights=new ArrayList<>();
        Machine(GraphPlan<String> plan,int mode) {
            this.plan=plan;this.mode=mode;
            Map<String,BigInteger> later=new LinkedHashMap<>();
            plan.initialExact().forEach((k,v)->{if(v.compareTo(MAX)>0)later.put(k,v.subtract(MAX));});
            runtime=new GraphJobRuntime<>(plan,Map.of(),plan.initial(),later);
        }
        public long capacity(GraphRecipe<String> recipe,long wanted){return mode==2?Math.min(wanted,Long.MAX_VALUE/101):wanted;}
        public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe,long n,Map<String,Long> inputs){
            check(inputs.equals(recipe.dispatchInputs(n)),"escrow");inputs.forEach((k,v)->add(in,k,v));add(runs,recipe.id(),n);
            Map<String,Long> outputs=new LinkedHashMap<>();recipe.executionOutputs().forEach((k,v)->{long count=Math.multiplyExact(v,n);outputs.put(k,count);add(out,k,count);});
            if(mode==0)outputs.forEach((k,v)->check(runtime.accept(k,v,false)==v,"synchronous return"));else flights.add(outputs);
            return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(String k,long n){long count=mode==2?Math.min(n,Long.MAX_VALUE/13):n;add(delivered,k,count);return count;}
        public long refund(String k,long n){add(refunded,k,n);return n;}
        void feed(){for(var e:plan.initialExact().entrySet()){
            long owed=ExactAmounts.capped(e.getValue().subtract(provided.getOrDefault(e.getKey(),BigInteger.ZERO)));
            long offer=Math.min(owed,mode==2?Long.MAX_VALUE/17:Long.MAX_VALUE);
            long accepted=runtime.accept(e.getKey(),offer,false);add(provided,e.getKey(),accepted);
        }}
        void returns(){for(var f:flights){for(var e:new ArrayList<>(f.entrySet())){
            long n=runtime.accept(e.getKey(),e.getValue(),false);check(n==e.getValue(),"machine return refused "+e);
            f.remove(e.getKey());
        }}flights.removeIf(Map::isEmpty);}
        void conserved(){
            Set<String> keys=new HashSet<>(plan.initialExact().keySet());keys.addAll(out.keySet());keys.addAll(in.keySet());
            for(String k:keys){BigInteger total=provided.getOrDefault(k,BigInteger.ZERO).add(out.getOrDefault(k,BigInteger.ZERO)).subtract(in.getOrDefault(k,BigInteger.ZERO));
                BigInteger physical=BigInteger.valueOf(runtime.held(k)).add(delivered.getOrDefault(k,BigInteger.ZERO)).add(refunded.getOrDefault(k,BigInteger.ZERO));
                for(var f:flights)physical=physical.add(BigInteger.valueOf(f.getOrDefault(k,0L)));
                check(total.equals(physical),"conservation "+k+" "+total+" != "+physical);
                check(BigInteger.valueOf(runtime.held(k)).add(BigInteger.valueOf(runtime.waiting(k))).compareTo(MAX)<=0,"headroom "+k);
            }
        }
        void execute(String name){int t;long last=0;
            for(t=0;t<10000&&!runtime.finished();t++){
                long version=runtime.version();feed();runtime.tick(this,t,128);if(mode>0)returns();
                if(t%7==0){conserved();if(mode>0)runtime=new GraphJobRuntime<>(runtime.snapshot());}
                if(version!=runtime.version())last=t;
                if(runtime.state()==GraphJobRuntime.State.NEEDS_ATTENTION||t-last>80)break;
            }
            conserved();
            check(runtime.state()==GraphJobRuntime.State.COMPLETED,name+" mode="+mode+" stalled: "+runtime.state()+" "+runtime.reason()+" pendingKeys="+runtime.pendingRuns().size());
            check(delivered.get(plan.target()).equals(BigInteger.valueOf(plan.amount())),"delivery exact");
            check(runs.equals(plan.patternTimesExact()),"runs exact");
            for(var e:plan.initialExact().entrySet())check(provided.getOrDefault(e.getKey(),BigInteger.ZERO).equals(e.getValue()),"initial supply exact");
            System.out.println("PASS "+name+" mode="+mode+" ticks="+t+" keys="+plan.initialExact().size());
        }
    }
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static GraphPlan<String> plan(String target,long n,List<GraphRecipe<String>> rs,PlanStep program,Map<String,? extends Number> initial,Map<String,Long> seeds){
        Map<String,GraphRecipe<String>> byId=new LinkedHashMap<>();rs.forEach(r->byId.put(r.id(),r));
        return new GraphPlan<>(target,n,true,program,byId,initial,seeds,Map.of(),GraphPlan.Result.FEASIBLE,0,0);
    }
    public static void main(String[] args)throws Exception{
        var raw=r("raw",Map.of("R",2L),Map.of("P",1L));
        var basic=plan("P",Long.MAX_VALUE,List.of(raw),new PlanStep.Batch("raw",Long.MAX_VALUE),Map.of("R",MAX.multiply(BigInteger.TWO)),Map.of());
        for(int mode=0;mode<3;mode++)new Machine(basic,mode).execute("two_raw_long");
        var loop=r("loop",Map.of("C",1L,"R",3L),Map.of("C",1L,"P",2L));long n=Long.MAX_VALUE/2;
        var cyclic=plan("P",2*n,List.of(loop),new PlanStep.Batch("loop",n),Map.of("R",BigInteger.valueOf(n).multiply(BigInteger.valueOf(3)),"C",BigInteger.valueOf(n)),Map.of("C",1L));
        for(int mode=0;mode<3;mode++)new Machine(cyclic,mode).execute("returned_catalyst_long");
        var mixed=plan("P",Long.MAX_VALUE,List.of(r("make",Map.of("X",1L),Map.of("R",2L)),raw),new PlanStep.Sequence(List.of(new PlanStep.Batch("make",1),new PlanStep.Batch("raw",Long.MAX_VALUE))),Map.of("X",BigInteger.ONE,"R",MAX.multiply(BigInteger.TWO).subtract(BigInteger.TWO)),Map.of());
        for(int mode=0;mode<3;mode++)new Machine(mixed,mode).execute("supply_also_produced");
        var cancelled=new Machine(basic,1);cancelled.feed();var before=cancelled.runtime.owned();cancelled.runtime.cancel();for(int t=0;t<10&&!cancelled.runtime.finished();t++)cancelled.runtime.tick(cancelled,t,64);cancelled.conserved();
        check(cancelled.runtime.state()==GraphJobRuntime.State.CANCELLED&&cancelled.runtime.snapshot().deferredExternal().isEmpty(),"cancel forgot unreceived supply");
        check(cancelled.refunded.get("R").equals(BigInteger.valueOf(before.get("R"))),"cancel refunded fictitious supply");
        boolean rejected=false;try{new GraphJobRuntime<>(basic,Map.of("R",Long.MAX_VALUE),Map.of());}catch(IllegalArgumentException e){rejected=true;}check(rejected,"truncated ownership accepted");
        load(".local/ae-dumps/sky2-all.json");String target=labels.entrySet().stream().filter(e->e.getValue().equals("kubejs:hypercube")).findFirst().orElseThrow().getKey();
        var budget=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
        var work=new GraphPlanningWork<>(compiler(target),target,Long.MAX_VALUE,stock,external,Map.of(),true,true,budget).catalysts(CatalystPolicy.MINIMAL);
        while(!work.step()){}var missing=work.result();
        check(!missing.missingExact().isEmpty(),"real dump expected missing preview");
        var funded=new GraphPlan<>(target,missing.amount(),true,missing.steps(),missing.recipes(),missing.initialExact(),missing.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        System.out.println("DUMP target="+target+" result="+missing.result()+" recipes="+missing.recipes().size()+" above_long="+missing.initialExact().entrySet().stream().filter(e->e.getValue().compareTo(MAX)>0).toList());
        for(int mode=0;mode<3;mode++) {
            var machine=new Machine(funded,mode);
            for(int t=0;t<160;t++) {
                machine.feed();machine.runtime.tick(machine,t,128);if(mode>0)machine.returns();machine.conserved();
                if(t%9==0)machine.runtime=new GraphJobRuntime<>(machine.runtime.snapshot());
            }
            check(machine.runtime.state()==GraphJobRuntime.State.RUNNING&&!machine.runs.isEmpty(),"real dump execution failed");
            System.out.println("PASS real_hypercube_long prefix mode="+mode+" ticks=160 dispatchedRecipes="+machine.runs.size()+" deferredKeys="+machine.runtime.snapshot().deferredExternal().size()+" exact conservation; not a full long completion");
        }
        System.out.println("PASS checks="+checks);
    }
}
