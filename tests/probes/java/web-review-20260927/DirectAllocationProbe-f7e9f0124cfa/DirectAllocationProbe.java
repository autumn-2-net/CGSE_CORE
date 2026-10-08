package org.cgse.core;
import java.nio.file.*;import java.util.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
/** Diagnostic route ablation only; unmodified production AllocationSearch. */
public class DirectAllocationProbe {
 public static void main(String[]a)throws Exception {
  Path input=Path.of(a[0]),out=Path.of(a[1]);long ms=Long.parseLong(a[2]);Set<String> selected=new HashSet<>(Files.readAllLines(Path.of(a[3])));Files.createDirectories(out);
  for(int i=0;i<8;i++)run(ContrastProbe.dag(30),1000,false);
  for(Path p:Files.list(input).sorted().toList()){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(p)).value());var f=FusionProbe.fixture(d);if(!selected.contains(f.name()))continue;
   for(int rep=0;rep<3;rep++){
    var row=new LinkedHashMap<String,Object>();row.put("case",f.name());row.put("rep",rep);row.put("budget_ms",ms);long start=System.nanoTime();var b=new PlanningBudget(ms,20_000_000,256L<<20,()->false,System::nanoTime);AllocationSearch<String> search=null;
    try {
     search=new AllocationSearch<>(new GraphCompiler<>(f.recipes()),f.target(),f.amount(),f.stock(),Set.of(),Map.of(),false,true,Set.of(),b,start);
     while(!search.step()){};var plan=search.result();row.put("ms",(System.nanoTime()-start)/1e6);row.put("status",plan==null?"NO_WITNESS":plan.result().name());
     if(plan!=null&&plan.feasible()){verify(f,summary(plan.steps(),plan.recipes()));row.put("verified",true);}
    }catch(Throwable error){row.put("status","LIMIT_OR_ERROR");row.put("error",error.toString());row.put("ms",(System.nanoTime()-start)/1e6);}
    finally{if(search!=null)search.discard();}
    row.put("nodes",b.nodes());row.put("diagnostics",b.diagnostics());Files.writeString(out.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" "+rep+" "+row.get("status")+" "+row.get("ms"));System.out.flush();
   }
  }
 }
}
