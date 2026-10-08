package org.cgse.core;
import com.google.gson.*;
import java.math.BigInteger;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

public class EmbeddedJumpProbe {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    @SuppressWarnings("unchecked")
    static IntegerCountBranch<String> root(IntegerCountSearch<String> search)throws Exception{
        for(String name:List.of("pending","deferred","dispatched")){
            Field f=IntegerCountSearch.class.getDeclaredField(name);f.setAccessible(true);
            for(var branch:(Iterable<IntegerCountBranch<String>>)f.get(search))if(branch.current.isEmpty()&&branch.auxiliaryMode==0&&branch.compiled&&branch.modelViews!=null)return branch;
        }return null;
    }
    public static void main(String[] args)throws Exception{
        List<Map<String,Object>> output=new ArrayList<>();
        for(var item:JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray()){
            var c=item.getAsJsonObject();String id=c.get("id").getAsString();if(!id.equals("scip/p0548-objective-12467"))continue;
            int variation=120,n=c.getAsJsonArray("lower").size();BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];
            List<Integer> order=new ArrayList<>();for(int i=0;i<n;i++)order.add(i);Collections.shuffle(order,new Random(7001+variation));
            int[] remap=new int[n];for(int i=0;i<n;i++)remap[order.get(i)]=i;
            for(int i=0;i<n;i++){lo[remap[i]]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[remap[i]]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
            List<ExactLinearProgram.Constraint> original=new ArrayList<>();
            for(var element:c.getAsJsonArray("rows")){var r=element.getAsJsonObject();Map<Integer,BigInteger> terms=new LinkedHashMap<>();for(var t:r.getAsJsonObject("terms").entrySet())terms.put(remap[Integer.parseInt(t.getKey())],t.getValue().getAsBigInteger());original.add(new ExactLinearProgram.Constraint(terms,r.get("upper").getAsBigInteger()));}
            Collections.shuffle(original,new Random(8009+variation));
            var e=new CountBenchmark.Embedding(original,lo,hi);var budget=new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);
            try(var search=new IntegerCountSearch<>(new GraphCompiler<>(e.recipes),"target",1,e.stock,Map.of(),Set.of(),Set.of(),false,true,budget,System.nanoTime())){
                IntegerCountBranch<String> branch=null;
                while(branch==null){if(search.step()){if(search.paused())search.resume();else break;}branch=root(search);}
                if(branch==null)throw new AssertionError("No compiled root");
                branch.modelViews.compileLight();branch.modelViews.addReduced(branch.reduction);
                for(var view:branch.modelViews.available()){
                    var b=new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);
                    Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("variation",variation);result.put("view",view.name());result.put("variables",view.lower().length);result.put("rows",view.rows().size());result.put("observedWork",budget.nodes());
                    BigInteger[] counts=null;String status="UNKNOWN";long start=System.nanoTime();
                    try(var jump=new CountJump(view.rows(),view.lower(),view.upper(),b,32768).retained()){
                        int rounds=0;do{while(!jump.step()){}counts=jump.counts();if(counts!=null||!jump.paused()||b.remainingWork()<1024)break;jump.resume(Math.min(32768,b.remainingWork()));}while(++rounds<10000);
                        if(counts!=null)status="SAT";
                    }catch(PlanningBudget.Exhausted limit){status="LIMIT";}
                    result.put("status",status);result.put("work",b.nodes());result.put("milliseconds",(System.nanoTime()-start)/1e6);result.put("diagnostics",b.diagnostics());
                    if(counts!=null){
                        CountBenchmark.verify(view.rows(),view.lower(),view.upper(),counts);BigInteger[] restored=view.restore(counts);
                        CountBenchmark.verify(branch.linearConstraints,branch.lower,branch.upper,restored);CountBenchmark.verify(branch.model.constraints,branch.lower,branch.upper,restored);
                        BigInteger[] x=lo.clone();for(int i=0;i<branch.model.recipes.size();i++){String recipe=branch.model.recipes.get(i).id();if(recipe.startsWith("var")){int j=Integer.parseInt(recipe.substring(3));x[j]=restored[i].add(lo[j]);}}
                        CountBenchmark.verify(original,lo,hi,x);
                        try(var schedule=new CountSchedule<>(branch.model,restored,b)){while(!schedule.step()){}result.put("schedule",schedule.result().toString());if(schedule.result()!=CountSchedule.Result.WITNESS)throw new AssertionError("Not executable");}
                    }
                    result.put("reservedAfterClose",b.reservedBytes());if(b.reservedBytes()!=0)throw new AssertionError("Direct leak");
                    output.add(result);System.out.println(view.name()+" variables="+view.lower().length+" rows="+view.rows().size()+" status="+status+" work="+result.get("work")+" schedule="+result.get("schedule"));
                }
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("Main leak");
        }
        Files.writeString(Path.of(args[1]),JSON.toJson(output));
    }
}
