package org.cgse.core;
import com.google.gson.*;import java.nio.file.*;import java.util.*;
public class StartupCountProbe {
 public static void main(String[] args)throws Exception {
  var root=Path.of(args[0]);var manual=MixedSweep.read(root.resolve("manual.json"));var extra=MixedSweep.read(root.resolve("extra-virtual.json"));
  var original=MixedSweep.compiler(manual,extra,"extra-first",42);
  var config=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
  try(var writer=Files.newBufferedWriter(Path.of(args[2]))){for(var request:config.getAsJsonArray("requests")){
   var q=request.getAsJsonObject();String target=q.get("target").getAsString();long amount=q.get("amount").getAsLong();
   var b=new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();
   Set<String> dead;
   try(var check=new MissingStockAnalysis<>(original,target,manual.stock(),manual.external(),Set.of(),Set.of(),true,b)){
    while(!check.step()){}dead=check.unreachableRecipes();
   }
   var kept=original.catalog().stream().filter(r->!dead.contains(r.id())).toList();
   var compiler=new GraphCompiler<>(kept);var closure=compiler.countClosure(target,Set.of(),Set.of(),8192,8192,b);
   var out=q.deepCopy();out.addProperty("kept",kept.size());out.addProperty("count_closure",closure==null?-1:closure.recipes().size());
   try(var solve=new IntegerCountSearch<>(compiler,target,amount,manual.stock(),Map.of(),manual.external(),Set.of(),true,true,b,start)){
    try{while(!solve.step()){}var p=solve.result();if(p!=null){PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);}out.addProperty("feasible",p!=null);}
    catch(PlanningBudget.Exhausted e){out.addProperty("limit",e.limit().name());}
   }
   out.addProperty("work",b.searchWork());out.addProperty("ms",(System.nanoTime()-start)/1e6);out.addProperty("diagnostics",b.diagnostics());
   writer.write(MixedSweep.JSON.toJson(out));writer.newLine();writer.flush();System.out.println(out.get("id")+" "+out.get("count_closure")+" "+out.get("feasible"));
  }}
 }
}
