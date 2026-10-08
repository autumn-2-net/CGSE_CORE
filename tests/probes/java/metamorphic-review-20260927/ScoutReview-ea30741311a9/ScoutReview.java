package org.cgse.core;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class ScoutReview {
    public static void main(String[] args)throws Exception {
        try(var in=new DataInputStream(Files.newInputStream(Path.of(args[0])))){
            in.readInt();String name=GraphFixtureRegression.string(in),target=GraphFixtureRegression.string(in);
            long amount=in.readLong();GraphFixtureRegression.string(in);var stock=GraphFixtureRegression.amounts(in);
            List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,GraphRecipe<String>> original=new HashMap<>();
            for(int n=in.readInt();n>0;n--){String id=GraphFixtureRegression.string(in);var inputs=GraphFixtureRegression.amounts(in);var outputs=GraphFixtureRegression.amounts(in);var recipe=new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs);recipes.add(recipe);original.put(id,recipe);}
            for(long quantum:new long[]{1,64,4096}){
                var b=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
                try(var counts=new IntegerCountSearch<>(new GraphCompiler<>(recipes),target,amount,stock,Map.of(),Set.of(),Set.of(),false,true,b,System.nanoTime())){
                    counts.scout(quantum);while(!counts.step()){}
                    if(!counts.paused()||counts.infeasible()||counts.result()!=null)throw new AssertionError("Premature conclusion at "+quantum);
                    long used=b.nodes();counts.resume();while(!counts.step()){}
                    var result=counts.result();if(result==null||!result.feasible())throw new AssertionError("Lost continuation");
                    if(b.nodes()<=used)throw new AssertionError("Work refund");
                    var summary=GraphFixtureRegression.summary(result.steps(),original,new IdentityHashMap<>());
                    for(var e:summary.need().entrySet())if(e.getValue().compareTo(java.math.BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)))>0)throw new AssertionError("Invalid prefix");
                    if(summary.delta().getOrDefault(target,java.math.BigInteger.ZERO).compareTo(java.math.BigInteger.valueOf(amount))<0)throw new AssertionError("Goal");
                }
            }
        }
        System.out.println("PASS: integer scouts pause without proofs, resume exact frontier and retain cumulative work at 1/64/4096 quanta");
    }
}
