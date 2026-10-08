import java.util.*;import java.math.*;import org.cgse.core.*;
public class PreviewProbe{
 public static void main(String[]args)throws Exception{
  var cl=Class.forName("org.cgse.core.DemandExpansion");
  var ctor=cl.getDeclaredConstructor(Map.class,Map.class,Map.class,PlanningBudget.class,boolean.class);ctor.setAccessible(true);
  var step=cl.getDeclaredMethod("step");step.setAccessible(true);
  for(int d:new int[]{4,24,60}){
   var c=ComplexCycleStress.nested(d,2,1,1);var compiler=new GraphCompiler<>(c.recipes());var rs=new LinkedHashMap<String,GraphRecipe<String>>();
   var q=new ArrayDeque<String>();q.add(c.target());var visited=new HashSet<String>();
   while(!q.isEmpty()){String key=q.removeFirst();if(visited.add(key))for(var r:compiler.producers(key)){rs.putIfAbsent(r.id(),r);q.addAll(r.inputs().keySet());}}
   var stock=new HashMap<String,BigInteger>();c.stock().forEach((k,v)->stock.put(k,BigInteger.valueOf(k.equals("R")?v-1:v)));
   var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);var exp=ctor.newInstance(rs,stock,Map.of(c.target(),BigInteger.ONE),b,true);
   while(!(boolean)step.invoke(exp)){}
   System.out.print("PREVIEW depth="+d+" work="+b.nodes());for(String name:List.of("failed","allowance","summaryWork","result","frames","path","rootPasses")){
    var f=cl.getDeclaredField(name);f.setAccessible(true);var v=f.get(exp);System.out.print(" "+name+"="+(v instanceof Collection a?a.size():name.equals("result")?v!=null:v));
    if(name.equals("frames") && v instanceof Collection a && !a.isEmpty()){Object fr=a.iterator().next();for(String fn:List.of("key","producer","iterations","supplementing")){var ff=fr.getClass().getDeclaredField(fn);ff.setAccessible(true);System.out.print(" frame_"+fn+"="+ff.get(fr));}}
   }System.out.println();
  }
 }
}
