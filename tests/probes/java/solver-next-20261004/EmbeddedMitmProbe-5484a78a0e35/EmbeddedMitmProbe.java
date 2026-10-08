package org.cgse.core;

import com.google.gson.*;
import java.math.BigInteger;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Read-only observation of the actual frozen main branch followed by an independent MITM call. */
public final class EmbeddedMitmProbe {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    @SuppressWarnings("unchecked")
    static IntegerCountBranch<String> root(IntegerCountSearch<String> search) throws Exception {
        for(String name : List.of("pending", "deferred", "dispatched")) {
            Field field = IntegerCountSearch.class.getDeclaredField(name); field.setAccessible(true);
            for(var branch : (Iterable<IntegerCountBranch<String>>) field.get(search))
                if(branch.current.isEmpty() && branch.auxiliaryMode == 0 && branch.compiled) return branch;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        var output = new ArrayList<Map<String,Object>>();
        for(var entry : JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray()) {
            var c = entry.getAsJsonObject(); String id = c.get("id").getAsString();
            if(args.length > 2 && !id.contains(args[2])) continue;
            int n = c.getAsJsonArray("lower").size(); var lo = new BigInteger[n]; var hi = new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
            var rows = new ArrayList<ExactLinearProgram.Constraint>();
            for(var item : c.getAsJsonArray("rows")) {var r=item.getAsJsonObject();var terms=new LinkedHashMap<Integer,BigInteger>();for(var term:r.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(term.getKey()),term.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,r.get("upper").getAsBigInteger()));}
            var e = new CountBenchmark.Embedding(rows,lo,hi);
            var budget = new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);
            try(var search = new IntegerCountSearch<>(new GraphCompiler<>(e.recipes),"target",1,e.stock,Map.of(),Set.of(),Set.of(),false,true,budget,System.nanoTime())) {
                IntegerCountBranch<String> branch = null;
                while(branch == null) {
                    if(search.step()) {if(search.paused())search.resume();else break;}
                    branch = root(search);
                }
                if(branch == null) throw new AssertionError("No compiled main root " + id);
                var viewRows = List.of(branch.linearConstraints, branch.reduction.rows());
                var viewLo = List.of(branch.lower, branch.reduction.lower());
                var viewHi = List.of(branch.upper, branch.reduction.upper());
                for(int mode=0;mode<2;mode++) {
                    var b = new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);
                    var result = new LinkedHashMap<String,Object>();result.put("id",id);result.put("view",mode==0?"actual-post-propagation":"actual-reduced");result.put("mainObservationWork",budget.nodes());
                    result.put("rows",viewRows.get(mode));result.put("lower",viewLo.get(mode));result.put("upper",viewHi.get(mode));
                    BigInteger[] counts;boolean impossible;
                    long started=System.nanoTime();
                    try(var mitm = new CountMeetInMiddle(viewRows.get(mode),viewLo.get(mode),viewHi.get(mode),b)) {
                        while(!mitm.step()){} counts=mitm.counts();impossible=mitm.infeasible();
                    }
                    result.put("mitmWork",b.nodes());result.put("mitmMilliseconds",(System.nanoTime()-started)/1e6);result.put("sat",counts!=null);result.put("infeasible",impossible);result.put("diagnostics",b.diagnostics());
                    if(counts!=null) {
                        CountBenchmark.verify(viewRows.get(mode),viewLo.get(mode),viewHi.get(mode),counts);
                        var restored=mode==0?counts:branch.reduction.expand(counts);
                        CountBenchmark.verify(branch.linearConstraints,branch.lower,branch.upper,restored);
                        CountBenchmark.verify(branch.model.constraints,branch.lower,branch.upper,restored);
                        var x=new BigInteger[n];for(int i=0;i<branch.model.recipes.size();i++){String recipe=branch.model.recipes.get(i).id();if(recipe.startsWith("var")){int j=Integer.parseInt(recipe.substring(3));x[j]=restored[i].add(lo[j]);}}
                        CountBenchmark.verify(rows,lo,hi,x);result.put("originalWitness",x);result.put("restoredRecipeCounts",restored);
                        try(var schedule=new CountSchedule<>(branch.model,restored,b)){while(!schedule.step()){}result.put("schedule",schedule.result().toString());if(schedule.result()!=CountSchedule.Result.WITNESS)throw new AssertionError("Non-executable exact witness "+id);}
                    }
                    result.put("reservedAfterMitmAndSchedule",b.reservedBytes());if(b.reservedBytes()!=0)throw new AssertionError("MITM/schedule leak");
                    output.add(result);System.out.println(id+" "+result.get("view")+" sat="+result.get("sat")+" work="+result.get("mitmWork")+" variables="+viewLo.get(mode).length+" rows="+viewRows.get(mode).size()+" schedule="+result.get("schedule"));
                }
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("Main leak");
        }
        Files.writeString(Path.of(args[1]),JSON.toJson(output));
    }
}
