import java.util.*;
import java.nio.file.*;
import org.cgse.core.*;
public class Sky2Replay {
 public static void main(String[] args)throws Exception{
  var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
  String id=null;var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
  for(String line:Files.readAllLines(Path.of(".local/dispatch-regression/sky2-catalog.tsv"))){var p=line.split("\t");if(p[0].equals("R")){if(id!=null)recipes.add(recipe(id,in,out));id=p[1];in.clear();out.clear();}else if(p[0].equals("S"))stock.put(p[1],Long.valueOf(p[2]));else (p[0].equals("I")?in:out).put(p[1],Long.valueOf(p[2]));}
  recipes.add(recipe(id,in,out));var compiler=new GraphCompiler<>(recipes);
  var compiled=compiler.compile("kubejs:hypercube",Map.of(),Set.of(),new PlanningBudget(0,8000000,()->false));
  for(int i=0;i<compiled.regions().size();i++){var region=compiled.regions().get(i);if(region.cyclic()){System.out.println("REGION "+i+" size="+region.recipes().size());region.recipes().forEach(r->System.out.println(r.id()+" "+r.inputs()+" -> "+r.outputs()));}}
  for(long n:new long[]{2147483647,Long.MAX_VALUE}){var budget=new PlanningBudget(0,16000000,128L<<20,()->false,System::nanoTime);var work=new GraphPlanningWork<>(compiler,"kubejs:hypercube",n,stock,true,true,budget).catalysts(CatalystPolicy.MINIMAL);while(!work.step()){}var p=work.result();System.out.println("RESULT n="+n+" "+p.result()+" nodes="+budget.nodes()+" missing="+p.missingExact()+" diag="+budget.diagnostics());}
 }
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue(),-1,e.getKey().startsWith("gtlcore:virtual_ingredient"),e.getKey().startsWith("gtlcore:virtual_ingredient"))).toList(),out);}
}
