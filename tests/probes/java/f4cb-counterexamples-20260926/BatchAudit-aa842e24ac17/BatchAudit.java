package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.nio.file.*;

/** Diagnostic entry points, not a replacement solver and not performance ranking. */
public final class BatchAudit {
    static void conservedTerminal(BatchProbe.Test test) {
        if(!Set.of("multiway","exact_cover","shared_exclusion","odd_matching").contains(test.family()))return;
        var f=test.fixture();Map<String,BigInteger> weights=new LinkedHashMap<>();
        for(String key:f.stock().keySet())if(key.matches("U\\d+"))weights.put(key,BigInteger.ONE);
        if(test.family().equals("exact_cover"))weights.put("COUNT",BigInteger.valueOf(3));
        else if(test.family().equals("odd_matching"))weights.put("PAIR",BigInteger.TWO);
        else for(var recipe:f.recipes())for(String key:recipe.outputs().keySet())if(key.matches("D\\d+"))weights.put(key,BigInteger.ONE);
        BigInteger initial=BigInteger.ZERO,spent=null;
        for(var w:weights.entrySet())initial=initial.add(w.getValue().multiply(BigInteger.valueOf(f.stock().getOrDefault(w.getKey(),0L))));
        for(var recipe:f.recipes()){
            BigInteger delta=BigInteger.ZERO;
            for(var w:weights.entrySet())delta=delta.add(w.getValue().multiply(BigInteger.valueOf(recipe.outputs().getOrDefault(w.getKey(),0L)).subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(w.getKey(),0L)))));
            if(recipe.id().equals("finish"))spent=delta.negate();else if(delta.signum()!=0)throw new AssertionError("not conserved");
        }
        if(spent==null||spent.signum()<=0||!initial.divide(spent).equals(BigInteger.ONE))throw new AssertionError("not unit finish bound");
        var budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();
        try(var model=RecipeCountModel.create(new GraphCompiler<>(f.recipes()),f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,budget)){
            int finish=-1;for(int i=0;i<model.recipes.size();i++)if(model.recipes.get(i).id().equals("finish"))finish=i;
            if(finish<0)throw new AssertionError("finish absent");
            var rows=new ArrayList<>(model.constraints);rows.add(new ExactLinearProgram.Constraint(Map.of(finish,BigInteger.ONE),BigInteger.ONE));
            try(var bounds=new CountBounds(model.recipes.size(),rows,budget)){
                while(!bounds.step()){}
                try(var reduction=new CountReduction(rows,bounds.lowerBounds(),bounds.upperBounds(),budget)){
                    while(!reduction.step()){}
                    try(var bool=new CountBoolean(reduction.rows(),reduction.lower(),reduction.upper(),budget)){
                        while(!bool.step()){}
                        System.out.println("PROVEN_FINISH_BOUND witness="+(bool.counts()!=null)+" infeasible="+bool.infeasible()+" ms="+(System.nanoTime()-start)/1e6+" trace="+budget.diagnostics());
                    }
                }
            }
        }catch(PlanningBudget.Exhausted limit){System.out.println("PROVEN_FINISH_BOUND_LIMIT "+budget.diagnostics());}
    }
    public static void main(String[] args)throws Exception{
        BatchProbe.generate();Set<String> selected=new LinkedHashSet<>(Files.readAllLines(Path.of(args[0])));
        for(var test:BatchProbe.tests)if(selected.contains(test.fixture().name())){
            var f=test.fixture();System.out.println("CASE "+f.name());
            var budget=new PlanningBudget(10000,20_000_000,256L<<20,()->false,System::nanoTime);
            var compiler=new GraphCompiler<>(f.recipes());
            try(var model=RecipeCountModel.create(compiler,f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,budget)){
                if(model==null){System.out.println("MODEL_UNAVAILABLE");continue;}
                try(var bounds=new CountBounds(model.recipes.size(),model.constraints,budget)){
                    while(!bounds.step()){}
                    System.out.println("BOUNDS blocked="+bounds.blocked());
                    if(bounds.blocked())continue;
                    try(var reduction=new CountReduction(model.constraints,bounds.lowerBounds(),bounds.upperBounds(),budget)){
                        while(!reduction.step()){}
                        Map<String,Integer> domains=new TreeMap<>();for(int i=0;i<reduction.lower().length;i++)domains.merge(reduction.lower()[i]+".."+reduction.upper()[i],1,Integer::sum);
                        System.out.println("REDUCED vars="+model.recipes.size()+"->"+reduction.lower().length+" rows="+reduction.rows().size()+" domains="+domains);
                        try(var matching=new CountMeetInMiddle(reduction.rows(),reduction.lower(),reduction.upper(),budget)){
                            while(!matching.step()){}
                            System.out.println("MATCH witness="+(matching.counts()!=null)+" infeasible="+matching.infeasible()+" trace="+budget.diagnostics());
                        }
                        try(var bool=new CountBoolean(reduction.rows(),reduction.lower(),reduction.upper(),budget)){
                            while(!bool.step()){}
                            System.out.println("BOOLEAN witness="+(bool.counts()!=null)+" infeasible="+bool.infeasible()+" trace="+budget.diagnostics());
                        }
                    }
                    if(test.family().equals("binary_counter")){
                        boolean fixed=Arrays.equals(bounds.lowerBounds(),bounds.upperBounds());System.out.println("COUNTER_ALL_COUNTS_FIXED "+fixed);
                        if(fixed)try(var schedule=new CountSchedule<>(model,bounds.lowerBounds(),budget)){
                            while(!schedule.step()){}
                            System.out.println("COUNTER_SCHEDULE "+schedule.result()+" trace="+budget.diagnostics());
                        }
                    }
                }
            }catch(PlanningBudget.Exhausted limit){System.out.println("AUDIT_LIMIT "+budget.failureDetail()+" "+budget.diagnostics());}
            conservedTerminal(test);
        }
    }
}
