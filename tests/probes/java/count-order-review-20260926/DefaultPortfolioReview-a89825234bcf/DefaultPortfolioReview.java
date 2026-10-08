package org.cgse.core;
import java.util.*;
public class DefaultPortfolioReview {
 public static void main(String[]args){var chosen=new HashSet<Integer>();for(String a:args)chosen.add(Integer.parseInt(a));int max=Collections.max(chosen);var rng=new Random(90525117);
  for(int sample=0;sample<=max;sample++){
   int size=4+rng.nextInt(4),total=3+rng.nextInt(8),n=1+rng.nextInt(total);int[] initial=new int[size];for(int i=0;i<total;i++)initial[rng.nextInt(size-1)]++;
   var recipes=new ArrayList<GraphRecipe<String>>();for(int j=0,rs=8+rng.nextInt(21);j<rs;j++){var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();for(int k=0,tokens=1+rng.nextInt(4);k<tokens;k++){in.merge(""+rng.nextInt(size),1L,Long::sum);out.merge(""+rng.nextInt(size),1L,Long::sum);}recipes.add(new GraphRecipe<>("r"+j,"r"+j,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out));}
   if(!chosen.contains(sample))continue;
   System.out.println("CASE "+sample+" stock="+Arrays.toString(initial)+" amount="+n);for(var r:recipes)System.out.println(r.id()+" "+r.inputs()+" -> "+r.outputs());
   var stock=new HashMap<String,Long>();for(int i=0;i<initial.length;i++)if(initial[i]>0)stock.put(""+i,(long)initial[i]);var budget=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
   {var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),""+(initial.length-1),n,stock,Set.of(),Map.of(),false,false,budget);while(!work.step()){}var p=work.result();System.out.println("RESULT "+(p==null?"UNKNOWN":p.result()+" counts="+p.patternTimesExact())+" nodes="+budget.nodes()+" "+budget.diagnostics());work.close();}
  }
 }
}
