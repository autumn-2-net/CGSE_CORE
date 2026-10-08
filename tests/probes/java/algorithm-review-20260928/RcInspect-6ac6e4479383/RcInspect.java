package org.cgse.core;
import java.io.*; import java.nio.file.*; import java.util.*; import java.math.*;
public class RcInspect {
 public static void main(String[] args)throws Exception {
  try(var in=new DataInputStream(Files.newInputStream(Path.of(args[0])))){
   for(int cases=in.readInt();cases>0;cases--){
    String name=GraphFixtureRegression.string(in), target=GraphFixtureRegression.string(in); long amount=in.readLong(); GraphFixtureRegression.string(in); var stock=GraphFixtureRegression.amounts(in);
    List<GraphRecipe<String>> recipes=new ArrayList<>();
    for(int r=in.readInt();r>0;r--){String id=GraphFixtureRegression.string(in); var a=GraphFixtureRegression.amounts(in);var b=GraphFixtureRegression.amounts(in);recipes.add(new GraphRecipe<>(id,id,a.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),b));}
    var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
    try(var m=RecipeCountModel.create(new GraphCompiler<>(recipes),target,amount,stock,Map.of(),Set.of(),Set.of(),true,budget)){
     System.out.println(name); for(int i=0;i<m.recipes.size();i++) System.out.println(i+" "+m.recipes.get(i));
     var rows=new ArrayList<>(m.constraints);int variables=m.recipes.size();
     for(int pass=0;pass<4;pass++){
      try(var b=new CountBounds(variables,rows,budget)){
       while(!b.step()){};
       System.out.println("BOUNDS "+pass+" blocked="+b.blocked()+" lower="+Arrays.toString(b.lowerBounds())+" upper="+Arrays.toString(b.upperBounds()));
       rows.addAll(b.tightened());
       try(var red=new CountReduction(rows,b.lowerBounds(),b.upperBounds(),budget)){
        while(!red.step()){};
        System.out.println("RED "+pass+" roots="+red.coordinates()+" lower="+Arrays.toString(red.lower())+" upper="+Arrays.toString(red.upper()));
        red.rows().forEach(System.out::println);
        variables=red.variables();rows=new ArrayList<>(red.rows());
       }
      }
     }
    }
   }
  }
 }
}
