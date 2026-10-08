package org.cgse.core;
import java.util.*;
import java.nio.file.*;
import java.math.BigInteger;
public class RegionalDebugProbe {
 public static void main(String[] args)throws Exception{
  var rs=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
  String id=null;var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
  for(String line:Files.readAllLines(Path.of(".local/dispatch-regression/sky2-catalog.tsv"))){var p=line.split("\t");if(p[0].equals("R")){if(id!=null)rs.add(recipe(id,in,out));id=p[1];in.clear();out.clear();}else if(p[0].equals("S"))stock.put(p[1],Long.valueOf(p[2]));else (p[0].equals("I")?in:out).put(p[1],Long.valueOf(p[2]));}
  rs.add(recipe(id,in,out));var compiler=new GraphCompiler<>(rs);var budget=new PlanningBudget(0,16000000,128L<<20,()->false,System::nanoTime);
  var compiled=compiler.compile("kubejs:hypercube",Map.of(),Set.of(),budget);
  var solve=new GraphSolve<>(compiled,"kubejs:hypercube",Long.MAX_VALUE,stock,Set.of(),Map.of(),true,true,budget,0,CatalystPolicy.MINIMAL,stock);
  while(!solve.step()){}
  var f=GraphSolve.class.getDeclaredField("demand");f.setAccessible(true);var demands=(Map<String,BigInteger>)f.get(solve);
  var recipes=compiled.regions().get(51).recipes();var keys=new LinkedHashSet<String>();var produced=new LinkedHashSet<String>();
  recipes.forEach(r->{keys.addAll(r.inputs().keySet());keys.addAll(r.outputs().keySet());produced.addAll(r.outputs().keySet());});
  var external=new LinkedHashSet<>(keys);external.removeAll(produced);
  var c=RecipeCountModel.class.getDeclaredConstructors()[0];c.setAccessible(true);
  @SuppressWarnings("unchecked") var model=(RecipeCountModel<String>)c.newInstance(recipes,List.copyOf(keys),"",0L,stock,Map.of(),external,false,budget);
  model.goals.clear();model.goals.putAll(demands);model.constraints.clear();
  for(String key:produced){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<recipes.size();i++){var d=RecipeCountModel.delta(recipes.get(i),key).negate();if(d.signum()!=0)terms.put(i,d);}model.constraints.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(stock.getOrDefault(key,0L)).subtract(model.goal(key))));}
  var bounds=new CountBounds(recipes.size(),model.constraints,budget);while(!bounds.step()){}
  f=CountBounds.class.getDeclaredField("lower");f.setAccessible(true);var counts=(BigInteger[])f.get(bounds);
  int violations=0;for(var row:model.constraints){BigInteger total=BigInteger.ZERO;for(var term:row.terms().entrySet())total=total.add(term.getValue().multiply(counts[term.getKey()]));if(total.compareTo(row.upper())>0)violations++;}
  System.out.println("bounds blocked="+bounds.blocked()+" violations="+violations+" nodes="+budget.nodes());
  for(int i=0;i<counts.length;i++)System.out.println("count "+counts[i]+" "+recipes.get(i).outputs());
  var schedule=new DebugSchedule<>(model,counts,budget);while(!schedule.step()){}
  System.out.println("schedule="+schedule.result()+" nodes="+budget.nodes());
  f=DebugSchedule.class.getDeclaredField("remaining");f.setAccessible(true);var remaining=(BigInteger[])f.get(schedule);f=DebugSchedule.class.getDeclaredField("held");f.setAccessible(true);var held=(Map<String,BigInteger>)f.get(schedule);
  for(int i=0;i<remaining.length;i++)if(remaining[i].signum()>0){var r=recipes.get(i);System.out.println("blocked remaining="+remaining[i]+" outputs="+r.outputs()+" lack="+r.inputs().entrySet().stream().filter(e->!external.contains(e.getKey())&&held.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(BigInteger.valueOf(e.getValue()))<0).toList());}
  if(schedule.witness()!=null){var byid=new LinkedHashMap<String,GraphRecipe<String>>();recipes.forEach(r->byid.put(r.id(),r));var s=new SummaryComputation<>(schedule.witness(),byid,budget);while(!s.step()){}System.out.println("required="+s.result().required()+" delta="+s.result().delta());}
 }
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue(),-1,e.getKey().startsWith("gtlcore:virtual_ingredient"),e.getKey().startsWith("gtlcore:virtual_ingredient"))).toList(),out);}
}
