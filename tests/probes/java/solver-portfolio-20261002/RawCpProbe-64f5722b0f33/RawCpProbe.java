package org.cgse.core;

import com.google.gson.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.google.ortools.linearsolver.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Local diagnostic, single-threaded native comparisons on strictly acyclic fixtures. */
public final class RawCpProbe {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    static Set<String> keys(QuantitySweep.Catalog c, Map<String,Long> stock) {
        Set<String> keys = new LinkedHashSet<>(stock.keySet());
        for (var r : c.recipes()) { keys.addAll(r.inputs().keySet()); keys.addAll(r.outputs().keySet()); }
        return keys;
    }
    static void replay(QuantitySweep.Catalog c, JsonObject g, long amount, Map<String,BigInteger> counts) {
        Map<String,BigInteger> stock = new LinkedHashMap<>();
        QuantitySweep.amounts(g.getAsJsonObject("stock")).forEach((k,v)->stock.put(k,BigInteger.valueOf(v)));
        var ids = new HashMap<String,GraphRecipe<String>>(); c.recipes().forEach(r->ids.put(r.id(),r));
        for (var id : g.getAsJsonArray("topological_order")) {
            var r = ids.get(id.getAsString()); var n = counts.getOrDefault(r.id(),BigInteger.ZERO);
            if (n.signum()<0) throw new AssertionError("negative count");
            r.inputs().forEach((k,v)->{
                var left = stock.getOrDefault(k,BigInteger.ZERO).subtract(BigInteger.valueOf(v).multiply(n));
                if (left.signum()<0) throw new AssertionError("Unfunded prefix " + r.id() + " " + k);
                stock.put(k,left);
            });
            r.outputs().forEach((k,v)->stock.merge(k,BigInteger.valueOf(v).multiply(n),BigInteger::add));
        }
        String target = g.get("target").getAsString();
        var initialTarget = new BigInteger(g.getAsJsonObject("stock").has(target) ? g.getAsJsonObject("stock").get(target).getAsString() : "0");
        if (stock.getOrDefault(target,BigInteger.ZERO).compareTo(initialTarget.add(BigInteger.valueOf(amount)))<0)
            throw new AssertionError("Target shortfall");
    }
    static Map<String,Object> nativeSolve(String engine, QuantitySweep.Catalog c, JsonObject g, long amount) {
        long start = System.nanoTime();
        var stock = QuantitySweep.amounts(g.getAsJsonObject("stock"));
        var upper = QuantitySweep.amounts(g.getAsJsonObject("count_upper"));
        String target = g.get("target").getAsString();
        Map<String,BigInteger> counts = new LinkedHashMap<>();
        Map<String,Object> out = new LinkedHashMap<>();
        String status; long built, end;
        if (engine.equals("cp_sat")) {
            CpModel model = new CpModel(); IntVar[] x = new IntVar[c.recipes().size()];
            for (int i=0;i<x.length;i++) x[i] = model.newIntVar(0,upper.get(c.recipes().get(i).id()),"x"+i);
            for (String k:keys(c,stock)) {
                var row=LinearExpr.newBuilder();
                for (int i=0;i<x.length;i++) {var r=c.recipes().get(i);row.addTerm(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}
                model.addGreaterOrEqual(row, k.equals(target) ? amount : -stock.getOrDefault(k,0L));
            }
            if (!model.validate().isEmpty()) throw new AssertionError(model.validate());
            CpSolver solver=new CpSolver();solver.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(5.0);
            solver.getParameters().setCpModelPresolve(false); if(Boolean.getBoolean("study.no_lp")) solver.getParameters().setLinearizationLevel(0); built=System.nanoTime(); var result=solver.solve(model);end=System.nanoTime();status=result.name();
            if(result==CpSolverStatus.OPTIMAL||result==CpSolverStatus.FEASIBLE)
                for(int i=0;i<x.length;i++)counts.put(c.recipes().get(i).id(),BigInteger.valueOf(solver.value(x[i])));
            out.put("stats",solver.responseStats());
        } else {
            MPSolver solver=MPSolver.createSolver("SCIP");solver.setNumThreads(1);solver.setTimeLimit(5000);
            MPVariable[] x=new MPVariable[c.recipes().size()];
            for(int i=0;i<x.length;i++)x[i]=solver.makeIntVar(0,upper.get(c.recipes().get(i).id()),"x"+i);
            for(String k:keys(c,stock)) {
                var row=solver.makeConstraint(k.equals(target)?amount:-stock.getOrDefault(k,0L),Double.POSITIVE_INFINITY,k);
                for(int i=0;i<x.length;i++){var r=c.recipes().get(i);row.setCoefficient(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}
            }
            built=System.nanoTime();var result=solver.solve();end=System.nanoTime();status=result.name();
            if(result==MPSolver.ResultStatus.OPTIMAL||result==MPSolver.ResultStatus.FEASIBLE)
                for(int i=0;i<x.length;i++){double n=x[i].solutionValue();long v=Math.round(n);if(Math.abs(n-v)>1e-5)throw new AssertionError("fractional native count");counts.put(c.recipes().get(i).id(),BigInteger.valueOf(v));}
            out.put("version",solver.solverVersion());out.put("nodes",solver.nodes());solver.delete();
        }
        if(!counts.isEmpty())replay(c,g,amount,counts);
        out.put("result",status);out.put("verified",!counts.isEmpty());out.put("counts",counts);
        out.put("ms",(end-start)/1e6);out.put("build_ms",(built-start)/1e6);out.put("solve_ms",(end-built)/1e6);
        return out;
    }
    static Map<String,Object> cgse(QuantitySweep.Catalog c, JsonObject g, long amount, boolean metrics, boolean countsOnly) {
        long start=System.nanoTime(); var compiler=c.compiler(); var stock=QuantitySweep.amounts(g.getAsJsonObject("stock"));
        var budget=new PlanningBudget(5000,20_000_000,128L<<20,()->false,System::nanoTime);
        if(metrics)budget.enableMetrics();
        GraphPlan<String> plan=null;String failure=null;
        if (countsOnly) {
            try(var search=new IntegerCountSearch<>(compiler,g.get("target").getAsString(),amount,stock,Map.of(),Set.of(),Set.of(),true,true,budget,start)) {
                while(true) {if(search.step()){if(search.paused())search.resume();else break;}}
                plan=search.result();
            }catch(PlanningBudget.Exhausted e){failure=e.limit().name();}
        } else {
            var policy=new CatalystPolicy(c.parallel(),c.extra());
            var work=new CatalystPlanningWork<String>(policy,budget,p->new GraphPlanningWork<>(compiler,g.get("target").getAsString(),amount,stock,c.external(),Map.of(),true,true,budget).catalysts(p));
            try {
                try {while(!work.step()){}plan=work.result();}catch(PlanningBudget.Exhausted e){plan=work.limited(e);}
            } finally { work.close(); }
        }
        long end=System.nanoTime();
        var out=new LinkedHashMap<String,Object>();
        if(plan!=null && plan.feasible()) {
            PlanVerifier.verifyRuntimeInventory(plan);replay(c,g,amount,plan.patternTimesExact());
            out.put("counts",plan.patternTimesExact());
        }
        out.put("result",plan==null?(failure==null?"UNKNOWN":failure):plan.result().name());
        out.put("verified",plan!=null&&plan.feasible());out.put("ms",(end-start)/1e6);
        out.put("work",budget.nodes());out.put("peak_bytes",budget.peakBytes());out.put("diagnostics",budget.diagnostics());
        if(metrics)out.put("strategies",budget.metrics().strategies());
        return out;
    }
    public static void main(String[] args)throws Exception {
        Loader.loadNativeLibraries();
        var gs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        boolean profile=args.length>3 && args[3].equals("profile");
        var first=gs.get(0).getAsJsonObject();var warm=QuantitySweep.catalog(first.getAsJsonObject("catalog"));
        for(int i=0;i<5;i++){cgse(warm,first,1,false,false);nativeSolve("cp_sat",warm,first,1);nativeSolve("scip",warm,first,1);}
        try(var writer=Files.newBufferedWriter(Path.of(args[1]))) {
            for(int rep=0;rep<Integer.parseInt(args[2]);rep++)for(var element:gs) {
                var g=element.getAsJsonObject();var c=QuantitySweep.catalog(g.getAsJsonObject("catalog"));
                for(var request:g.getAsJsonArray("requests")) {
                    long amount=request.getAsJsonObject().get("amount").getAsLong();
                    var row=new LinkedHashMap<String,Object>();row.put("id",g.get("id").getAsString());row.put("amount",amount);row.put("repeat",rep);row.put("recipes",c.recipes().size());
                    row.put("cgse",cgse(c,g,amount,profile,false));
                    if(profile)row.put("counts_only",cgse(c,g,amount,true,true));
                    else {row.put("cp_sat",nativeSolve("cp_sat",c,g,amount));row.put("scip",nativeSolve("scip",c,g,amount));}
                    writer.write(JSON.toJson(row));writer.newLine();writer.flush();
                    System.out.println(row.get("id")+" amount="+amount+" repeat="+rep+" cgse="+((Map<?,?>)row.get("cgse")).get("result"));
                }
            }
        }
    }
}
