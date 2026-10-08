package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class SingleViewProbe {
 public static void main(String[] args)throws Exception {
  var root=Path.of(args[0]);var manual=MixedSweep.read(root.resolve("manual.json"));var extra=MixedSweep.read(root.resolve("extra-virtual.json"));
  var compiler=MixedSweep.compiler(manual,extra,"extra-first",42);
  var config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
  try(var writer=Files.newBufferedWriter(Path.of(args[2]))){for(var request:config.getAsJsonArray("requests")){
   var q=request.getAsJsonObject();String target=q.get("target").getAsString();long amount=q.get("amount").getAsLong();
   for(int variant:new int[]{3,8,9,10}) {
    var b=new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();
    try(var ranking=GraphSourceRanking.createQuantitative(compiler,manual.stock(),manual.external(),target,true,b,393216);
        var work=new GraphStockViewWork<>(compiler,target,amount,manual.stock(),manual.external(),Map.of(),Set.of(),true,true,
         new CatalystPolicy(4096,64),b,start,variant,()->ranking)) {
      var out=q.deepCopy();out.addProperty("variant",variant);
      try{while(!work.step()){}var p=work.result();if(p!=null){PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);}out.addProperty("feasible",p!=null);}
      catch(PlanningBudget.Exhausted e){out.addProperty("limit",e.limit().name());}
      out.addProperty("work",b.searchWork());out.addProperty("ms",(System.nanoTime()-start)/1e6);out.addProperty("diagnostics",b.diagnostics());
      writer.write(MixedSweep.JSON.toJson(out));writer.newLine();writer.flush();System.out.println(q.get("id")+" variant="+variant+" "+out.get("feasible")+" work="+b.searchWork());
    }
   }
  }}
 }
}
