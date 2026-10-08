package org.cgse.core;
import java.io.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

public class FallbackCases {
    public static void main(String[] args)throws Exception {
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            for(int remaining=in.readInt();remaining>0;remaining--) {
                String name=GraphFixtureRegression.string(in),target=GraphFixtureRegression.string(in);
                long amount=in.readLong();String truth=GraphFixtureRegression.string(in);var stock=GraphFixtureRegression.amounts(in);
                List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,GraphRecipe<String>> primitive=new LinkedHashMap<>();
                for(int n=in.readInt();n>0;n--) {
                    String id=GraphFixtureRegression.string(in);var inputs=GraphFixtureRegression.amounts(in);var outputs=GraphFixtureRegression.amounts(in);
                    var recipe=new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs);
                    recipes.add(recipe);primitive.put(id,recipe);
                }
                var compiler=new GraphCompiler<>(recipes);
                var budget=new PlanningBudget(250,131072,16L<<20,()->false,System::nanoTime);
                long start=System.nanoTime();String status;
                try {
                    var plan=GraphFallback.plan(compiler,target,amount,stock,Set.of(),Map.of(),false,true,budget);
                    var summary=GraphFixtureRegression.summary(plan.steps(),primitive,new IdentityHashMap<>());
                    Map<String,BigInteger> funded=new HashMap<>();stock.forEach((k,v)->funded.put(k,BigInteger.valueOf(v)));
                    plan.missingExact().forEach((k,v)->funded.merge(k,v,BigInteger::add));
                    for(var entry:summary.need().entrySet()) {
                        if(plan.initialExact().getOrDefault(entry.getKey(),BigInteger.ZERO).compareTo(entry.getValue())<0)throw new AssertionError(name+" unreserved prefix");
                        if(funded.getOrDefault(entry.getKey(),BigInteger.ZERO).compareTo(entry.getValue())<0)throw new AssertionError(name+" unfunded prefix");
                    }
                    if(summary.delta().getOrDefault(target,BigInteger.ZERO).add(plan.missingExact().getOrDefault(target,BigInteger.ZERO)).compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError(name+" missing target");
                    if(plan.feasible()&&truth.equals("UNSAT"))throw new AssertionError(name+" false feasible");
                    status=plan.feasible()?"FUNDED":"REFILL_PREVIEW";
                }catch(PlanningBudget.Exhausted limit){status=limit.limit().name();}
                if(budget.reservedBytes()!=0)throw new AssertionError("leak "+name);
                System.out.println(name+"\t"+status+"\tms="+(System.nanoTime()-start)/1e6+"\twork="+budget.nodes());
            }
        }
    }
}
