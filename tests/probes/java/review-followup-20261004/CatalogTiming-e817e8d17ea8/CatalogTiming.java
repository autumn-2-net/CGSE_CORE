package org.cgse.core;
import java.util.*;import java.nio.file.*;import com.google.gson.Gson;
public class CatalogTiming {
 static List<GraphRecipe<String>> chain(int n){var out=new ArrayList<GraphRecipe<String>>();for(int i=0;i<n;i++)out.add(new GraphRecipe<>("r"+i,"r"+i,List.of(new GraphRecipe.Slot<>("k"+i,2),new GraphRecipe.Slot<>("raw"+i%3,3)),Map.of("k"+(i+1),3L)));return out;}
 public static void main(String[]args)throws Exception{var rows=new ArrayList<Map<String,Object>>();for(int wave=-8;wave<8;wave++)for(int n:new int[]{64,256})for(boolean hot:List.of(false,true)){
  var c=new GraphCompiler<>(chain(n));var b=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);if(hot)try(var m=RecipeCountModel.create(c,"k"+n,1,Map.of(),Map.of(),Set.of(),Set.of(),false,b)){}long start=System.nanoTime(),work=b.nodes();for(int i=0;i<400;i++){if(!hot)c=new GraphCompiler<>(chain(n));try(var m=RecipeCountModel.create(c,"k"+n,i+1,Map.of("k0",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),true,b)){if(m==null)throw new AssertionError();}}if(b.reservedBytes()!=0)throw new AssertionError("leak");if(wave>=0)rows.add(Map.of("hot",hot,"recipes",n,"wave",wave,"ms",(System.nanoTime()-start)/1e6,"work",b.nodes()-work));
 }Files.writeString(Path.of(args[0],"isolated-catalog-timing.json"),new Gson().toJson(rows));System.out.println("isolated catalog measurements="+rows.size());}
}
