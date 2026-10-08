package org.cgse.core;
import java.util.*;import java.io.*;import java.nio.file.*;
public class BoundsProbeReview {
 public static void main(String[] args)throws Exception {
  try(var in=new DataInputStream(Files.newInputStream(Path.of(args[0])))) {
   for(int n=in.readInt();n>0;n--){String name=GraphFixtureRegression.string(in),target=GraphFixtureRegression.string(in);long amount=in.readLong();GraphFixtureRegression.string(in);var stock=GraphFixtureRegression.amounts(in);var recipes=new ArrayList<GraphRecipe<String>>();
    for(int j=in.readInt();j>0;j--){String id=GraphFixtureRegression.string(in);var a=GraphFixtureRegression.amounts(in);var b=GraphFixtureRegression.amounts(in);recipes.add(new GraphRecipe<>(id,id,a.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),b));}
    if(!name.equals("optional_lossy_0058"))continue;
    var budget=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);var model=RecipeCountModel.create(new GraphCompiler<>(recipes),target,amount,stock,Map.of(),Set.of(),Set.of(),true,budget);
    try(var b=new CountBounds(model.recipes.size(),model.constraints,budget)){while(!b.step()){}var lo=b.lowerBounds();var hi=b.upperBounds();for(int i=0;i<lo.length;i++)System.out.println(i+" "+model.recipes.get(i).id()+" "+lo[i]+".."+hi[i]);
     var rows=new ArrayList<>(model.constraints);rows.addAll(b.tightened());try(var r=new CountReduction(rows,lo,hi,budget)){while(!r.step()){}System.out.println("REDUCED "+r.variables()+" LO "+Arrays.toString(r.lower())+" HI "+Arrays.toString(r.upper()));for(var row:r.rows())System.out.println(row);}
    }
    System.out.println(budget.diagnostics());return;
   }
  }
 }
}
