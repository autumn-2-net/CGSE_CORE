package org.cgse.core;
import java.util.*;import java.io.*;import java.nio.file.*;
public class JumpReview {
 public static void main(String[] args)throws Exception {
  try(var in=new DataInputStream(Files.newInputStream(Path.of(args[0])))) {
   for(int n=in.readInt();n>0;n--){String name=GraphFixtureRegression.string(in),target=GraphFixtureRegression.string(in);long amount=in.readLong();String truth=GraphFixtureRegression.string(in);var stock=GraphFixtureRegression.amounts(in);var recipes=new ArrayList<GraphRecipe<String>>();
    for(int j=in.readInt();j>0;j--){String id=GraphFixtureRegression.string(in);var a=GraphFixtureRegression.amounts(in);var b=GraphFixtureRegression.amounts(in);recipes.add(new GraphRecipe<>(id,id,a.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),b));}
    var budget=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);long started=System.nanoTime();
    try {var model=RecipeCountModel.create(new GraphCompiler<>(recipes),target,amount,stock,Map.of(),Set.of(),Set.of(),true,budget);
     if(model==null){System.out.println(name+"\tMODEL_SKIP");continue;}
     try(var b=new CountBounds(model.recipes.size(),model.constraints,budget)){while(!b.step()){} var rows=new ArrayList<>(model.constraints);rows.addAll(b.tightened());try(var r=new CountReduction(rows,b.lowerBounds(),b.upperBounds(),budget)){while(!r.step()){}long before=budget.nodes();try(var jump=new CountJump(r.rows(),r.lower(),r.upper(),budget,Long.parseLong(args[1]))){while(!jump.step()){}var vector=r.expand(jump.counts());String state="UNRESOLVED";if(vector!=null){for(var row:model.constraints){var sum=java.math.BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(vector[t.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("Invalid original counts");}try(var schedule=new CountSchedule<>(model,vector,budget)){while(!schedule.step()){}state=schedule.result().toString();if(state.equals("WITNESS")&&truth.equals("UNSAT"))throw new AssertionError("False executable witness "+name);}}System.out.println(name+"\t"+state+"\tvariables="+r.variables()+"\tms="+(System.nanoTime()-started)/1e6+"\twork="+(budget.nodes()-before));System.out.println("TRACE "+budget.diagnostics());}}}
    }catch(PlanningBudget.Exhausted e){System.out.println(name+"\tLIMIT "+e.getMessage());}
   }
  }
 }
}
