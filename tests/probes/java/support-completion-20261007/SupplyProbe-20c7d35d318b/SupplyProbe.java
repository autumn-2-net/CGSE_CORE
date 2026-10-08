package org.cgse.core;
import com.google.gson.*;import java.nio.file.*;import java.util.*;
public class SupplyProbe {
 public static void main(String[] args)throws Exception {
  var root=Path.of(args[0]);var manual=MixedSweep.read(root.resolve("manual.json"));var extra=MixedSweep.read(root.resolve("extra-virtual.json"));
  var compiler=MixedSweep.compiler(manual,extra,"extra-first",42);
  var config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
  try(var writer=Files.newBufferedWriter(Path.of(args[2]))){for(var request:config.getAsJsonArray("requests")){
   var q=request.getAsJsonObject();String target=q.get("target").getAsString();long amount=q.get("amount").getAsLong();
   var b=new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();
   try(var completion=new GraphSupportCompletion<>(compiler,manual.stock(),manual.external(),Set.of(),b)) {
    var rs=completion.complete(null,null,target,amount);var out=q.deepCopy();out.addProperty("recipes",rs.size());
    out.addProperty("completion_work",b.searchWork());out.addProperty("completion_diagnostics",b.diagnostics());
    out.add("support",MixedSweep.JSON.toJsonTree(rs.stream().map(GraphRecipe::id).toList()));
    try(var solve=new IntegerCountSearch<>(new GraphCompiler<>(rs),target,amount,manual.stock(),Map.of(),manual.external(),Set.of(),true,true,b,start)){
     try{while(!solve.step()){}var p=solve.result();if(p!=null){PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);}out.addProperty("feasible",p!=null);}
     catch(PlanningBudget.Exhausted e){out.addProperty("limit",e.limit().name());}
    }
    out.addProperty("work",b.searchWork());out.addProperty("ms",(System.nanoTime()-start)/1e6);out.addProperty("diagnostics",b.diagnostics());
    writer.write(MixedSweep.JSON.toJson(out));writer.newLine();writer.flush();System.out.println(q.get("id")+" feasible="+out.get("feasible")+" work="+b.searchWork());
   }
  }}
 }
}
