package org.cgse.core;
import java.nio.file.*;import java.util.*;import java.lang.reflect.*;
import static org.cgse.core.AdversarialProbe.*;
import static org.cgse.core.ContrastProbe.*;
/** Research-only ablation; sources and persistent production settings are untouched. */
public class WorkflowAblationProbe {
 public static void main(String[]a)throws Exception {
  Path input=Path.of(a[0]),out=Path.of(a[1]);Files.createDirectories(out);Field field=GraphPlanningWork.class.getDeclaredField("allocating");field.setAccessible(true);
  List<String> modes=a.length>2&&a[2].equals("compare")?List.of("observe","direct_fresh_same_jvm","drive_existing_at_handoff","drive_existing_no_proofs"):a.length>2?List.of(a[2]):List.of("observe","detach_allocation_proofs");int reps=a.length>3?Integer.parseInt(a[3]):2;
  for(int i=0;i<8;i++)run(ContrastProbe.dag(30),1000,false);
  for(String name:List.of("pebble_00511","pebble_01673"))for(String mode:modes)for(int rep=0;rep<reps;rep++){
   var f=FusionProbe.fixture(FusionProbe.map(new FusionProbe.Json(Files.readString(input.resolve(name+".json"))).value()));var b=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();var work=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).begin(f.target(),f.amount(),f.stock(),false,true,b);long firstAt=-1,calls=0;GraphPlan<String> result=null;AllocationSearch<String> direct=null;
   var row=new LinkedHashMap<String,Object>();row.put("case",name);row.put("mode",mode);row.put("rep",rep);
   try{
    if(mode.equals("direct_fresh_same_jvm")){
     direct=new AllocationSearch<>(new GraphCompiler<>(f.recipes()),f.target(),f.amount(),f.stock(),Set.of(),Map.of(),false,true,Set.of(),b,start);while(!direct.step()){};result=direct.result();
    }else while(true){
     @SuppressWarnings("unchecked") var alloc=(AllocationSearch<String>)field.get(work);
     if(alloc!=null){calls++;if(firstAt<0)firstAt=b.nodes();if(mode.equals("detach_allocation_proofs")||mode.equals("drive_existing_no_proofs"))alloc.proofs(null);
      if(mode.startsWith("drive_existing")){while(!alloc.step()){};result=alloc.result();break;}
     }
     if(work.step()){result=work.result();break;}
    }
    row.put("ms",(System.nanoTime()-start)/1e6);row.put("status",result==null?"NO_WITNESS":result.result().name());if(result!=null&&result.feasible()){verify(f,summary(result.steps(),result.recipes()));row.put("verified",true);}
   }catch(PlanningBudget.Exhausted e){row.put("ms",(System.nanoTime()-start)/1e6);row.put("status",e.toString().contains("SEARCH_LIMIT")?"SEARCH_LIMIT":"TIMEOUT");row.put("limit_detail",e.toString());}
   finally{if(direct!=null)direct.discard();work.close();}
   row.put("nodes",b.nodes());row.put("first_allocation_observed_at_work",firstAt);row.put("allocation_observations",calls);row.put("diagnostics",b.diagnostics());Files.writeString(out.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(name+" "+mode+" "+rep+" "+row.get("status")+" "+row.get("ms"));System.out.flush();
  }
 }
}
