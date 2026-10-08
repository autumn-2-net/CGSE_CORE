package org.cgse.core;
import java.util.*;import java.nio.file.*;import java.math.BigInteger;import com.google.gson.*;
public class LongAudit {
 public static void main(String[] args)throws Exception {
 var o=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
 var stock=FixedReplay.longs(o.getAsJsonObject("stock"));var recipes=new ArrayList<GraphRecipe<String>>();
 for(var e:o.getAsJsonArray("recipes")){var r=e.getAsJsonObject();String id=r.get("id").getAsString();recipes.add(new GraphRecipe<>(id,id,FixedReplay.longs(r.getAsJsonObject("inputs")).entrySet().stream().map(t->new GraphRecipe.Slot<>(t.getKey(),t.getValue())).toList(),FixedReplay.longs(r.getAsJsonObject("outputs"))));}
 var compiler=new GraphCompiler<>(recipes);var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
 try(var model=RecipeCountModel.create(compiler,"GOAL",1,stock,Map.of(),Set.of(),Set.of(),true,budget);var bounds=new CountBounds(model.recipes.size(),model.constraints,budget)) {
  while(!bounds.step()){}System.out.println("BOUNDS work="+budget.nodes()+" blocked="+bounds.blocked()+" trace="+budget.diagnostics());
  var counts=bounds.lowerBounds();for(int i=0;i<counts.length;i++)System.out.println(model.recipes.get(i).id()+" "+counts[i]+".."+bounds.upperBounds()[i]);
  try(var schedule=new CountSchedule<>(model,counts,budget)){while(!schedule.step()){}System.out.println("SCHEDULE "+schedule.result()+" nodes="+budget.nodes()+" trace="+budget.diagnostics());}
 }
 var order=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
 var emitted=new HashSet<String>();var work=new GraphPlanner<>(compiler).begin("GOAL",1,stock,true,true,order);try{
  int i=0;while(!work.step())if(++i%100==0)for(String note:order.diagnostics().split(" \\| "))if(emitted.add(note))System.out.println(note);
  System.out.println("ORDER="+work.result().result()+" trace="+order.diagnostics());
 }finally{work.close();}
 }
}
