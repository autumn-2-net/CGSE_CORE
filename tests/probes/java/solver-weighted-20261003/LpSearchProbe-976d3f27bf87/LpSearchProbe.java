package org.cgse.core;

import com.google.gson.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

public final class LpSearchProbe {
    static int assertions, admitted;
    static BigInteger b(long v){return BigInteger.valueOf(v);}
    static BigInteger[] fill(int n,int v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
    static void check(boolean v,String reason){assertions++;if(!v)throw new AssertionError(reason);}
    static PlanningBudget budget(long maximum){return new PlanningBudget(0,maximum,64L<<20,()->false,()->0L);}
    record Fixture(List<ExactLinearProgram.Constraint> rows,BigInteger[]low,BigInteger[]high){}
    static ExactLinearProgram.Constraint row(long rhs,long... values){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<values.length;i++)if(values[i]!=0)terms.put(i,b(values[i]));return new ExactLinearProgram.Constraint(terms,b(rhs));}
    static Fixture affine(){
        var rows=List.of(row(7,1,-2),row(-7,-1,2),row(1,0,0,1,0,0,0,1),row(-1,0,0,-1,0,0,0,-1),row(10,0,3,2,4,5,7),row(-8,0,-2,-3,-6,-4,-5));
        var low=fill(8,0);var high=fill(8,1);low[0]=b(7);high[0]=b(9);low[7]=high[7]=b(9);return new Fixture(rows,low,high);
    }
    static CountReduction compile(Fixture f,PlanningBudget budget){var r=new CountReduction(f.rows,f.low,f.high,budget);try{while(!r.step()){}return r;}catch(Throwable t){r.close();throw t;}}
    static void verify(Fixture f,BigInteger[] x){check(x!=null&&x.length==f.low.length,"counts dimension");for(int i=0;i<x.length;i++)check(x[i].compareTo(f.low[i])>=0&&x[i].compareTo(f.high[i])<=0,"original bound");for(var row:f.rows){var total=BigInteger.ZERO;for(var t:row.terms().entrySet())total=total.add(t.getValue().multiply(x[t.getKey()]));check(total.compareTo(row.upper())<=0,"original row "+row+" counts="+Arrays.toString(x));}}
    static void affineRecovery(){
        var f=affine();var budget=budget(20_000_000);CountLpSearch search;
        try(var reduction=compile(f,budget)){
            var c=reduction.coordinates();check(c.factors().contains(b(2)),"nonunit fixture lost");check(c.factors().contains(b(-1)),"negative fixture lost");check(c.offsets().contains(b(7))&&c.offsets().contains(b(9)),"offset fixture lost");
            search=CountLpSearch.create(reduction,8,budget);check(search!=null,"affine not admitted");
        }
        // Destroy the original compilation, then build an unrelated map before
        // resuming the retained solver. It must keep its own original snapshot.
        try(var changed=compile(new Fixture(List.of(row(0,1,0,-1),row(0,-1,0,1)),fill(8,0),fill(8,1)),budget);search){
            while(!search.step()){}verify(f,search.counts());check(!search.infeasible(),"affine false UNSAT");check(!search.retained(),"solved search retained");search.close();
        }
        check(budget.reservedBytes()==0,"affine leak");
    }
    static Fixture hard(Path path)throws Exception{
        for(var element:JsonParser.parseString(Files.readString(path)).getAsJsonArray()){
            var c=element.getAsJsonObject();if(!c.get("id").getAsString().contains("lseu-objective-1119"))continue;
            int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var e:c.getAsJsonArray("rows")){var j=e.getAsJsonObject();var terms=new TreeMap<Integer,BigInteger>();for(var t:j.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(t.getKey()),t.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,j.get("upper").getAsBigInteger()));}return new Fixture(rows,lo,hi);
        }throw new AssertionError("missing hard fixture");
    }
    static RecipeCountModel<String> model(PlanningBudget budget){return RecipeCountModel.create(new GraphCompiler<>(List.of(new GraphRecipe<>("one","one",List.of(new GraphRecipe.Slot<>("fuel",1)),Map.of("target",1L)))),"target",1,Map.of("fuel",1L),Map.of(),Set.of(),Set.of(),false,budget);}
    static void handoff(Fixture hard){
        var budget=budget(650000);
        try(var reduction=compile(hard,budget);var lp=CountLpSearch.create(reduction,hard.low.length,budget)){
            check(lp!=null,"hard not admitted");long start=budget.nodes(),remaining=budget.remainingWork();
            while(!lp.step()){}long spent=budget.nodes()-start;
            check(lp.retained()&&lp.counts()==null&&!lp.infeasible(),"partial LP falsely closed");check(spent>=remaining-remaining/4,"premature first handoff");check(budget.remainingWork()>remaining/8,"first LP swallowed fallback allowance");
            long before=budget.nodes();check(lp.step(),"paused wrapper not idempotent");check(budget.nodes()==before,"paused wrapper consumed work");
            lp.resume(4096);while(!lp.step()){}check(lp.retained()&&!lp.infeasible(),"short resume falsely closed");
            System.out.println("HANDOFF firstRemaining="+remaining+" firstSpent="+spent+" remaining="+budget.remainingWork()+" shortResumeSpent="+(budget.nodes()-before));
            try(var model=model(budget);var execution=new CountExecution<>(model,budget);var branch=new IntegerCountBranch<>(model,execution,"target",1,model.stock,Map.of(),Set.of(),false,false,budget,0,List.of())){
                branch.lpSearch=lp;branch.modelViews=CountModelViews.create(hard.rows,hard.low,hard.high,budget);check(branch.modelViews!=null,"views not admitted");branch.viewSearch=new CountViewSearch(branch.modelViews,budget);
                branch.state=IntegerCountBranch.State.UNRESOLVED;
                check(branch.resume()&&branch.lpActive&&!branch.preferLpResume,"first resume not LP");
                // Simulate the completed LP quantum returning to coordinator.
                branch.lpActive=false;branch.state=IntegerCountBranch.State.UNRESOLVED;
                check(branch.resume()&&branch.viewStage==4&&branch.preferLpResume,"second resume not view");
                branch.viewStage=0;branch.state=IntegerCountBranch.State.UNRESOLVED;
                check(branch.resume()&&branch.lpActive&&!branch.preferLpResume,"third resume not LP");
                branch.lpSearch=null; // Outer try-with owns this specific instance.
            }
        }check(budget.reservedBytes()==0,"handoff leak");
    }
    static void lifecycle(){
        var f=affine();
        for(long available:new long[]{0,1024,4096,8192,16384,32768,65536,262144,1048576}){
            var budget=budget(20_000_000);try(var reduction=compile(f,budget)){
                long baseline=budget.reservedBytes(),hold=budget.availableBytes()-available;budget.reserve(hold);
                try(var search=CountLpSearch.create(reduction,8,budget)){if(search!=null){admitted++;while(!search.step()){}verify(f,search.counts());}}
                check(budget.reservedBytes()==baseline+hold,"admission leak available="+available);budget.release(hold);
            }check(budget.reservedBytes()==0,"admission final leak");
        }
        for(int after:new int[]{0,1,2,10,30,100,500,2000}){
            var enabled=new AtomicBoolean();var calls=new AtomicInteger();var budget=new PlanningBudget(0,20_000_000,64L<<20,()->enabled.get()&&calls.incrementAndGet()>after,()->0L);
            try(var reduction=compile(f,budget)){long held=budget.reservedBytes();enabled.set(true);try(var search=CountLpSearch.create(reduction,8,budget)){if(search!=null)while(!search.step()){};}catch(CancellationException expected){}check(budget.reservedBytes()==held,"cancel create/step leak "+after);}check(budget.reservedBytes()==0,"cancel all leak");
        }
        var budget=budget(20_000_000);try(var reduction=compile(f,budget);var search=CountLpSearch.create(reduction,8,budget)){check(search!=null,"cancel setup");long held=budget.reservedBytes();budget.cancel();try{search.step();throw new AssertionError("cancel ignored");}catch(CancellationException expected){}check(budget.reservedBytes()==held,"cancel mutated ownership");}check(budget.reservedBytes()==0,"cancel after create leak");
        budget=budget(20_000_000);try(var reduction=compile(f,budget)){long held=budget.reservedBytes();try{CountLpSearch.create(reduction,99,budget);throw new AssertionError("bad coordinate length accepted");}catch(IllegalArgumentException expected){}check(budget.reservedBytes()==held,"coordinate mismatch leak");}check(budget.reservedBytes()==0,"coordinate final leak");
    }
    static void callAdvance(IntegerCountBranch<?> branch)throws Exception{Method m=IntegerCountBranch.class.getDeclaredMethod("advance");m.setAccessible(true);try{m.invoke(branch);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}
    static void reject(boolean assembly)throws Exception{
        var budget=budget(20_000_000);var recipe=new GraphRecipe<String>("stuck","stuck",List.of(new GraphRecipe.Slot<>("target",1)),Map.of("target",1L));
        try(var model=RecipeCountModel.create(new GraphCompiler<>(List.of(recipe)),"target",1,Map.of("target",1L),Map.of(),Set.of(),Set.of(),true,budget);var execution=new CountExecution<>(model,budget);var branch=new IntegerCountBranch<>(model,execution,"target",1,model.stock,Map.of(),Set.of(),false,true,budget,0,List.of())){
            branch.reduction=compile(new Fixture(model.constraints,fill(1,0),fill(1,2)),budget);branch.initialized=branch.compiled=true;branch.linearConstraints=new ArrayList<>(model.constraints);branch.lower=fill(1,0);branch.upper=fill(1,2);branch.viewCandidateStage=2;branch.counts=fill(1,1);
            if(assembly)branch.assembling=new AllocationSearch.Candidate<>(new PlanStep.Batch("stuck",1),Map.of("stuck",recipe),"target",1,model.stock,Map.of(),Set.of(),false,true,false,budget,0);
            else {branch.counts=fill(1,2);branch.scheduling=new CountSchedule<>(model,branch.counts,budget);branch.schedulingWork=8192;/* Force the existing speculative handoff boundary. */}
            if(assembly){for(int i=0;i<1000&&branch.assembling!=null;i++)callAdvance(branch);}
            else branch.run(1,List.of(),List.of(),List.of(),null,()->false);
            check(branch.assembling==null&&branch.scheduling==null&&branch.verifying==null&&branch.program==null,"rejected candidate retained "+assembly);
            check(branch.counts==null&&branch.plan==null&&branch.viewCandidateStage==0,"rejected candidate published "+assembly);
            check(branch.congruence!=null&&branch.state==IntegerCountBranch.State.OPEN,"old path not resumed "+assembly);
            check(branch.lpSearch==null,"exhausted LP repeated "+assembly);
        }check(budget.reservedBytes()==0,"rejection leak "+assembly);
    }
    public static void main(String[] args)throws Exception{affineRecovery();handoff(hard(Path.of(args[0])));lifecycle();reject(true);reject(false);System.out.println("LpSearchProbe assertions="+assertions+" lifecycleAdmitted="+admitted+" falseSuccess=0 falseInfeasible=0 ownedLeaks=0");}
}
