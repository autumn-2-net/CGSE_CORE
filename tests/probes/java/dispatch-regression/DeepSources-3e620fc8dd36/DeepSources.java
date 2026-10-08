import java.util.*;import org.cgse.core.*;
public class DeepSources{
 public static void main(String[]args){
  for(long n:new long[]{2147483647L,Long.MAX_VALUE})for(int paths:new int[]{4,16,64}){
   int depth=(args.length==0?1024:Integer.parseInt(args[0]))/paths;var rs=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
   for(int i=0;i<paths;i++){
    stock.put("R"+i,n);
    for(int d=0;d<depth;d++)rs.add(SourceBoundary.r("r"+i+"_"+d,Map.of(d==0?"R"+i:"I"+i+"_"+(d-1),1L),Map.of(d==depth-1?"Q":"I"+i+"_"+d,1L)));
   }
   rs.add(SourceBoundary.r("p",Map.of("Q",(long)paths),Map.of("P",1L)));
   SourceBoundary.run("deep-split-"+paths+"x"+depth,rs,"P",n,stock,true);
   stock.put("R"+(paths-1),n-1);SourceBoundary.run("deep-short-"+paths+"x"+depth,rs,"P",n,stock,false);
  }
  SourceBoundary.run("integer-rounding",List.of(SourceBoundary.r("r",Map.of("A",3L),Map.of("B",2L))),"B",3,Map.of("A",5L),false);
 }
}
